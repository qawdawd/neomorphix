package ir

import arch.SnnArch
import ast.AstBlock
import ast.AstCondition
import ast.AstLoop
import ast.AstNode
import ast.AstOperation
import ast.AstPhase
import ast.AstPhaseBlock
import symbols.SymbolEntry
import symbols.SymbolTable
import transaction.TxOperand

/**
 * Builder that converts phase-expanded AST nodes into IR structures. The builder keeps the code
 * straightforward so that the produced IR mirrors the AST closely while still referencing the
 * symbol table for metadata lookups.
 */
class IrBuilder {

    /**
     * Converts the provided AST root into an [IrProgram]. The AST is expected to contain phase
     * blocks produced by [neomorphix.ast.TrAstBuilder.attachPhaseRegions] and loop-expanded bodies.
     */
    fun fromAst(ast: AstBlock, symbols: SymbolTable, arch: SnnArch): IrProgram {
        val phases = ast.statements.mapNotNull { node ->
            when (node) {
                is AstPhaseBlock -> IrPhaseBlock(mapPhase(node.phase), convertBlock(node.body, symbols))
                else -> null
            }
        }
        return IrProgram(phases, arch, symbols)
    }

    /**
     * Records an intention to pipeline the synaptic phase. The actual implementation will be added
     * in future revisions.
     */
    fun pipelineSynapticPhase(program: IrProgram, details: String? = null): IrProgram {
        program.addTransformation(IrTransformNote(IrTransformKind.PIPELINE_SYNAPTIC, details))
        return program
    }

    /** Registers an intention to parallelise the somatic phase. */
    fun parallelizeSomatic(program: IrProgram, details: String? = null): IrProgram {
        program.addTransformation(IrTransformNote(IrTransformKind.PARALLEL_SOMATIC, details))
        return program
    }

    /** Registers an intention to parallelise the emission phase. */
    fun parallelizeEmission(program: IrProgram, details: String? = null): IrProgram {
        program.addTransformation(IrTransformNote(IrTransformKind.PARALLEL_EMISSION, details))
        return program
    }

    /** Registers an intention to parallelise the refractory phase. */
    fun parallelizeRefractory(program: IrProgram, details: String? = null): IrProgram {
        program.addTransformation(IrTransformNote(IrTransformKind.PARALLEL_REFRACTORY, details))
        return program
    }

    /** Registers an intention to apply synaptic parameter packing. */
    fun applyPacking(program: IrProgram, details: String? = null): IrProgram {
        program.addTransformation(IrTransformNote(IrTransformKind.APPLY_PACKING, details))
        return program
    }

    /**
     * Produces a readable representation of the IR program. The output lists phases, loops and
     * nested statements with indentation to make manual inspection easier.
     */
    fun dump(program: IrProgram): String {
        val builder = StringBuilder()
        builder.appendLine("IrProgram")
        program.phases.forEach { phase ->
            builder.appendLine("  Phase ${phase.phase}")
            dumpBlock(phase.body, builder, indent = 2)
        }
        if (program.plannedTransformations.isNotEmpty()) {
            builder.appendLine("  Planned transformations:")
            program.plannedTransformations.forEach { note ->
                val details = note.details?.let { ": $it" } ?: ""
                builder.appendLine("    - ${note.kind}$details")
            }
        }
        return builder.toString()
    }

    private fun convertBlock(block: AstBlock, symbols: SymbolTable): IrBlock {
        val statements = block.statements.mapNotNull { convertNode(it, symbols) }
        return IrBlock(statements)
    }

    private fun requireTarget(op: AstOperation, ctx: String): transaction.TxField =
        requireNotNull(op.target) { "Operation $ctx must have a non-null target" }

    private fun convertNode(node: AstNode, symbols: SymbolTable): IrStatement? = when (node) {
        is AstOperation -> convertOperation(node, symbols)
        is AstCondition -> convertCondition(node, symbols)
        is AstLoop -> convertLoop(node, symbols)
        is AstPhaseBlock -> null
    }

    private fun convertOperation(operation: AstOperation, symbols: SymbolTable): IrStatement {
        return when (operation.kind) {
            ast.OperationKind.ASSIGNMENT -> {
                val target = requireSymbol(requireTarget(operation, "ASSIGNMENT"), symbols)
                val value  = toValue(operation.operands.first(), symbols)
                IrAssignment(target, value)
            }
            ast.OperationKind.BINARY,
            ast.OperationKind.UNARY -> {
                val target   = requireSymbol(requireTarget(operation, "ALU"), symbols)
                val operands = operation.operands.map { toValue(it, symbols) }
                val opcode   = requireNotNull(operation.opcode) { "Opcode must be present for IR operation" }
                IrOperation(opcode, target, operands)
            }
            ast.OperationKind.EMIT -> {
                val emitStmt = operation.statement as transaction.TxStatement.Emit
                val target   = operation.target?.let { requireSymbol(it, symbols) } // опционально
                IrEmit(emitStmt.emitId, target)
            }
        }
    }

