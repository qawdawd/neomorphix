package neuromorphix

// ===== IR: фазы/опкоды/операнды =====
enum class TxPhase { SYNAPTIC, NEURONAL }

enum class TxOpcode {
    ADD, SUB, SHL, SHR,
    CMP_GE,        // если используешь старую связку CMP+EMIT
    EMIT,          // "
    EMIT_IF        // новый «атомарный» emit с условием и опц. сбросом
}

// Пространства имён полей
const val SPACE_SPIKE = "SPIKE"
const val SPACE_NEURON = "NEURON"

// Операнд IR
data class TxOperand(
    val isImm: Boolean,
    val name: String? = null,          // имя поля, если не imm
    val imm: Int? = null,              // значение, если imm
    val space: String? = null          // SPIKE / NEURON (для полей)
)

// Операция IR
data class TxOp(
    val phase: TxPhase,
    val opcode: TxOpcode,
    val dst: String,                   // имя поля/выхода
    val dstSpace: String? = null,      // куда пишем (SPIKE/NEURON/лог. "spike_out")
    val a: TxOperand? = null,
    val b: TxOperand? = null,
    // для EMIT_IF:
    val cmp: NeuronCmp? = null,        // GT/LT/GE/LE
    val resetDst: String? = null,      // поле сброса (или null)
    val resetImm: Int? = null          // значение сброса (или null)
)

// Целостный IR «на такт»
data class TxIR(val ops: MutableList<TxOp> = mutableListOf())


// ===== Вспомогательные адаптеры к IR-операндам =====
private fun SpikeOperand.toTxOperand(): TxOperand =
    if (isImm) TxOperand(true, null, imm, null)
    else TxOperand(false, fieldName, null, if (isExternal) (extSpace ?: SPACE_SPIKE) else SPACE_SPIKE)

private fun NeuronOperand.toTxOperand(): TxOperand =
    if (isImm) TxOperand(true, null, imm, null)
    else TxOperand(false, fieldName, null, SPACE_NEURON)

// ===== Построение IR из SpikeTx =====
fun SpikeTx.toIR(): List<TxOp> {
    val out = mutableListOf<TxOp>()
    for (op in listOps()) {
        val opcode = when (op.opcode) {
            SpikeOpCode.ADD -> TxOpcode.ADD
            SpikeOpCode.SUB -> TxOpcode.SUB
            SpikeOpCode.SHL -> TxOpcode.SHL
            SpikeOpCode.SHR -> TxOpcode.SHR
        }
        val dstSpace = if (op.dstIsExternal) (op.dstExtSpace ?: SPACE_NEURON) else SPACE_SPIKE
        out += TxOp(
            phase = TxPhase.SYNAPTIC,
            opcode = opcode,
            dst = op.dst,
            dstSpace = dstSpace,
            a = op.a.toTxOperand(),
            b = op.b.toTxOperand()
        )
    }
    return out
}

// ===== Построение IR из NeuronTx =====
fun NeuronTx.toIR(): List<TxOp> {
    val out = mutableListOf<TxOp>()
    for (op in listOps()) {
        when (op.opcode) {
            NeuronOpCode.ADD, NeuronOpCode.SUB, NeuronOpCode.SHL, NeuronOpCode.SHR -> {
                val opcode = when (op.opcode) {
                    NeuronOpCode.ADD -> TxOpcode.ADD
                    NeuronOpCode.SUB -> TxOpcode.SUB
                    NeuronOpCode.SHL -> TxOpcode.SHL
                    NeuronOpCode.SHR -> TxOpcode.SHR
                    else -> error("unreachable")
                }
                out += TxOp(
                    phase = TxPhase.NEURONAL,
                    opcode = opcode,
                    dst = op.dst,
                    dstSpace = SPACE_NEURON,
                    a = op.a.toTxOperand(),
                    b = op.b.toTxOperand()
                )
            }

            NeuronOpCode.CMP_GE -> {
                out += TxOp(
                    phase = TxPhase.NEURONAL,
                    opcode = TxOpcode.CMP_GE,
                    dst = op.dst,                 // имя предикатного поля
                    dstSpace = SPACE_NEURON,
                    a = op.a.toTxOperand(),
                    b = op.b.toTxOperand()
                )
            }

            NeuronOpCode.EMIT -> {
                out += TxOp(
                    phase = TxPhase.NEURONAL,
                    opcode = TxOpcode.EMIT,
                    dst = op.dst,                 // "spike_out" или поле для сброса
                    dstSpace = SPACE_NEURON,
                    a = op.a.toTxOperand(),       // предикат-поле
                    b = op.b.toTxOperand()        // imm(0) или imm(reset)
                )
            }

            NeuronOpCode.EMIT_IF -> {
                out += TxOp(
                    phase = TxPhase.NEURONAL,
                    opcode = TxOpcode.EMIT_IF,
                    dst = op.dst,                 // логическое имя выхода (обычно "spike_out")
                    dstSpace = SPACE_NEURON,
                    a = op.a.toTxOperand(),       // левый операнд сравнения
                    b = op.b.toTxOperand(),       // правый операнд сравнения
                    cmp = op.cmp,                 // GT/LT/GE/LE
                    resetDst = op.resetDst,
                    resetImm = op.resetImm
                )
            }
        }
    }
    return out
}

