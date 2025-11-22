package naming

import control.ControlPlan
import ir.IrPhase
import layout.LayoutPlan
import phasebinding.PhaseBindingPlan

/** Base naming preferences used to seed unique RTL identifiers. */
data class Naming(
    val kernelPrefix: String = "bnmm",
    val tickName: String = "tick",
    val fifoInName: String = "spike_in",
    val fifoOutName: String = "spike_out",
    val selectorName: String = "selector0",
    val regPrefix: String = "cfg",
    val fsmName: String = "core_fsm",
    val dynPrefix: String = "dyn",
    val synInstName: String = "syn",
    val neurInstName: String = "neur",
    val emitInstName: String = "emit"
)

/** Tracks uniqueness of identifiers within a module scope. */
class NameScope {
    private val used = HashSet<String>()

    /** Reserve the provided name as-is, throwing if already used. */
    fun reserveExact(name: String): String {
        require(name !in used) { "Name collision: '$name' already used" }
        used += name
        return name
    }

    /** Allocate a unique identifier derived from [base]. */
    fun alloc(base: String): String {
        if (base !in used) {
            used += base
            return base
        }
        var i = 1
        while (true) {
            val candidate = "${base}_$i"
            if (candidate !in used) {
                used += candidate
                return candidate
            }
            i++
        }
    }
}

/** Assigned instance, port and register names for the generated kernel. */
data class AssignedNames(
    val tickInst: String,
    val fifoInInst: String,
    val fifoOutInst: String,
    val selectorInst: String,
    val regBankInst: String,
    val synInst: String,
    val neurInst: String,
    val emitInst: String,
    val wmemInsts: Map<String, String>,
    val dynMainInst: String,
    val dynExtraInsts: Map<String, String>,
    val fsmInst: String,
    val synPorts: Map<String, String>,
    val neurPorts: Map<String, String>,
    val emitPorts: Map<String, String>,
    val regApiToRtl: Map<String, String>
)

/** Aggregate naming plan consumed by downstream generators. */
data class NamingPlan(
    val kernelName: String,
    val phaseNames: Map<IrPhase, String>,
    val fsmStateNames: Map<String, String>,
    val assigned: AssignedNames
)

/**
 * Builds stable names for instances, ports and registers based on layout and control plans,
 * mirroring the behaviour of the ActiveCore Naming planner.
 */
object NamingPlanner {

    fun plan(
        controlPlan: ControlPlan,
        layoutPlan: LayoutPlan,
        bindingPlan: PhaseBindingPlan,
        naming: Naming = Naming()
    ): NamingPlan {
        val scope = NameScope()

        val kernelName = scope.alloc(naming.kernelPrefix)
        val phaseNames = controlPlan.phaseOrder.associateWith { phase ->
            scope.alloc(phase.name.lowercase())
        }
        val fsmStateNames = controlPlan.states.associate { state ->
            state.name to scope.alloc(state.name.lowercase())
        }

        val tickInst = scope.alloc(layoutPlan.tick.signalName)
        val fifoInInst = scope.alloc(naming.fifoInName)
        val fifoOutInst = scope.alloc(naming.fifoOutName)
        val selectorInst = scope.alloc(layoutPlan.selector.cfg.name.ifBlank { naming.selectorName })
        val regBankInst = scope.alloc("${naming.regPrefix}_bank")
        val synInst = scope.alloc(naming.synInstName)
        val neurInst = scope.alloc(naming.neurInstName)
        val emitInst = scope.alloc(naming.emitInstName)
        val fsmInst = scope.alloc(naming.fsmName)

        val wmemInsts = if (layoutPlan.wmems.values.map { it.cfg.name }.toSet().size == 1) {
            val shared = scope.alloc(layoutPlan.wmems.values.first().cfg.name)
            layoutPlan.wmems.keys.associateWith { shared }
        } else {
            layoutPlan.wmems.mapValues { (_, plan) -> scope.alloc(plan.cfg.name) }
        }

        val dynMainInst = scope.alloc("${naming.dynPrefix}_${layoutPlan.dyn.main.field}")
        val dynExtraInsts = layoutPlan.dyn.extra.associate { extra ->
            extra.field to scope.alloc("${naming.dynPrefix}_${extra.field}")
        }

        val synPorts = mapOf(
            "start_i" to "${synInst}_start",
            "gate_i" to "${synInst}_gate",
            "done_o" to "${synInst}_done",
            "busy_o" to "${synInst}_busy"
        )
        val neurPorts = mapOf(
            "start_i" to "${neurInst}_start",
            "done_o" to "${neurInst}_done",
            "busy_o" to "${neurInst}_busy"
        )
        val emitPorts = mapOf(
            "start_i" to "${emitInst}_start",
            "done_o" to "${emitInst}_done",
            "busy_o" to "${emitInst}_busy"
        )

        val regApiToRtl = layoutPlan.regBank.mapApiKeys.mapValues { (_, regName) ->
            scope.alloc("${naming.regPrefix}_${regName}")
        }

        val assigned = AssignedNames(
            tickInst = tickInst,
            fifoInInst = fifoInInst,
            fifoOutInst = fifoOutInst,
            selectorInst = selectorInst,
            regBankInst = regBankInst,
            synInst = synInst,
            neurInst = neurInst,
            emitInst = emitInst,
            wmemInsts = wmemInsts,
            dynMainInst = dynMainInst,
            dynExtraInsts = dynExtraInsts,
            fsmInst = fsmInst,
            synPorts = synPorts,
            neurPorts = neurPorts,
            emitPorts = emitPorts,
            regApiToRtl = regApiToRtl
        )

        return NamingPlan(kernelName, phaseNames, fsmStateNames, assigned)
    }
}
