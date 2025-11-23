package bnmm.controller

import bnmm.phase.SynapticPhasePorts
import bnmm.phase.SomaticPhasePorts
import bnmm.phase.EmissionPhasePorts
import bnmm.phase.RefractoryPhasePorts
import cyclix.Generic
import hwast.PORT_DIR
import hwast.hw_dim_static
import hwast.hw_imm
import hwast.hw_var

/**
 * Control plan describes the execution order of phase handlers.
 */
data class ControlPhase(
    val name: String
) {
    init {
        require(name.isNotBlank()) { "Control phase name must not be blank" }
    }
}

data class ControlPlan(val phases: List<ControlPhase>) {
    init {
        require(phases.isNotEmpty()) { "Control plan must contain at least one phase" }
    }
}

/**
 * Static configuration of the controller FSM.
 * @param name suffix for generated signals
 * @param loop when true, restarts from the first phase after the last completes
 * @param stepByTick when true, transitions are allowed only when tick=1 (if provided)
 */
data class ControllerConfig(
    val name: String = "ctrl",
    val loop: Boolean = false,
    val stepByTick: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Controller name must not be blank" }
    }
}

/**
 * Common wrapper for phase control signals so the controller can remain agnostic
 * of the concrete phase type.
 */
data class PhaseControlPorts(
    val start: hw_var,
    val busy: hw_var,
    val done: hw_var
)

/**
 * External interface of the controller FSM.
 */
data class ControllerPorts(
    val start: hw_var,
    val busy: hw_var,
    val done: hw_var,
    val phaseIndex: hw_var
)

private object ControlMath {
    fun log2ceil(value: Int): Int {
        require(value > 0) { "log2ceil expects positive value" }
        var v = value - 1
        var r = 0
        while (v > 0) {
            v = v shr 1
            r++
        }
        return if (r == 0) 1 else r
    }
}

class Controller(private val instName: String = "ctrl") {

    /**
     * Builds an FSM that walks through the provided phases in order. It asserts
     * `start` for a single cycle when entering a phase, waits for `done`, and
     * then advances to the next phase or finishes.
     *
     * @param g Cyclix container
     * @param cfg static configuration flags
     * @param plan ordered list of phases to execute
     * @param phasePorts mapping from phase name to its control signals
     * @param tick optional tick gate when stepByTick=true
     */
    fun emit(
        g: Generic,
        cfg: ControllerConfig,
        plan: ControlPlan,
        phasePorts: Map<String, PhaseControlPorts>,
        tick: hw_var? = null
    ): ControllerPorts {
        require(plan.phases.all { phasePorts.containsKey(it.name) }) {
            "All phases in the plan must have provided control ports"
        }

        val name = cfg.name
        val planSize = plan.phases.size
        val phaseIdxWidth = ControlMath.log2ceil(planSize)

        val start_i = g.uport("start_$name", PORT_DIR.IN, hw_dim_static(1), "0")
        val busy_o = g.uport("busy_$name", PORT_DIR.OUT, hw_dim_static(1), "0")
        val done_o = g.uport("done_$name", PORT_DIR.OUT, hw_dim_static(1), "0")

        val phaseIdx = g.uglobal("phase_idx_$name", hw_dim_static(phaseIdxWidth), "0")
        val phaseIdxNext = g.uglobal("phase_idx_n_$name", hw_dim_static(phaseIdxWidth), "0")
        phaseIdx.assign(phaseIdxNext)

        val launching = g.uglobal("launch_$name", hw_dim_static(1), "0")
        val launchingNext = g.uglobal("launch_n_$name", hw_dim_static(1), "0")
        launching.assign(launchingNext)

        val S_IDLE = 0
        val S_RUN = 1
        val state = g.uglobal("state_$name", hw_dim_static(1), "0")
        val stateNext = g.uglobal("state_n_$name", hw_dim_static(1), "0")
        state.assign(stateNext)

        val stepEn = g.uglobal("step_en_$name", hw_dim_static(1), "0")
        stepEn.assign(
            if (cfg.stepByTick) {
                tick?.let { g.eq2(it, 1) } ?: hw_imm(0)
            } else hw_imm(1)
        )

        // Default outputs
        busy_o.assign(0)
        done_o.assign(0)

        // Deassert all phase starts by default
        plan.phases.forEach { phase ->
            phasePorts[phase.name]?.start?.assign(0)
        }

        // IDLE state: wait for external start
        g.begif(g.eq2(state, S_IDLE)); run {
            g.begif(g.land(start_i, stepEn)); run {
            busy_o.assign(1)
            stateNext.assign(S_RUN)
            phaseIdxNext.assign(0)
            launchingNext.assign(1)
            done_o.assign(0)
        }; g.endif()
        }; g.endif()

        // RUN state: drive current phase, wait for done
        g.begif(g.eq2(state, S_RUN)); run {
            busy_o.assign(1)

            // Dispatch per-phase behavior via case on phaseIdx
            g.begcase(phaseIdx); run {
            plan.phases.forEachIndexed { idx, phase ->
                g.begbranch(idx); run {
                val ports = phasePorts.getValue(phase.name)

                // Pulse start when (re)entering this phase
                g.begif(g.eq2(launching, 1)); run {
                ports.start.assign(1)
                launchingNext.assign(0)
            }; g.endif()

                // Advance when phase signals done and tick allows
                g.begif(g.land(stepEn, ports.done)); run {
                val lastPhase = idx == planSize - 1
                if (lastPhase) {
                    if (cfg.loop) {
                        phaseIdxNext.assign(0)
                        launchingNext.assign(1)
                    } else {
                        stateNext.assign(S_IDLE)
                        busy_o.assign(0)
                        done_o.assign(1)
                    }
                } else {
                    phaseIdxNext.assign(g.add(phaseIdx, hw_imm(1)))
                    launchingNext.assign(1)
                }
            }; g.endif()
            }; g.endbranch()
            }
        }; g.endcase()
        }; g.endif()

        return ControllerPorts(
            start = start_i,
            busy = busy_o,
            done = done_o,
            phaseIndex = phaseIdx
        )
    }
}

/**
 * Convenience helpers to wrap concrete phase port bundles into the generic
 * PhaseControlPorts type expected by the controller.
 */
object PhasePortAdapter {
    fun fromSynaptic(ports: SynapticPhasePorts) = PhaseControlPorts(ports.start, ports.busy, ports.done)
    fun fromSomatic(ports: SomaticPhasePorts) = PhaseControlPorts(ports.start, ports.busy, ports.done)
    fun fromEmission(ports: EmissionPhasePorts) = PhaseControlPorts(ports.start, ports.busy, ports.done)
    fun fromRefractory(ports: RefractoryPhasePorts) = PhaseControlPorts(ports.start, ports.busy, ports.done)
}
