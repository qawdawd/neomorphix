package bnmm.selector

import cyclix.Generic
import hwast.PORT_DIR
import hwast.hw_dim_static
import hwast.hw_imm
import hwast.hw_var
import semantics.PhaseParallelPlan

/**
 * Describes grouped traversal parameters for post-synaptic neurons. It mirrors
 * the semantic parallelism plan but stays lightweight for standalone use.
 */
data class NeuronSelectorPlan(
    val groupSize: Int,
    val totalGroups: Int,
    val activeGroups: Int,
    val remainder: Int,
    val notes: List<String> = emptyList()
) {
    companion object {
        fun fromPlan(plan: PhaseParallelPlan) = NeuronSelectorPlan(
            groupSize = plan.effectiveGroupSize,
            totalGroups = plan.totalGroups,
            activeGroups = plan.activeGroups,
            remainder = plan.remainder,
            notes = plan.notes
        )
    }
}

/**
 * Static configuration of the neuron selector.
 */
data class NeuronSelectorConfig(
    val name: String = "neuron_sel",
    val indexWidth: Int,
    val plan: NeuronSelectorPlan? = null,
    val stepByTick: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Selector name must not be blank" }
        require(indexWidth > 0) { "Index width must be positive" }
    }
}

/**
 * Runtime parameters supplied from the parent design.
 */
data class NeuronSelectorRuntime(
    val totalNeurons: hw_var,
    val baseIndex: hw_var? = null,
    val tick: hw_var? = null
)

/**
 * Ports exported by the selector for connection to phase units or an FSM.
 */
data class NeuronSelectorPorts(
    val start: hw_var,
    val busy: hw_var,
    val done: hw_var,
    val groupIndex: hw_var,
    val laneIndex: hw_var,
    val postIndex: hw_var,
    val laneValid: hw_var
)

/**
 * Helper factory for building a simple BRAM-like dynamic memory port for
 * neuron state. Provided for symmetry with the synapse selector demo use case.
 */
object NeuronStatePortFactory {
    fun create(g: Generic, name: String, addrWidth: Int, dataWidth: Int): hw_var {
        return g.uport(name, PORT_DIR.INOUT, hw_dim_static(dataWidth).apply { add(addrWidth, 0) }, "0")
    }
}

/**
 * Selector that iterates over post-synaptic neurons, optionally grouping them
 * according to a parallelism plan. It produces start/busy/done handshake
 * signals and exposes the current post index along with in-group lane info.
 */
class NeuronSelector(private val instName: String = "neuron_sel") {

    fun emit(
        g: Generic,
        cfg: NeuronSelectorConfig,
        runtime: NeuronSelectorRuntime
    ): NeuronSelectorPorts {
        val name = cfg.name
        val groupSize = cfg.plan?.groupSize ?: 1
        require(groupSize > 0) { "Group size must be positive" }

        val start_i = g.uglobal("start_$name", hw_dim_static(1), "0")
        val busy_o = g.uglobal("busy_$name", hw_dim_static(1), "0")
        val done_o = g.uglobal("done_$name", hw_dim_static(1), "0")

        val groupIdx = g.uglobal("group_$name", hw_dim_static(cfg.indexWidth), "0")
        val laneIdx = g.uglobal("lane_$name", hw_dim_static(cfg.indexWidth), "0")
        val postIdx = g.uglobal("post_$name", hw_dim_static(cfg.indexWidth), "0")
        val laneValid = g.uglobal("lane_valid_$name", hw_dim_static(1), "0")

        val baseLatched = g.uglobal("base_$name", hw_dim_static(cfg.indexWidth), "0")
        val processed = g.uglobal("processed_$name", hw_dim_static(cfg.indexWidth + 1), "0")

        val S_IDLE = 0
        val S_RUN = 1
        val state = g.uglobal("state_$name", hw_dim_static(1), "0")
        val stateNext = g.uglobal("state_n_$name", hw_dim_static(1), "0")
        state.assign(stateNext)

        val stepEn = g.uglobal("step_en_$name", hw_dim_static(1), "0")
        stepEn.assign(if (cfg.stepByTick) runtime.tick?.let { g.eq2(it, 1) } ?: hw_imm(0) else hw_imm(1))

        busy_o.assign(0)
        done_o.assign(0)
        laneValid.assign(0)

        g.begif(g.eq2(state, S_IDLE)); run {
            g.begif(g.eq2(start_i, 1)); run {
            busy_o.assign(1)
            stateNext.assign(S_RUN)
            groupIdx.assign(0)
            laneIdx.assign(0)
            processed.assign(0)
            baseLatched.assign(runtime.baseIndex ?: hw_imm(0))
        }; g.endif()
        }; g.endif()

        g.begif(g.eq2(state, S_RUN)); run {
            busy_o.assign(1)
            val doStep = g.land(stepEn, busy_o)

            // current absolute index = base + group*groupSize + lane
            val groupOffset = g.mul(groupIdx, hw_imm(groupSize))
            val absIndex = g.add(g.add(baseLatched, groupOffset), laneIdx)
            postIdx.assign(absIndex)

            laneValid.assign(g.less(processed, runtime.totalNeurons))

            g.begif(g.eq2(doStep, 1)); run {
            g.begif(g.eq2(laneValid, 1)); run {
            // advance counters only for valid lanes
            processed.assign(processed.plus(1))

            val lastLane = g.eq2(laneIdx, hw_imm(groupSize - 1))
            g.begif(lastLane); run {
            laneIdx.assign(0)
            groupIdx.assign(groupIdx.plus(1))
        }; g.endif()
            g.begelse(); run {
            laneIdx.assign(laneIdx.plus(1))
        }; g.endif()

            // completion check
            g.begif(g.eq2(processed.plus(1), runtime.totalNeurons));
            run {
                busy_o.assign(0)
                done_o.assign(1)
                stateNext.assign(S_IDLE)
            }; g.endif()
        }; g.endif()
            g.begelse(); run {
            // no valid lanes left; finish immediately
            busy_o.assign(0)
            done_o.assign(1)
            stateNext.assign(S_IDLE)
        }; g.endif()
        }; g.endif()
        }; g.endif()

        return NeuronSelectorPorts(
            start = start_i,
            busy = busy_o,
            done = done_o,
            groupIndex = groupIdx,
            laneIndex = laneIdx,
            postIndex = postIdx,
            laneValid = laneValid
        )
    }
}
