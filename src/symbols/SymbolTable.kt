
package symbols

import ast.AstBlock
import ast.AstCondition
import ast.AstLoop
import ast.AstNode
import ast.AstOperation
import ast.AstPhaseBlock
import ast.TransactionKind
import transaction.TxCondition
import transaction.TxField
import transaction.TxOperand

/**
 * Central registry of symbols referenced in the transactional description. It keeps information
 * about every declared field as well as operands that appear inside expressions and conditions.
 */
class SymbolTable {

    /** Mapping of field names to their descriptors. */
    private val fieldSymbols = LinkedHashMap<String, SymbolEntry>()

    /** Mapping of operand identifiers (including externals and emit results) to descriptors. */
    private val operandSymbols = LinkedHashMap<String, SymbolEntry>()

    /** Quick lookup table that stores the computed bit width for every registered name. */
    private val widthTable = LinkedHashMap<String, Int>()

    private val fieldsByTransaction = LinkedHashMap<String, MutableList<String>>()
    private val operandsByTransaction = LinkedHashMap<String, MutableList<String>>()

    /**
     * Registers all fields belonging to the provided transaction.
     */
    fun registerTransactionFields(
        txId: String,
        kind: TransactionKind?,
        fields: Collection<TxField>
    ) {
        val index = fieldsByTransaction.getOrPut(txId) { mutableListOf() }
        for (field in fields) {
            val entry = SymbolEntry(
                name = field.name,
                type = field.type,
                bitWidth = field.bitWidth,
                kind = SymbolKind.FIELD,
                origin = SymbolOrigin.TRANSACTION_FIELD,
                transactionId = txId,
                transactionKind = kind
            )
            registerField(entry)
            index += entry.name
        }
    }

    /**
     * Registers operands discovered in the provided AST nodes. The transaction id is stored so that
     * later stages can query which transaction introduced a particular operand.
     */
    fun registerOperands(txId: String, kind: TransactionKind?, nodes: Collection<AstNode>) {
        val index = operandsByTransaction.getOrPut(txId) { mutableListOf() }
        nodes.forEach { node ->
            collectOperands(node, txId, kind) { name, entry ->
                val stored = operandSymbols[name]
                if (stored == null) {
                    operandSymbols[name] = entry
                    widthTable[name] = entry.bitWidth
                    index += name
                } else {
                    require(stored.bitWidth == entry.bitWidth) {
                        "Operand '$name' has conflicting bit widths (${stored.bitWidth} vs ${entry.bitWidth})"
                    }
                }
            }
        }
    }

    /** Returns the descriptor of a previously registered field. */
    fun resolveField(name: String): SymbolEntry? = fieldSymbols[name]

    /**
     * Returns the descriptor of a previously registered operand. If it matches a field it falls
     * back to the field registry.
     */
    fun resolveOperand(name: String): SymbolEntry? = operandSymbols[name] ?: fieldSymbols[name]

    /** Returns all field entries for the given transaction id. */
    fun fieldsForTransaction(txId: String): List<SymbolEntry> =
        fieldsByTransaction[txId]?.mapNotNull { fieldSymbols[it] } ?: emptyList()

    /** Returns all operand entries for the given transaction id. */
    fun operandsForTransaction(txId: String): List<SymbolEntry> =
        operandsByTransaction[txId]?.mapNotNull { operandSymbols[it] ?: fieldSymbols[it] } ?: emptyList()

    /** Returns the width recorded for the specified symbol, if present. */
    fun widthOf(name: String): Int? = widthTable[name]

    /** Returns descriptors of all registered fields in insertion order. */
    fun allFields(): List<SymbolEntry> = fieldSymbols.values.toList()

    /** Returns descriptors of all registered operands in insertion order. */
    fun allOperands(): List<SymbolEntry> = operandSymbols.values.toList()

