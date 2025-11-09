package ir

import ast.LoopKind
import symbols.SymbolEntry
import transaction.ComparisonOp
import transaction.OpCode

/**
 * Enumerates all supported phases of the transactional flow.
 */
enum class IrPhase {
    SYNAPTIC,
    SOMATIC,
    EMISSION,
    REFRACTORY
}

/**
 * Represents a symbolic value referenced by IR statements. Symbols carry width/type metadata,
 * constants store literal numbers from the source transactions.
 */
sealed interface IrValue {
    /** Reference to a registered symbol (field, operand, emit result, ...). */
    data class Symbol(val entry: SymbolEntry) : IrValue

    /** Numeric literal embedded directly in the transaction description. */
    data class Constant(val value: Number) : IrValue
}

/**
 * Metadata about a loop iterator that survived phase expansion. The iterator name is preserved to
 * make future scheduling passes easier to read.
 */
data class IrIterator(
    val name: String,
    val count: Int,
    val kind: LoopKind
)

/**
 * Sealed hierarchy of statements stored in IR blocks.
 */
sealed interface IrStatement

/**
 * Container for an ordered list of statements. Blocks are reused for phase bodies, loop bodies,
 * conditional branches and other nested regions.
 */
data class IrBlock(val statements: List<IrStatement>)

/**
 * Assignment without an explicit opcode (target := value).
 */
data class IrAssignment(
    val target: SymbolEntry,
    val value: IrValue
) : IrStatement

/**
 * Operation with an opcode and one or two operands. The opcode matches transactional operations.
 */
data class IrOperation(
    val opcode: OpCode,
    val target: SymbolEntry,
    val operands: List<IrValue>
) : IrStatement {
    init {
        require(operands.isNotEmpty()) { "IR operation must have at least one operand" }
        require(operands.size <= 2) { "IR operation supports up to two operands" }
    }
}

/**
 * Emit statement that represents spike generation.
 */
data class IrEmit(
    val emitId: Int,
    val target: SymbolEntry?
) : IrStatement

/**
 * Condition used by IR-level branching.
 */
data class IrCondition(
    val left: IrValue,
    val comparison: ComparisonOp,
    val right: IrValue
)

/**
 * Conditional statement with optional else-if branches and a trailing else block.
 */
data class IrConditional(
    val condition: IrCondition,
    val thenBlock: IrBlock,
    val elseIfBranches: List<IrConditionalBranch>,
    val elseBlock: IrBlock?
) : IrStatement

/**
 * Single else-if branch that belongs to an [IrConditional].
 */
data class IrConditionalBranch(
    val condition: IrCondition,
    val body: IrBlock
)

/**
 * For-loop that iterates over presynaptic/postsynaptic domains.
 */
data class IrLoop(
    val iterator: IrIterator,
    val body: IrBlock
) : IrStatement

/**
 * Phase-level container. Each phase owns a dedicated block.
 */
data class IrPhaseBlock(
    val phase: IrPhase,
    val body: IrBlock
)

/**
 * Lightweight note describing a future transformation. This is used to stage optimisation plans
 * without implementing them yet.
 */
data class IrTransformNote(
    val kind: IrTransformKind,
    val details: String? = null
)

/**
 * List of supported transformation stubs.
 */
enum class IrTransformKind {
    PIPELINE_SYNAPTIC,
    PARALLEL_SOMATIC,
    PARALLEL_EMISSION,
    PARALLEL_REFRACTORY,
    APPLY_PACKING
}
