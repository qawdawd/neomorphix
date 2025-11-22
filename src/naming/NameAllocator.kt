package naming

import control.ControlPlan
import ir.IrPhase

/** Deterministic allocator that produces repeatable names for generated artefacts. */
class NameAllocator(private val kernelPrefix: String = "bnmm") {
    private val counters = sortedMapOf<String, Int>()

    fun allocate(base: String): String {
        val next = counters.getOrDefault(base, 0) + 1
        counters[base] = next
        return "${base}_${next}"
    }

    fun planNames(controlPlan: ControlPlan): NamingPlan {
        val phaseNames = controlPlan.phaseOrder.associateWith { phase ->
            "${phase.name.lowercase()}_${allocate(phase.name.lowercase())}"
        }
        val stateNames = controlPlan.states.associate { state -> state.name to allocate(state.name) }
        val kernelName = "${kernelPrefix}_${allocate(kernelPrefix)}"
        return NamingPlan(kernelName, phaseNames, stateNames)
    }
}

data class NamingPlan(
    val kernelName: String,
    val phaseNames: Map<IrPhase, String>,
    val fsmStateNames: Map<String, String>
)
