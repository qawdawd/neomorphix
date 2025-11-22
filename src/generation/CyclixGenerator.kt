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
        builder.appendLine("// Memory layout: ${layoutPlan.memory.wordCount} word(s) x ${layoutPlan.memory.wordWidth}b, ports=${layoutPlan.memory.ports}")
        builder.appendLine("// Pipeline: enabled=${layoutPlan.pipeline.enabled}, stages=${layoutPlan.pipeline.stageCount}, latency=${layoutPlan.pipeline.stageLatencyCycles}")

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
