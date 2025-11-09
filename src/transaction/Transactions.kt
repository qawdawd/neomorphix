package transaction

import arch.SnnArch

/**
 * Base class shared between spike and neuron transactions. It manages field registration, statement
 * storage and AST export.
 */
abstract class BaseTransaction(
    val name: String,
    private val forbiddenAssignmentTypes: Set<TxFieldType>
) {
    private val fields = LinkedHashMap<String, TxField>()
    protected val statements = mutableListOf<TxStatement>()
    private var emitCounter = 0

    /** Adds a field to the transaction. */
    fun addField(name: String, bitWidth: Int, type: TxFieldType): TxField {
        validateFieldType(type)
        require(!fields.containsKey(name)) { "Field '$name' is already defined" }
        val field = TxField(name, bitWidth, type)
        fields[name] = field
        return field
    }

    /** Returns a field by name or throws if it does not exist. */
    fun getField(name: String): TxField = fields[name]
        ?: error("Field '$name' is not declared in transaction '$name'")

    /** Checks if a field with the given name exists. */
    fun hasField(name: String): Boolean = fields.containsKey(name)

    /** Exports transaction contents into an immutable AST representation. */
    fun toAst(): TransactionAst {
        val frozenStatements = freezeStatements(statements)
        val rootBlock = TxStatement.Block(TxBlock(frozenStatements))
        return TransactionAst(name, fields.values.toList(), rootBlock)
    }

    /** Иерархичный человеко-читаемый вывод AST (с отступами). */
    fun dumpTree(): String {
        val ast = toAst()
        val sb = StringBuilder()

        // Заголовок и поля
        sb.appendLine("Transaction: $name")
        if (ast.fields.isEmpty()) {
            sb.appendLine("  Fields: (none)")
        } else {
            sb.appendLine("  Fields:")
            ast.fields.forEach { f ->
                sb.appendLine("    - ${f.name}: width=${f.bitWidth}, type=${f.type}")
            }
        }

        // Тело
        sb.appendLine("  Body:")
        renderStmt(ast.root, indent = 2, out = sb)
        return sb.toString()
    }

    // ====== Вспомогательные принты ======
    private fun renderStmt(stmt: TxStatement, indent: Int, out: StringBuilder) {
        val pad = "  ".repeat(indent)
        when (stmt) {
            is TxStatement.Block -> {
                out.appendLine("${pad}{")
                stmt.body.statements.forEach { s -> renderStmt(s, indent + 1, out) }
                out.appendLine("${pad}}")
            }

            is TxStatement.Assignment -> {
                out.appendLine("${pad}${stmt.target.name} := ${renderOperand(stmt.value)}")
            }

            is TxStatement.BinaryOp -> {
                val op = when (stmt.opcode) {
                    OpCode.ADD -> "+"
                    OpCode.SUB -> "-"
                    OpCode.MUL -> "*"
                    OpCode.POW -> "^"
                    OpCode.LOGICAL_OR -> "||"
                    OpCode.LOGICAL_AND -> "&&"
                    OpCode.LOGICAL_NOT -> error("LOGICAL_NOT is unary")
                }
                out.appendLine("${pad}${stmt.target.name} := ${renderOperand(stmt.left)} $op ${renderOperand(stmt.right)}")
            }

            is TxStatement.UnaryOp -> {
                val op = when (stmt.opcode) {
                    OpCode.LOGICAL_NOT -> "!"
                    else -> stmt.opcode.toString()
                }
                out.appendLine("${pad}${stmt.target.name} := $op${renderOperand(stmt.operand)}")
            }

            is TxStatement.Emit -> {
                val arg = stmt.target?.name?.let { "($it)" } ?: ""
                out.appendLine("${pad}emit#${stmt.emitId}$arg")
            }

            is TxStatement.IfBlock -> {
                out.appendLine("${pad}if (${renderCond(stmt.condition)}) {")
                stmt.body.statements.forEach { s -> renderStmt(s, indent + 1, out) }
                out.appendLine("${pad}}")

                stmt.elseIfBlocks.forEach { ei ->
                    out.appendLine("${pad}else if (${renderCond(ei.condition)}) {")
                    ei.body.statements.forEach { s -> renderStmt(s, indent + 1, out) }
                    out.appendLine("${pad}}")
                }

                stmt.elseBlock?.let { eb ->
                    out.appendLine("${pad}else {")
                    eb.body.statements.forEach { s -> renderStmt(s, indent + 1, out) }
                    out.appendLine("${pad}}")
                }
            }

            // Эти два — уже покрыты в IfBlock, но на всякий случай:
            is TxStatement.ElseIfBlock -> {
                out.appendLine("${pad}else if (${renderCond(stmt.condition)}) {")
                stmt.body.statements.forEach { s -> renderStmt(s, indent + 1, out) }
                out.appendLine("${pad}}")
            }
            is TxStatement.ElseBlock -> {
                out.appendLine("${pad}else {")
                stmt.body.statements.forEach { s -> renderStmt(s, indent + 1, out) }
                out.appendLine("${pad}}")
            }

            is TxStatement.MutableIfBlock -> {
                // До freeze() сюда обычно не попадём, но на всякий случай:
                out.appendLine("${pad}if <mutable> (${renderCond(stmt.condition)}) { ... }")
            }
        }
    }

    private fun renderCond(c: TxCondition): String {
        val cmp = when (c.comparison) {
            ComparisonOp.EQ  -> "=="
            ComparisonOp.NEQ -> "!="
            ComparisonOp.LT  -> "<"
            ComparisonOp.LTE -> "<="
            ComparisonOp.GT  -> ">"
            ComparisonOp.GTE -> ">="
        }
        return "${renderOperand(c.left)} $cmp ${renderOperand(c.right)}"
    }

    private fun renderOperand(op: TxOperand): String = when (op) {
        is TxOperand.Constant -> op.value.toString()
        is TxOperand.FieldRef -> op.field.name
        is TxOperand.ExternalFieldRef -> "${op.name}@${op.type}:${op.bitWidth}"
        is TxOperand.EmitReference -> "emit#${op.emitId}${op.targetField?.let { "(${it.name})" } ?: ""}"
    }


    internal fun requireField(name: String): TxField = getField(name)

    internal fun assertAssignable(field: TxField) {
        require(field.type !in forbiddenAssignmentTypes) {
            "Assignments to fields of type ${field.type} are not allowed in transaction '$name'"
        }
    }

    internal fun nextEmitId(): Int {
        emitCounter += 1
        return emitCounter
    }


    /** Точка расширения: потомок может вернуть специализированный билдер. */
    protected open fun newBuilder(target: MutableList<TxStatement>): TxBuilder =
        TxBuilder(this, target)

    //    protected open fun createBuilder(target: MutableList<TxStatement>): TxBuilder = TxBuilder(this, target)
    internal fun createBuilder(target: MutableList<TxStatement>): TxBuilder =
        newBuilder(target)

    /** Provides a builder positioned at the root block. */
    fun builder(): TxBuilder = createBuilder(statements)

    /** Executes the provided builder lambda against the root block. */
    fun build(block: TxBuilder.() -> Unit) {
        val b = builder()
        b.block()
    }

    internal fun freezeStatements(source: List<TxStatement>): List<TxStatement> = source.map { statement ->
        when (statement) {
            is TxStatement.Assignment,
            is TxStatement.BinaryOp,
            is TxStatement.UnaryOp,
            is TxStatement.Emit -> statement

            is TxStatement.Block -> TxStatement.Block(
                TxBlock(freezeStatements(statement.body.statements))
            )

            is TxStatement.IfBlock -> TxStatement.IfBlock(
                statement.condition,
                TxBlock(freezeStatements(statement.body.statements)),
                statement.elseIfBlocks.map { elseIf ->
                    TxStatement.ElseIfBlock(
                        elseIf.condition,
                        TxBlock(freezeStatements(elseIf.body.statements))
                    )
                },
                statement.elseBlock?.let { elseBlock ->
                    TxStatement.ElseBlock(
                        TxBlock(freezeStatements(elseBlock.body.statements))
                    )
                }
            )

            is TxStatement.ElseIfBlock -> TxStatement.ElseIfBlock(
                statement.condition,
                TxBlock(freezeStatements(statement.body.statements))
            )

            is TxStatement.ElseBlock -> TxStatement.ElseBlock(
                TxBlock(freezeStatements(statement.body.statements))
            )

            is TxStatement.MutableIfBlock -> statement.toImmutable(this)
        }
    }

    internal open fun validateFieldType(type: TxFieldType) {}

    private fun TxStatement.MutableIfBlock.toImmutable(owner: BaseTransaction): TxStatement.IfBlock {
        val immutableBody = TxBlock(owner.freezeStatements(bodyStatements))
        val immutableElseIfs = elseIfBlocks.map { mutableElseIf ->
            TxStatement.ElseIfBlock(
                mutableElseIf.condition,
                TxBlock(owner.freezeStatements(mutableElseIf.bodyStatements))
            )
        }
        val immutableElse = elseBlock?.let { mutableElse ->
            TxStatement.ElseBlock(
                TxBlock(owner.freezeStatements(mutableElse.bodyStatements))
            )
        }
        return TxStatement.IfBlock(condition, immutableBody, immutableElseIfs, immutableElse)
    }
}