    private fun convertCondition(condition: AstCondition, symbols: SymbolTable): IrConditional {
        val rootCondition = toIrCondition(condition.condition, symbols)
        val thenBlock = convertBlock(condition.thenBlock, symbols)
        val elseIfs = condition.elseIfBranches.map {
            IrConditionalBranch(
                condition = toIrCondition(it.condition, symbols),
                body = convertBlock(it.body, symbols)
            )
        }
        val elseBlock = condition.elseBlock?.let { convertBlock(it, symbols) }
        return IrConditional(rootCondition, thenBlock, elseIfs, elseBlock)
    }

    private fun convertLoop(loop: AstLoop, symbols: SymbolTable): IrLoop {
        val iterator = IrIterator(
            name = loop.descriptor.iterator,
            count = loop.descriptor.count,
            kind = loop.descriptor.kind
        )
        val body = convertBlock(loop.body, symbols)
        return IrLoop(iterator, body)
    }

    private fun toIrCondition(condition: transaction.TxCondition, symbols: SymbolTable): IrCondition {
        return IrCondition(
            left = toValue(condition.left, symbols),
            comparison = condition.comparison,
            right = toValue(condition.right, symbols)
        )
    }

    private fun toValue(operand: TxOperand, symbols: SymbolTable): IrValue = when (operand) {
        is TxOperand.Constant -> IrValue.Constant(operand.value)
        is TxOperand.FieldRef -> {
            val entry = requireSymbol(operand.field, symbols)
            IrValue.Symbol(entry)
        }
        is TxOperand.ExternalFieldRef -> {
            val entry = symbols.resolveOperand(operand.name)
                ?: error("Operand '${operand.name}' is not registered in the symbol table")
            IrValue.Symbol(entry)
        }
        is TxOperand.EmitReference -> {
            val name = "emit#${operand.emitId}"
            val entry = symbols.resolveOperand(name)
                ?: error("Emit result '$name' is not registered in the symbol table")
            IrValue.Symbol(entry)
        }
    }

    private fun requireSymbol(field: transaction.TxField, symbols: SymbolTable): SymbolEntry {
        return symbols.resolveField(field.name)
            ?: error("Field '${field.name}' is not registered in the symbol table")
    }

    private fun mapPhase(phase: AstPhase): IrPhase = when (phase) {
        AstPhase.SYNAPTIC -> IrPhase.SYNAPTIC
        AstPhase.SOMATIC -> IrPhase.SOMATIC
        AstPhase.EMISSION -> IrPhase.EMISSION
        AstPhase.REFRACTORY -> IrPhase.REFRACTORY
    }

    private fun dumpBlock(block: IrBlock, sink: StringBuilder, indent: Int) {
        val prefix = "  ".repeat(indent)
        block.statements.forEach { statement ->
            when (statement) {
                is IrAssignment -> {
                    sink.appendLine("${prefix}${statement.target.name} := ${renderValue(statement.value)}")
                }
                is IrOperation -> {
                    val operands = statement.operands.joinToString(", ") { renderValue(it) }
                    sink.appendLine("${prefix}${statement.target.name} ${statement.opcode} [$operands]")
                }
                is IrEmit -> {
                    val target = statement.target?.name?.let { "($it)" } ?: ""
                    sink.appendLine("${prefix}emit#${statement.emitId}$target")
                }
                is IrConditional -> {
                    sink.appendLine("${prefix}if ${renderCondition(statement.condition)}")
                    dumpBlock(statement.thenBlock, sink, indent + 1)
                    statement.elseIfBranches.forEach { branch ->
                        sink.appendLine("${prefix}else if ${renderCondition(branch.condition)}")
                        dumpBlock(branch.body, sink, indent + 1)
                    }
                    statement.elseBlock?.let {
                        sink.appendLine("${prefix}else")
                        dumpBlock(it, sink, indent + 1)
                    }
                }
                is IrLoop -> {
                    sink.appendLine("${prefix}loop ${statement.iterator.name} (count=${statement.iterator.count}, kind=${statement.iterator.kind})")
                    dumpBlock(statement.body, sink, indent + 1)
                }
            }
        }
    }

    private fun renderValue(value: IrValue): String = when (value) {
        is IrValue.Constant -> value.value.toString()
        is IrValue.Symbol -> value.entry.name
    }

    private fun renderCondition(condition: IrCondition): String {
        return "${renderValue(condition.left)} ${condition.comparison} ${renderValue(condition.right)}"
    }
}
