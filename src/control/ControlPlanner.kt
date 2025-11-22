package control

import ir.IrPhase
import layout.LayoutPlan
import phasebinding.PhaseBindingPlan

/** State representation for a simple FSM controller. */
data class ControlState(
    val name: String,
    val phase: IrPhase?,
    val description: String
)

data class FsmTransition(
    val from: String,
    val to: String,
    val condition: String
)

data class ControlPlan(
    val states: List<ControlState>,
    val transitions: List<FsmTransition>,
    val phaseOrder: List<IrPhase>,
    val notes: List<String>
)

/**
 * Builds a scheduler/FSM plan by walking the ordered phases and ensuring each
 * receives a dedicated run state. The resulting plan is intentionally simple
 * so that it can be consumed by backends without additional inference.
 */
class ControlPlanner {

    fun plan(layoutPlan: LayoutPlan, bindingPlan: PhaseBindingPlan): ControlPlan {
        val states = mutableListOf<ControlState>()
        val transitions = mutableListOf<FsmTransition>()
        val notes = mutableListOf<String>()

        states += ControlState("idle", null, "Waiting for start signal")
        val phaseOrder = bindingPlan.bindings.keys.toList()
        var previous = "idle"
        phaseOrder.forEach { phase ->
            val stateName = "run_${phase.name.lowercase()}"
            states += ControlState(stateName, phase, "Execute ${phase.name.lowercase()} operations")
            transitions += FsmTransition(previous, stateName, conditionForPhase(layoutPlan, phase))
            previous = stateName
        }
        states += ControlState("complete", null, "All phases processed")
        transitions += FsmTransition(previous, "complete", "phase_done")

        if (bindingPlan.bindings.any { it.value.isEmpty() }) {
            notes += "Some phases only contribute control flow without operations"
        }
        if (layoutPlan.phases.syn.gateByTick) {
            notes += "Synaptic phase is gated by tick and selector timing"
        }

        return ControlPlan(states, transitions, phaseOrder, notes)
    }

    private fun conditionForPhase(layoutPlan: LayoutPlan, phase: IrPhase): String =
        if (phase == IrPhase.SYNAPTIC && layoutPlan.phases.syn.gateByTick) "tick_window_complete" else "phase_done"
}