/**
 * Builder used to assemble statements inside a particular block. Instances are short-lived and only
 * operate on the block provided by the owning transaction.
 */
open class TxBuilder internal constructor(
    private val transaction: BaseTransaction,
    protected val statements: MutableList<TxStatement>
) {
    /** Creates a constant operand. */
    fun const(value: Number): TxOperand.Constant = TxOperand.Constant(value)

    /** Returns an operand referencing a field declared in the transaction. */
    fun field(name: String): TxOperand.FieldRef = TxOperand.FieldRef(transaction.requireField(name))

    /**
     * Creates an operand referencing an external field defined outside the transaction.
     */
    fun externalField(name: String, type: TxFieldType, bitWidth: Int): TxOperand.ExternalFieldRef =
        TxOperand.ExternalFieldRef(name, type, bitWidth)

    /** Adds a direct assignment statement. */
    fun assign(targetField: String, value: TxOperand) {
        val target = transaction.requireField(targetField)
        transaction.assertAssignable(target)
        statements += TxStatement.Assignment(target, value)
    }

    /** Adds a direct assignment using a constant literal. */
    fun assign(targetField: String, value: Number) = assign(targetField, const(value))

    /** Adds a binary addition statement. */
    fun add(targetField: String, left: TxOperand, right: TxOperand) {
        addBinary(targetField, OpCode.ADD, left, right)
    }

    /** Adds a binary subtraction statement. */
    fun sub(targetField: String, left: TxOperand, right: TxOperand) {
        addBinary(targetField, OpCode.SUB, left, right)
    }

    /** Adds a binary multiplication statement. */
    fun mul(targetField: String, left: TxOperand, right: TxOperand) {
        addBinary(targetField, OpCode.MUL, left, right)
    }

    /** Adds a power operation statement. */
    fun pow(targetField: String, base: TxOperand, exponent: TxOperand) {
        addBinary(targetField, OpCode.POW, base, exponent)
    }

    /** Adds a logical OR statement. */
    fun logicalOr(targetField: String, left: TxOperand, right: TxOperand) {
        addBinary(targetField, OpCode.LOGICAL_OR, left, right)
    }

    /** Adds a logical AND statement. */
    fun logicalAnd(targetField: String, left: TxOperand, right: TxOperand) {
        addBinary(targetField, OpCode.LOGICAL_AND, left, right)
    }

    /** Adds a logical NOT statement. */
    fun logicalNot(targetField: String, operand: TxOperand) {
        val target = transaction.requireField(targetField)
        transaction.assertAssignable(target)
        statements += TxStatement.UnaryOp(target, OpCode.LOGICAL_NOT, operand)
    }

    /**
     * Adds an if-statement and returns a conditional builder to continue with else-if or else
     * branches.
     */
    fun ifCondition(
        left: TxOperand,
        comparison: ComparisonOp,
        right: TxOperand,
        bodyBuilder: TxBuilder.() -> Unit
    ): TxConditionalBuilder {
        val condition = TxCondition(left, comparison, right)
        val mutableIf = TxStatement.MutableIfBlock(condition)
        statements += mutableIf
        val innerBuilder = transaction.createBuilder(mutableIf.bodyStatements)
        innerBuilder.bodyBuilder()
        return TxConditionalBuilder(transaction, mutableIf)
    }

    private fun addBinary(targetField: String, opCode: OpCode, left: TxOperand, right: TxOperand) {
        val target = transaction.requireField(targetField)
        transaction.assertAssignable(target)
        statements += TxStatement.BinaryOp(target, opCode, left, right)
    }

    /** Emits a spike (NeuronTx only). Returns an operand referencing the emit result. */
    fun emit(targetField: String? = null): TxOperand.EmitReference {
        val neuron = transaction as? NeuronTx
            ?: error("emit() is only allowed inside NeuronTx.build { ... }")
        val target = targetField?.let { neuron.requireField(it) }
        target?.let { neuron.assertAssignable(it) }
        return neuron.emit(target, statements)
    }
}