// ===== Сборка общего IR =====
fun buildTxIR(spike: SpikeTx, neuron: NeuronTx): TxIR {
    val ir = TxIR()
    ir.ops += spike.toIR()
    ir.ops += neuron.toIR()
    return ir
}

// Текстовый дамп (читабельно для отладки/репортов)
fun TxIR.dump() {
    println("==== TxIR dump ====")
    for ((i, op) in ops.withIndex()) {
        val aStr = op.a?.let { if (it.isImm) "#${it.imm}" else "${it.space}.${it.name}" } ?: "-"
        val bStr = op.b?.let { if (it.isImm) "#${it.imm}" else "${it.space}.${it.name}" } ?: "-"
        when (op.opcode) {
            TxOpcode.EMIT_IF -> {
                val cmpStr = when (op.cmp) {
                    NeuronCmp.GT -> ">"
                    NeuronCmp.LT -> "<"
                    NeuronCmp.GE -> ">="
                    NeuronCmp.LE -> "<="
                    else -> "?"
                }
                val rst = if (op.resetDst != null && op.resetImm != null)
                    " reset ${op.resetDst}:=${op.resetImm}" else ""
                println("[%02d] %-9s %-8s dst=%s.%s  if (%s %s %s)%s"
                    .format(i, op.phase, op.opcode, op.dstSpace, op.dst, aStr, cmpStr, bStr, rst))
            }
            else -> {
                println("[%02d] %-9s %-9s dst=%s.%s  a=%s  b=%s"
                    .format(i, op.phase, op.opcode, op.dstSpace, op.dst, aStr, bStr))
            }
        }
    }
}

// JSON (минимально, через org.json уже у тебя подключён)
fun TxIR.toJson(): org.json.JSONObject {
    val root = org.json.JSONObject()
    val arr = org.json.JSONArray()
    for (op in ops) {
        val jo = org.json.JSONObject()
        jo.put("phase", op.phase.name)
        jo.put("opcode", op.opcode.name)
        jo.put("dst", op.dst)
        jo.put("dstSpace", op.dstSpace)

        fun enc(o: TxOperand?): org.json.JSONObject? {
            if (o == null) return null
            val x = org.json.JSONObject()
            x.put("isImm", o.isImm)
            if (o.isImm) x.put("imm", o.imm) else {
                x.put("name", o.name)
                x.put("space", o.space)
            }
            return x
        }

        enc(op.a)?.let { jo.put("a", it) }
        enc(op.b)?.let { jo.put("b", it) }

        op.cmp?.let { jo.put("cmp", it.name) }
        op.resetDst?.let { jo.put("resetDst", it) }
        op.resetImm?.let { jo.put("resetImm", it) }

        arr.put(jo)
    }
    root.put("ops", arr)
    return root
}