    /** Performs basic validation ensuring that all AST references are registered in the table. */
    fun validate(ast: AstBlock) {
        val issues = mutableListOf<String>()
        traverse(ast) { node ->
            when (node) {
                is AstOperation -> {
                    node.target?.let { field ->
                        if (!fieldSymbols.containsKey(field.name)) {
                            issues += "Unknown target field '${field.name}' in operation ${node.kind}"
                        }
                    }
                    node.operands.forEach { operand ->
                        when (operand) {
                            is TxOperand.FieldRef -> ensureOperand(operand.field.name, issues)
                            is TxOperand.ExternalFieldRef -> ensureOperand(operand.name, issues)
                            is TxOperand.EmitReference -> ensureOperand(emitName(operand), issues)
                            is TxOperand.Constant -> {
                                // Constants do not require registration in the table.
                            }
                        }
                    }
                }

                is AstCondition -> {
                    checkConditionOperands(node.condition, issues)
                    node.elseIfBranches.forEach { branch ->
                        checkConditionOperands(branch.condition, issues)
                    }
                }

                is AstLoop -> {
                    // Loop metadata currently does not contribute additional symbols.
                }

                is AstPhaseBlock -> {
                    // Phase wrapper is handled by traversal, body has already been visited.
                }
            }
        }

        if (issues.isNotEmpty()) {
            val message = buildString {
                appendLine("SymbolTable validation failed:")
                issues.forEach { appendLine("- $it") }
            }
            throw IllegalStateException(message)
        }
    }

    /**
     * Dumps the contents of the symbol table in a readable form for debugging purposes.
     */
    fun dump(): String {
        val builder = StringBuilder()
        builder.appendLine("SymbolTable")

        builder.appendLine("  Fields:")
        if (fieldSymbols.isEmpty()) {
            builder.appendLine("    (none)")
        } else {
            fieldSymbols.values.forEach { entry ->
                builder.appendLine(
                    "    - ${entry.name}: type=${entry.type}, width=${entry.bitWidth}, tx=${entry.transactionId}, kind=${entry.transactionKind}"
                )
            }
        }

        builder.appendLine("  Operands:")
        if (operandSymbols.isEmpty()) {
            builder.appendLine("    (none)")
        } else {
            operandSymbols.values.forEach { entry ->
                builder.appendLine(
                    "    - ${entry.name}: type=${entry.type}, width=${entry.bitWidth}, origin=${entry.origin}, tx=${entry.transactionId}"
                )
            }
        }

        builder.appendLine("  Width table:")
        if (widthTable.isEmpty()) {
            builder.appendLine("    (none)")
        } else {
            widthTable.forEach { (name, width) ->
                builder.appendLine("    - $name -> $width")
            }
        }

        return builder.toString()
    }

    private fun registerField(entry: SymbolEntry) {
        val existing = fieldSymbols[entry.name]
        if (existing == null) {
            fieldSymbols[entry.name] = entry
            widthTable[entry.name] = entry.bitWidth
        } else {
            require(existing.bitWidth == entry.bitWidth) {
                "Field '${entry.name}' has conflicting bit widths (${existing.bitWidth} vs ${entry.bitWidth})"
            }
            require(existing.type == entry.type) {
                "Field '${entry.name}' has conflicting types (${existing.type} vs ${entry.type})"
            }
        }
    }

    private fun collectOperands(
        node: AstNode,
        txId: String,
        defaultKind: TransactionKind?,
        sink: (String, SymbolEntry) -> Unit
    ) {
        when (node) {
            is AstOperation -> {
                node.operands.forEach { operand ->
                    when (operand) {
                        is TxOperand.FieldRef -> {
                            val field = operand.field
                            sink(field.name, SymbolEntry(
                                name = field.name,
                                type = field.type,
                                bitWidth = field.bitWidth,
                                kind = SymbolKind.OPERAND,
                                origin = SymbolOrigin.TRANSACTION_OPERAND,
                                transactionId = txId,
                                transactionKind = defaultKind ?: node.origin
                            ))
                        }
                        is TxOperand.ExternalFieldRef -> sink(operand.name, SymbolEntry(
                            name = operand.name,
                            type = operand.type,
                            bitWidth = operand.bitWidth,
                            kind = SymbolKind.OPERAND,
                            origin = SymbolOrigin.EXTERNAL,
                            transactionId = txId,
                            transactionKind = defaultKind ?: node.origin
                        ))
                        is TxOperand.EmitReference -> {
                            val target = operand.targetField
                            val width = target?.bitWidth ?: 1
                            sink(
                                emitName(operand),
                                SymbolEntry(
                                    name = emitName(operand),
                                    type = target?.type,
                                    bitWidth = width,
                                    kind = SymbolKind.OPERAND,
                                    origin = SymbolOrigin.EMIT_RESULT,
                                    transactionId = txId,
                                    transactionKind = defaultKind ?: node.origin
                                )
                            )
                        }
                        is TxOperand.Constant -> {
                            // Constants do not have symbolic names.
                        }
                    }
                }
            }

            is AstCondition -> {
                collectCondition(node.condition, txId, defaultKind, sink)
                node.thenBlock.statements.forEach { collectOperands(it, txId, defaultKind, sink) }
                node.elseIfBranches.forEach { branch ->
                    collectCondition(branch.condition, txId, defaultKind, sink)
                    branch.body.statements.forEach { collectOperands(it, txId, defaultKind, sink) }
                }
                node.elseBlock?.statements?.forEach { collectOperands(it, txId, defaultKind, sink) }
            }

            is AstLoop -> node.body.statements.forEach { collectOperands(it, txId, defaultKind, sink) }

            is AstPhaseBlock -> node.body.statements.forEach { collectOperands(it, txId, defaultKind, sink) }
        }
    }