/**
 * Builder returned by [TxBuilder.ifCondition] that allows registering additional else-if and else
 * branches.
 */
class TxConditionalBuilder internal constructor(
    private val transaction: BaseTransaction,
    private val mutableIf: TxStatement.MutableIfBlock
) {
    /** Adds an else-if branch to the surrounding if-block. */
    fun elseIf(
        left: TxOperand,
        comparison: ComparisonOp,
        right: TxOperand,
        bodyBuilder: TxBuilder.() -> Unit
    ): TxConditionalBuilder {
        val condition = TxCondition(left, comparison, right)
        val elseIf = TxStatement.MutableElseIfBlock(condition)
        mutableIf.elseIfBlocks += elseIf
        val builder = transaction.createBuilder(elseIf.bodyStatements)
        builder.bodyBuilder()
        return this
    }

    /** Adds a trailing else branch to the surrounding if-block. */
    fun elseBlock(bodyBuilder: TxBuilder.() -> Unit) {
        val mutableElse = TxStatement.MutableElseBlock()
        mutableIf.elseBlock = mutableElse
        val builder = transaction.createBuilder(mutableElse.bodyStatements)
        builder.bodyBuilder()
    }
}

/** Spike transaction description with restrictions specific to synaptic processing. */
class SpikeTx(name: String) : BaseTransaction(
    name = name,
    forbiddenAssignmentTypes = setOf(TxFieldType.SYNAPTIC_PARAM)
) {
    override fun validateFieldType(type: TxFieldType) {
        require(
            type == TxFieldType.SYNAPTIC_PARAM ||
//                    type == TxFieldType.NEURON ||
                    type == TxFieldType.DELAY ||
                    type == TxFieldType.LOCAL ||
                    type == TxFieldType.DYNAMIC
        ) { "Spike transaction fields must be SYNAPTIC_PARAM, NEURON, DELAY or LOCAL" }
    }
}

