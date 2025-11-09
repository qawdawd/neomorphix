package symbols

import ast.TransactionKind
import transaction.TxFieldType

/**
 * Identifies the high-level role of a symbol stored in the symbol table.
 */
enum class SymbolKind {
    FIELD,
    OPERAND
}

/**
 * Captures the origin category of a symbol. This allows later passes to differentiate between
 * transaction-owned fields, external references and synthesized values such as emit results.
 */
enum class SymbolOrigin {
    TRANSACTION_FIELD,
    TRANSACTION_OPERAND,
    EXTERNAL,
    EMIT_RESULT
}

/**
 * Entry stored inside the symbol table. Every entry keeps the original transaction (if applicable),
 * the associated type information and the bit width so later stages can reason about packing and
 * scheduling.
 */
data class SymbolEntry(
    val name: String,
    val type: TxFieldType?,
    val bitWidth: Int,
    val kind: SymbolKind,
    val origin: SymbolOrigin,
    val transactionId: String? = null,
    val transactionKind: TransactionKind? = null,
    val notes: String? = null
) {
    init {
        require(name.isNotBlank()) { "Symbol name must not be blank" }
        require(bitWidth > 0) { "Symbol bit width must be positive" }
    }
}
