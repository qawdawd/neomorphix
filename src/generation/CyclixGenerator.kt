package generation

import control.ControlPlan
import ir.IrProgram
import layout.LayoutPlan
import naming.NamingPlan
import phasebinding.PhaseBindingPlan

/** Textual representation of a generated Cyclix kernel. */
data class GeneratedKernel(
    val name: String,
    val cyclixSnippet: String
)

/**
 * Combines previously computed plans to produce a synthetic Cyclix kernel description. The goal is
 * to keep the generator side-effect free so that exporting stages can re-use the produced snippet.
 */
class CyclixGenerator {

    fun generate(
        program: IrProgram,
        layoutPlan: LayoutPlan,
        bindingPlan: PhaseBindingPlan,
        controlPlan: ControlPlan,
        namingPlan: NamingPlan
    ): GeneratedKernel {
        val builder = StringBuilder()
        builder.appendLine("// Kernel: ${namingPlan.kernelName}")
        builder.appendLine("// Layers: ${program.architecture.layerCount}, neurons per layer: ${program.architecture.neuronsPerLayer}")
        builder.appendLine("// Tick: ${layoutPlan.tick.signalName} every ${layoutPlan.tick.cfg.timeslot}${layoutPlan.tick.cfg.unit} (clk=${layoutPlan.tick.cfg.clkPeriodNs}ns)")
        builder.appendLine(
            "// FIFO in: role=${layoutPlan.fifoIn.role}, width=${layoutPlan.fifoIn.cfg.dataWidth}, depth=${layoutPlan.fifoIn.cfg.depth}, tickDB=${layoutPlan.fifoIn.cfg.useTickDoubleBuffer}"
        )
        builder.appendLine(
            "// FIFO out: role=${layoutPlan.fifoOut.role}, width=${layoutPlan.fifoOut.cfg.dataWidth}, depth=${layoutPlan.fifoOut.cfg.depth}, tickDB=${layoutPlan.fifoOut.cfg.useTickDoubleBuffer}"
        )
        layoutPlan.wmems.entries.distinctBy { it.value.cfg.name }.forEach { (field, mem) ->
            val packInfo = mem.pack?.let { pack -> "packed (${pack.fields.keys.joinToString()})" } ?: "separate"
            builder.appendLine(
                "// Weight mem for $field -> ${mem.cfg.name}: ${mem.cfg.wordWidth}b x ${mem.cfg.depth} ($packInfo)"
            )
        }
        builder.appendLine("// Dyn main=${layoutPlan.dyn.main.field}, extras=${layoutPlan.dyn.extra.joinToString { it.field }}")
        builder.appendLine("// Selector: ${layoutPlan.selector.cfg.name}, addrW=${layoutPlan.selector.cfg.addrWidth}, tickGate=${layoutPlan.selector.cfg.stepByTick}")
        builder.appendLine("// Phases: synParam=${layoutPlan.phases.syn.synParamField ?: "none"}, emit cmp=${layoutPlan.phases.emit.cmp}")

        bindingPlan.bindings.forEach { (phase, ops) ->
            val phaseName = namingPlan.phaseNames[phase] ?: phase.name.lowercase()
            builder.appendLine("phase $phaseName {")
            if (ops.isEmpty()) {
                builder.appendLine("  // no operations")
            }
            ops.forEach { op ->
                val loopSuffix = if (op.loopContext.isEmpty()) "" else " @loops=${op.loopContext.joinToString { it.name }}"
                builder.appendLine("  // ${op.component} executes ${op.target}${loopSuffix}")
            }
            builder.appendLine("}")
        }

        builder.appendLine("fsm ${namingPlan.kernelName}_fsm {")
        controlPlan.states.forEach { state ->
            val mapped = namingPlan.fsmStateNames[state.name] ?: state.name
            builder.appendLine("  state $mapped // ${state.description}")
        }
        controlPlan.transitions.forEach { tr ->
            val from = namingPlan.fsmStateNames[tr.from] ?: tr.from
            val to = namingPlan.fsmStateNames[tr.to] ?: tr.to
            builder.appendLine("  $from -> $to when ${tr.condition}")
        }
        builder.appendLine("}")

        return GeneratedKernel(namingPlan.kernelName, builder.toString())
    }
}
