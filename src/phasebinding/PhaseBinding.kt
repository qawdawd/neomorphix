package phasebinding

import ir.IrAssignment
import ir.IrBlock
import ir.IrConditional
import ir.IrEmit
import ir.IrIterator
import ir.IrLoop
import ir.IrOperation
import ir.IrPhase
import ir.IrProgram
import layout.LayoutPlan
import transaction.OpCode

/** BNMM components that execute IR operations. */
enum class BnmmComponent { SYNAPTIC_UNIT, SOMATIC_UNIT, EMISSION_UNIT, REFRACTORY_UNIT }

data class BoundOperation(
    val target: String,
    val opcode: OpCode?,
    val component: BnmmComponent,
    val loopContext: List<IrIterator>
)

data class PhaseBindingPlan(
    val bindings: Map<IrPhase, List<BoundOperation>>,
    val notes: List<String>
)

/**
 * Produces a deterministic mapping between IR operations and BNMM components. The binder does not
 * mutate the IR and can be reused by code generation and control planning passes.
 */
class PhaseBinder(private val program: IrProgram) {

    fun bind(layoutPlan: LayoutPlan): PhaseBindingPlan {
        val bindings = linkedMapOf<IrPhase, List<BoundOperation>>()
        val notes = mutableListOf<String>()

        program.phases.forEach { phaseBlock ->
            val component = componentForPhase(phaseBlock.phase)
            val ops = collectOperations(phaseBlock.body, emptyList())
                .map { ref -> BoundOperation(ref.first, ref.second, component, ref.third) }
            bindings[phaseBlock.phase] = ops
            if (ops.isEmpty()) {
                notes += "Phase ${phaseBlock.phase.name.lowercase()} contains no executable operations"
            }
        }

        if (!layoutPlan.pipeline.enabled) {
            notes += "Pipeline disabled; bindings remain one-to-one with IR order"
        }

        return PhaseBindingPlan(bindings, notes)
    }

    private fun componentForPhase(phase: IrPhase): BnmmComponent = when (phase) {
        IrPhase.SYNAPTIC -> BnmmComponent.SYNAPTIC_UNIT
        IrPhase.SOMATIC -> BnmmComponent.SOMATIC_UNIT
        IrPhase.EMISSION -> BnmmComponent.EMISSION_UNIT
        IrPhase.REFRACTORY -> BnmmComponent.REFRACTORY_UNIT
    }

    private fun collectOperations(block: IrBlock, loopStack: List<IrIterator>): List<Triple<String, OpCode?, List<IrIterator>>> {
        val ops = mutableListOf<Triple<String, OpCode?, List<IrIterator>>>()
        block.statements.forEach { stmt ->
            when (stmt) {
                is IrAssignment -> ops += Triple(stmt.target.name, null, loopStack)
                is IrOperation -> ops += Triple(stmt.target.name, stmt.opcode, loopStack)
                is IrEmit -> ops += Triple(stmt.target?.name ?: "emit#${stmt.emitId}", null, loopStack)
                is IrConditional -> {
                    ops += collectOperations(stmt.thenBlock, loopStack)
                    stmt.elseIfBranches.forEach { branch ->
                        ops += collectOperations(branch.body, loopStack)
                    }
                    stmt.elseBlock?.let { ops += collectOperations(it, loopStack) }
                }
                is IrLoop -> ops += collectOperations(stmt.body, loopStack + stmt.iterator)
            }
        }
        return ops
    }
}