fun AstModule.dump() {
    println("==== AST: $name ====")
    for (blk in blocks) {
        println("-- phase: ${blk.phase} --")
        for ((i, s) in blk.stmts.withIndex()) {
            fun e(e: AstExpr): String = when (e) {
                is AstExpr.Imm -> "#${e.value}"
                is AstExpr.FieldRef -> "${e.space}.${e.name}"
                is AstExpr.Bin -> "(${e(e.a)} ${e.op} ${e(e.b)})"
                is AstExpr.Cmp -> "(${e(e.a)} ${e.op} ${e(e.b)})"
            }
            when (s) {
                is AstStmt.Assign ->
                    println("[%02d] %s := %s".format(i, e(s.dst), e(s.expr)))
                is AstStmt.ExternalAssign ->
                    println("[%02d] %s := %s   // external".format(i, e(s.dst), e(s.expr)))
                is AstStmt.DelayAssign ->
                    println("[%02d] %s := %s   // delay".format(i, e(s.dst), e(s.expr)))
                is AstStmt.EmitIf -> {
                    val rst = if (s.resetDst != null && s.resetImm != null)
                        " reset ${e(s.resetDst)} := #${s.resetImm}" else ""
                    println("[%02d] EMIT_IF cond=%s out=%s%s"
                        .format(i, e(s.cond), s.outName, rst))
                }
            }
        }
    }
}


// ===== AST: выражения =====
sealed class AstExpr {
    data class Imm(val value: Int) : AstExpr()
    data class FieldRef(val space: String, val name: String) : AstExpr()   // space: "SPIKE"/"NEURON"
    data class Bin(val op: BinOp, val a: AstExpr, val b: AstExpr) : AstExpr()
    data class Cmp(val op: CmpOp, val a: AstExpr, val b: AstExpr) : AstExpr()
}

enum class BinOp { ADD, SUB, SHL, SHR }
enum class CmpOp { GT, GE, LT, LE, EQ, NE }

// ===== AST: операторы/инструкции =====
sealed class AstStmt {
    // Присваивание в локальное поле (SPIKE)
    data class Assign(val dst: AstExpr.FieldRef, val expr: AstExpr) : AstStmt()

    // Присваивание во внешнее поле (обычно NEURON) из спайковой фазы
    data class ExternalAssign(val dst: AstExpr.FieldRef, val expr: AstExpr) : AstStmt()

    // Операции с задержкой (локальные DELAY-поля)
    data class DelayAssign(val dst: AstExpr.FieldRef, val expr: AstExpr) : AstStmt()

    // Эмиссия с условием, с опциональным сбросом
    data class EmitIf(
        val cond: AstExpr.Cmp,
        val outName: String = "spike_out",
        val resetDst: AstExpr.FieldRef? = null,
        val resetImm: Int? = null
    ) : AstStmt()
}

// ===== AST: блоки и корень =====
enum class AstPhase { SYNAPTIC, NEURONAL }

data class AstBlock(val phase: AstPhase, val stmts: MutableList<AstStmt> = mutableListOf())

data class AstModule(
    val name: String = "tx_module",
    val blocks: MutableList<AstBlock> = mutableListOf(
        AstBlock(AstPhase.SYNAPTIC),
        AstBlock(AstPhase.NEURONAL)
    )
) {
    fun syn(): AstBlock = blocks.first { it.phase == AstPhase.SYNAPTIC }
    fun neu(): AstBlock = blocks.first { it.phase == AstPhase.NEURONAL }
}


// Утилиты для упаковки операндов в AstExpr
private fun spikeOpndToExpr(o: SpikeOperand): AstExpr =
    if (o.isImm) AstExpr.Imm(o.imm)
    else AstExpr.FieldRef(
        space = if (o.isExternal) (o.extSpace ?: "SPIKE") else "SPIKE",
        name = o.fieldName ?: error("SpikeOperand.fieldName == null")
    )

private fun neuronOpndToExpr(o: NeuronOperand): AstExpr =
    if (o.isImm) AstExpr.Imm(o.imm)
    else AstExpr.FieldRef(space = "NEURON", name = o.fieldName ?: error("NeuronOperand.fieldName == null"))

private fun spikeOpToBin(op: SpikeOpCode): BinOp = when (op) {
    SpikeOpCode.ADD -> BinOp.ADD
    SpikeOpCode.SUB -> BinOp.SUB
    SpikeOpCode.SHL -> BinOp.SHL
    SpikeOpCode.SHR -> BinOp.SHR
}
private fun neuronOpToBin(op: NeuronOpCode): BinOp = when (op) {
    NeuronOpCode.ADD -> BinOp.ADD
    NeuronOpCode.SUB -> BinOp.SUB
    NeuronOpCode.SHL -> BinOp.SHL
    NeuronOpCode.SHR -> BinOp.SHR
    else -> error("not a binary op: $op")
}