    private fun collectCondition(
        condition: TxCondition,
        txId: String,
        defaultKind: TransactionKind?,
        sink: (String, SymbolEntry) -> Unit
    ) {
        listOf(condition.left, condition.right).forEach { operand ->
            when (operand) {
                is TxOperand.FieldRef -> {
                    val field = operand.field
                    sink(field.name, SymbolEntry(
                        name = field.name,
                        type = field.type,
                        bitWidth = field.bitWidth,
                        kind = SymbolKind.OPERAND,
                        origin = SymbolOrigin.TRANSACTION_OPERAND,
                        transactionId = txId,
                        transactionKind = defaultKind
                    ))
                }
                is TxOperand.ExternalFieldRef -> sink(operand.name, SymbolEntry(
                    name = operand.name,
                    type = operand.type,
                    bitWidth = operand.bitWidth,
                    kind = SymbolKind.OPERAND,
                    origin = SymbolOrigin.EXTERNAL,
                    transactionId = txId,
                    transactionKind = defaultKind
                ))
                is TxOperand.EmitReference -> {
                    val target = operand.targetField
                    val width = target?.bitWidth ?: 1
                    sink(emitName(operand), SymbolEntry(
                        name = emitName(operand),
                        type = target?.type,
                        bitWidth = width,
                        kind = SymbolKind.OPERAND,
                        origin = SymbolOrigin.EMIT_RESULT,
                        transactionId = txId,
                        transactionKind = defaultKind
                    ))
                }
                is TxOperand.Constant -> {
                    // Constants are ignored.
                }
            }
        }
    }

    private fun ensureOperand(name: String, issues: MutableList<String>) {
        if (!operandSymbols.containsKey(name) && !fieldSymbols.containsKey(name)) {
            issues += "Unknown operand '$name' referenced in AST"
        }
    }

    private fun checkConditionOperands(condition: TxCondition, issues: MutableList<String>) {
        listOf(condition.left, condition.right).forEach { operand ->
            when (operand) {
                is TxOperand.FieldRef -> ensureOperand(operand.field.name, issues)
                is TxOperand.ExternalFieldRef -> ensureOperand(operand.name, issues)
                is TxOperand.EmitReference -> ensureOperand(emitName(operand), issues)
                is TxOperand.Constant -> {
                    // Constants are valid without registration.
                }
            }
        }
    }

    private fun emitName(reference: TxOperand.EmitReference): String = "emit#${reference.emitId}"

    private fun traverse(block: AstBlock, action: (AstNode) -> Unit) {
        block.statements.forEach { node ->
            traverse(node, action)
        }
    }

    private fun traverse(node: AstNode, action: (AstNode) -> Unit) {
        action(node)
        when (node) {
            is AstOperation -> {
                // Leaf node, nothing else to traverse.
            }
            is AstCondition -> {
                node.thenBlock.statements.forEach { traverse(it, action) }
                node.elseIfBranches.forEach { branch ->
                    branch.body.statements.forEach { traverse(it, action) }
                }
                node.elseBlock?.statements?.forEach { traverse(it, action) }
            }
            is AstLoop -> node.body.statements.forEach { traverse(it, action) }
            is AstPhaseBlock -> node.body.statements.forEach { traverse(it, action) }
        }
    }
}
