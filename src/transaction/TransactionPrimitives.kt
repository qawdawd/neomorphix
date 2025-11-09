package transaction

/**
 * Enumeration of available transaction field roles. The LOCAL type is used for temporary values
 * that exist only within the lifetime of the transaction evaluation.
 */
enum class TxFieldType {
    SYNAPTIC_PARAM,
    NEURON,
    DELAY,
    STATIC,
    DYNAMIC,
    LOCAL
}

/**
 * Description of a single field within a transaction.
 */
data class TxField(
    val name: String,
    val bitWidth: Int,
    val type: TxFieldType
) {
    init {
        require(name.isNotBlank()) { "Field name must not be blank" }
        require(bitWidth > 0) { "Field width must be positive" }
    }
}

/**
 * Operand that can appear within transaction expressions.
 */
sealed class TxOperand {
    /** Constant literal operand. */
    data class Constant(val value: Number) : TxOperand()

    /** Reference to a field declared within the same transaction. */
    data class FieldRef(val field: TxField) : TxOperand()

    /** Reference to a field defined in another transaction or architectural scope. */
    data class ExternalFieldRef(
        val name: String,
        val type: TxFieldType,
        val bitWidth: Int
    ) : TxOperand() {
        init {
            require(name.isNotBlank()) { "External field name must not be blank" }
            require(bitWidth > 0) { "External field width must be positive" }
        }
    }

    /** Reference to the result of an emit operation. */
    data class EmitReference(val emitId: Int, val targetField: TxField?) : TxOperand()
}

/**
 * Arithmetic and logical operation kinds supported inside transactions.
 */
enum class OpCode {
    ADD,
    SUB,
    MUL,
    POW,
    LOGICAL_OR,
    LOGICAL_AND,
    LOGICAL_NOT
}

/**
 * Comparison operation used to form conditional statements.
 */
enum class ComparisonOp {
    EQ,
    NEQ,
    LT,
    LTE,
    GT,
    GTE
}

/**
 * Condition built from two operands and a comparison operator.
 */
data class TxCondition(
    val left: TxOperand,
    val comparison: ComparisonOp,
    val right: TxOperand
)

/**
 * Simple container for a list of statements. This wrapper is used by blocks, including roots of
 * transaction AST structures.
 */
data class TxBlock(val statements: List<TxStatement>)

/**
 * Sealed hierarchy describing all supported transaction statements. During construction mutable
 * variants are used internally and converted to immutable counterparts when exporting the AST.
 */
sealed class TxStatement {
    /** Direct assignment of an operand into the target field. */
    data class Assignment(val target: TxField, val value: TxOperand) : TxStatement()

    /** Binary operation with a dedicated opcode and two input operands. */
    data class BinaryOp(
        val target: TxField,
        val opcode: OpCode,
        val left: TxOperand,
        val right: TxOperand
    ) : TxStatement()

    /** Unary operation such as logical negation. */
    data class UnaryOp(
        val target: TxField,
        val opcode: OpCode,
        val operand: TxOperand
    ) : TxStatement()

    /** Emit operation that can optionally forward a field as an argument. */
    data class Emit(val emitId: Int, val target: TxField?) : TxStatement()

    /** Explicit block wrapper used when a standalone nested block needs to be referenced. */
    data class Block(val body: TxBlock) : TxStatement()

    /** Immutable representation of the "if" statement. */
    data class IfBlock(
        val condition: TxCondition,
        val body: TxBlock,
        val elseIfBlocks: List<ElseIfBlock>,
        val elseBlock: ElseBlock?
    ) : TxStatement()

    /** Immutable representation of the "else-if" section. */
    data class ElseIfBlock(
        val condition: TxCondition,
        val body: TxBlock
    ): TxStatement()

    /** Immutable representation of the trailing "else" section. */
    data class ElseBlock(
        val body: TxBlock
    ): TxStatement()

    /**
     * Mutable builder representation of the "if" statement. This is internal to the transaction
     * module and is replaced with [IfBlock] during AST export.
     */
    internal data class MutableIfBlock(
        val condition: TxCondition,
        val bodyStatements: MutableList<TxStatement> = mutableListOf(),
        val elseIfBlocks: MutableList<MutableElseIfBlock> = mutableListOf(),
        var elseBlock: MutableElseBlock? = null
    ) : TxStatement()

    /** Mutable builder representation of the "else-if" section. */
    internal data class MutableElseIfBlock(
        val condition: TxCondition,
        val bodyStatements: MutableList<TxStatement> = mutableListOf()
    )

    /** Mutable builder representation of the "else" section. */
    internal data class MutableElseBlock(
        val bodyStatements: MutableList<TxStatement> = mutableListOf()
    )
}

/**
 * Exported view of a transaction consisting of declared fields and the root block of statements.
 */
data class TransactionAst(
    val name: String,
    val fields: List<TxField>,
    val root: TxStatement.Block
)
