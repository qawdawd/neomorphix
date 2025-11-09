package ast

import transaction.OpCode
import transaction.TxCondition
import transaction.TxField
import transaction.TxFieldType
import transaction.TxOperand
import transaction.TxStatement

/**
 * Enumerates the phases that appear during transactional synthesis.
 */
enum class AstPhase {
    SYNAPTIC,
    SOMATIC,
    EMISSION,
    REFRACTORY
}

/**
 * Identifies the origin transaction that introduced a particular AST node.
 */
enum class TransactionKind {
    SPIKE,
    NEURON
}

/**
 * Base interface for every AST node. Each node remembers which transaction produced it so that
 * phase separation and loop expansion can be applied selectively.
 */
sealed interface AstNode {
    val origin: TransactionKind
}

/**
 * Block of AST nodes. Blocks are reused for root containers and nested regions (if/else bodies,
 * loop bodies, phase bodies). The origin is optional because a block may aggregate nodes from
 * different transactions (for example, the root block created by the builder).
 */
data class AstBlock(
    val origin: TransactionKind?,
    val statements: List<AstNode>
)

/**
 * Describes an operation in the AST. The node keeps the original transaction statement for
 * traceability while exposing a simplified classification.
 */
data class AstOperation(
    override val origin: TransactionKind,
    val kind: OperationKind,
    val target: TxField?,
    val operands: List<TxOperand>,
    val opcode: OpCode?,
    val statement: TxStatement
) : AstNode

/**
 * Supported operation kinds in the AST.
 */
enum class OperationKind {
    ASSIGNMENT,
    BINARY,
    UNARY,
    EMIT
}

/**
 * Conditional statement node with optional chained else-if branches and a trailing else block.
 */
data class AstCondition(
    override val origin: TransactionKind,
    val condition: TxCondition,
    val thenBlock: AstBlock,
    val elseIfBranches: List<ElseIfBranch>,
    val elseBlock: AstBlock?
) : AstNode {
    /** Description of a single else-if branch. */
    data class ElseIfBranch(
        val condition: TxCondition,
        val body: AstBlock
    )
}

/**
 * Metadata about a loop iterator used during phase expansion.
 */
data class LoopDescriptor(
    val iterator: String,
    val count: Int,
    val kind: LoopKind,
    val description: String
)

/**
 * Kinds of loops supported by the builder. The semantic meaning of the iterator depends on the
 * phase being processed.
 */
enum class LoopKind {
    PRESYNAPTIC,
    POSTSYNAPTIC
}

/**
 * Explicit for-loop node with a descriptor and a body block.
 */
data class AstLoop(
    override val origin: TransactionKind,
    val descriptor: LoopDescriptor,
    val body: AstBlock
) : AstNode

/**
 * Phase wrapper used after separating spike and neuron sections into dedicated regions.
 */
data class AstPhaseBlock(
    override val origin: TransactionKind,
    val phase: AstPhase,
    val body: AstBlock
) : AstNode