// Собираем AST из SpikeTx (в синоптический блок)
fun SpikeTx.toAst(module: AstModule) {
    val syn = module.syn()
    for (op in listOps()) {
        val a = spikeOpndToExpr(op.a)
        val b = spikeOpndToExpr(op.b)

        val bin = AstExpr.Bin(spikeOpToBin(op.opcode), a, b)

        val dstRef = AstExpr.FieldRef(
            space = if (op.dstIsExternal) (op.dstExtSpace ?: "NEURON") else "SPIKE",
            name = op.dst
        )

        // логика твоей постановки:
        // — если пишем во внешний нейрон — ExternalAssign;
        // — если dst — локальный DELAY → DelayAssign (мидлварь уже проверяет тип);
        // — иначе обычное Assign.
        if (op.dstIsExternal) {
            syn.stmts += AstStmt.ExternalAssign(dstRef, bin)
        } else {
            // здесь можно проверить, что это delay-поле (если хочется жестко валидировать)
            syn.stmts += AstStmt.DelayAssign(dstRef, bin)
        }
    }
}

// Собираем AST из NeuronTx (в нейронный блок)
fun NeuronTx.toAst(module: AstModule) {
    val neu = module.neu()
    for (op in listOps()) {
        when (op.opcode) {
            NeuronOpCode.ADD, NeuronOpCode.SUB, NeuronOpCode.SHL, NeuronOpCode.SHR -> {
                val a = neuronOpndToExpr(op.a)
                val b = neuronOpndToExpr(op.b)
                val bin = AstExpr.Bin(neuronOpToBin(op.opcode), a, b)
                val dst = AstExpr.FieldRef("NEURON", op.dst)
                neu.stmts += AstStmt.Assign(dst, bin)
            }

            NeuronOpCode.CMP_GE -> {
                val a = neuronOpndToExpr(op.a)
                val b = neuronOpndToExpr(op.b)
                val cmp = AstExpr.Cmp(CmpOp.GE, a, b)
                // сохраняем как Assign в предикат — это тоже нормальный AST-узел:
                val dst = AstExpr.FieldRef("NEURON", op.dst)
                neu.stmts += AstStmt.Assign(dst, cmp) // потребитель (emit) может прочитать предикат из поля
            }

            NeuronOpCode.EMIT -> {
                // интерпретация из твоего соглашения:
                //  a = предикат-поле (NeuronOperand.field)
                //  dst = "spike_out" (без сброса) ИЛИ имя поля для сброса
                //  b = imm(0) или imm(reset)
                val pred = AstExpr.FieldRef("NEURON", op.a.fieldName ?: error("emit predicate name null"))
                val zeroOrReset = neuronOpndToExpr(op.b)
                val cond = AstExpr.Cmp(CmpOp.NE, pred, AstExpr.Imm(0))  // предикат ≠ 0

                val (resetDst, resetImm) =
                    if (op.dst == "spike_out") null to null
                    else AstExpr.FieldRef("NEURON", op.dst) to (zeroOrReset as? AstExpr.Imm)?.value

                neu.stmts += AstStmt.EmitIf(
                    cond = cond,
                    outName = "spike_out",
                    resetDst = resetDst,
                    resetImm = resetImm
                )
            }

            NeuronOpCode.EMIT_IF -> {
                // если позже добавишь «атомарный» emit-if
                val a = neuronOpndToExpr(op.a)
                val b = neuronOpndToExpr(op.b)
                val cmpOp = when (op.cmp) {
                    NeuronCmp.GT -> CmpOp.GT
                    NeuronCmp.GE -> CmpOp.GE
                    NeuronCmp.LT -> CmpOp.LT
                    NeuronCmp.LE -> CmpOp.LE
                    NeuronCmp.EQ -> CmpOp.EQ
                    NeuronCmp.NE -> CmpOp.NE
                    else -> CmpOp.GE
                }
                val cond = AstExpr.Cmp(cmpOp, a, b)
                val resetDst = op.resetDst?.let { AstExpr.FieldRef("NEURON", it) }
                neu.stmts += AstStmt.EmitIf(
                    cond = cond,
                    outName = op.dst,
                    resetDst = resetDst,
                    resetImm = op.resetImm
                )
            }
        }
    }
}

// Построение единого AST (корня)
fun buildAst(spike: SpikeTx, neuron: NeuronTx, name: String = "lif_module"): AstModule {
    val m = AstModule(name)
    spike.toAst(m)
    neuron.toAst(m)
    return m
}