/** Neuron transaction description capable of emitting spikes and importing static parameters. */
class NeuronTx(name: String) : BaseTransaction(
    name = name,
    forbiddenAssignmentTypes = emptySet()
) {
    /** Adds a static parameter field using the descriptor stored inside the architecture model. */
    fun addStaticFieldFromArch(arch: SnnArch, paramName: String, fieldName: String = paramName): TxField {
        val descriptor = arch.getStaticParameter(paramName)
            ?: error("Static parameter '$paramName' is not defined in the architecture")
        return addField(fieldName, descriptor.bitWidth, TxFieldType.STATIC)
    }

    override fun validateFieldType(type: TxFieldType) {
        require(
            type == TxFieldType.DYNAMIC ||
                    type == TxFieldType.STATIC ||
                    type == TxFieldType.LOCAL
        ) { "Neuron transaction fields must be DYNAMIC, STATIC or LOCAL" }
    }

    //    override fun createBuilder(target: MutableList<TxStatement>): TxBuilder =
    //        NeuronTxBuilder(this, target)
    override fun newBuilder(target: MutableList<TxStatement>): TxBuilder =
        NeuronTxBuilder(this, target)

//    fun build(block: NeuronTxBuilder.() -> Unit) {
//        val b = newBuilder(statements) as NeuronTxBuilder
//        b.block()
//    }

    fun withNeuron(block: NeuronTxBuilder.() -> Unit) {
        val b = newBuilder(statements) as NeuronTxBuilder
        b.block()
    }


    /** Внутренний примитив эмиссии, вызывается из билдера. */
    internal fun emit(target: TxField?, sink: MutableList<TxStatement>): TxOperand.EmitReference {
        val id = nextEmitId()
        sink += TxStatement.Emit(id, target)
        return TxOperand.EmitReference(id, target)
    }
}

/**
 * Specialized builder for neuron transactions that exposes the emit operation.
 */
class NeuronTxBuilder internal constructor(
    private val neuronTx: NeuronTx,
    statements: MutableList<TxStatement>
) : TxBuilder(neuronTx, statements) {
    /**
     * Adds an emit statement and returns an operand that represents the emit result for use in
     * expressions.
     */
//    fun emit(targetField: String? = null): TxOperand.EmitReference {
//        val target = targetField?.let { neuronTx.requireField(it) }
//        target?.let { neuronTx.assertAssignable(it) }
//        return neuronTx.emit(target, statements)
//    }
}
