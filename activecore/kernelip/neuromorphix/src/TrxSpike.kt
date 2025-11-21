package neuromorphix

/** Тип поля спайковой транзакции. */
enum class SpikeFieldKind {
    // Поле, которое будет синтезироваться в память (например, w, tag)
    SYNAPTIC_PARAM,
    // Поле, используемое для реализации синаптической задержки
    DELAY
}

/** Дескриптор поля спайковой транзакции. */
class SpikeField(
    var name: String,
    var width: Int,
    var kind: SpikeFieldKind
)

/** Ссылка на поле (для будущего использования в AST/IR). */
class SpikeFieldRef(var name: String)

/* ===== 0) Опкоды и операнды (Spike) ===== */

enum class SpikeOpCode {
    ADD,   // +
    SUB,   // -
    SHL,   // <<
    SHR    // >>
}

/** Операнд: поле/константа. Поддержка внешних (нейронных) полей. */
class SpikeOperand {
    var isImm: Boolean = false
    var fieldName: String? = null

    // внешняя ссылка
    var isExternal: Boolean = false
    var extSpace: String? = null   // например, "NEURON"
    var imm: Int = 0

    companion object {
        fun field(name: String): SpikeOperand {
            val o = SpikeOperand()
            o.isImm = false
            o.fieldName = name
            return o
        }
        fun imm(value: Int): SpikeOperand {
            val o = SpikeOperand()
            o.isImm = true
            o.imm = value
            return o
        }
        /** Ссылка на поле из NeuronTx: neuron.<name> */
        fun neuronField(name: String): SpikeOperand {
            val o = SpikeOperand()
            o.isImm = false
            o.fieldName = name
            o.isExternal = true
            o.extSpace = "NEURON"
            return o
        }
    }
}

/** Узел операции (AST-узел) для SpikeTx. */
class SpikeOperation(
    val opcode: SpikeOpCode,
    val dst: String,              // имя поля: локальное или внешнее
    val a: SpikeOperand,
    val b: SpikeOperand,
    val dstIsExternal: Boolean = false,
    val dstExtSpace: String? = null  // "NEURON" и т.п.
)
/* ===== 1) SpikeTx с поддержкой операций ===== */

class SpikeTx {

    // Поля
    private val fields = mutableListOf<SpikeField>()

    // Список операций над полями этой транзакции
    private val ops = mutableListOf<SpikeOperation>()

    fun addField(
        name: String,
        width: Int,
        kind: SpikeFieldKind = SpikeFieldKind.SYNAPTIC_PARAM
    ): SpikeFieldRef {
        for (f in fields) {
            if (f.name == name) {
                println("Ошибка: поле с именем '$name' уже существует.")
                return SpikeFieldRef(name)
            }
        }
        val field = SpikeField(name, width, kind)
        fields.add(field)
        return SpikeFieldRef(name)
    }

    fun getField(name: String): SpikeField? {
        for (f in fields) if (f.name == name) return f
        return null
    }

    fun hasField(name: String): Boolean {
        for (f in fields) if (f.name == name) return true
        return false
    }

    fun listFields() {
        println("Список полей:")
        for (f in fields) println("  ${f.name} (${f.width} бит) тип=${f.kind}")
    }

    // ===== Базовые операции: +, -, <<, >> =====
// --- ВНЕШНИЕ операции: обновление поля нейрона ---
    fun opAddExt(dstNeuronName: String, a: SpikeOperand, b: SpikeOperand) {
        ops.add(
            SpikeOperation(
                SpikeOpCode.ADD,
                dstNeuronName,
                a, b,
                dstIsExternal = true,
                dstExtSpace = "NEURON"
            )
        )
    }

    fun opSubExt(dstNeuronName: String, a: SpikeOperand, b: SpikeOperand) {
        ops.add(
            SpikeOperation(
                SpikeOpCode.SUB,
                dstNeuronName,
                a, b,
                dstIsExternal = true,
                dstExtSpace = "NEURON"
            )
        )
    }

    fun opShlExt(dstNeuronName: String, a: SpikeOperand, shiftImm: Int) {
        ops.add(
            SpikeOperation(
                SpikeOpCode.SHL,
                dstNeuronName,
                a, SpikeOperand.imm(shiftImm),
                dstIsExternal = true,
                dstExtSpace = "NEURON"
            )
        )
    }

    fun opShrExt(dstNeuronName: String, a: SpikeOperand, shiftImm: Int) {
        ops.add(
            SpikeOperation(
                SpikeOpCode.SHR,
                dstNeuronName,
                a, SpikeOperand.imm(shiftImm),
                dstIsExternal = true,
                dstExtSpace = "NEURON"
            )
        )
    }

    // --- ЛОКАЛЬНЫЕ операции ТОЛЬКО ДЛЯ ЗАДЕРЖКИ ---
    private fun requireDelay(name: String): Boolean {
        val f = getField(name)
        if (f == null) { println("Ошибка: локальное поле '$name' не найдено."); return false }
        if (f.kind != SpikeFieldKind.DELAY) {
            println("Ошибка: допустимы только операции над полями DELAY (поле '$name' имеет тип ${f.kind}).")
            return false
        }
        return true
    }

    fun opDelayAddImm(delayField: String, imm: Int) {
        if (!requireDelay(delayField)) return
        ops.add(SpikeOperation(SpikeOpCode.ADD, delayField, SpikeOperand.field(delayField), SpikeOperand.imm(imm)))
    }

    fun opDelaySubImm(delayField: String, imm: Int) {
        if (!requireDelay(delayField)) return
        ops.add(SpikeOperation(SpikeOpCode.SUB, delayField, SpikeOperand.field(delayField), SpikeOperand.imm(imm)))
    }

    fun opDelayShlImm(delayField: String, imm: Int) {
        if (!requireDelay(delayField)) return
        ops.add(SpikeOperation(SpikeOpCode.SHL, delayField, SpikeOperand.field(delayField), SpikeOperand.imm(imm)))
    }

    fun opDelayShrImm(delayField: String, imm: Int) {
        if (!requireDelay(delayField)) return
        ops.add(SpikeOperation(SpikeOpCode.SHR, delayField, SpikeOperand.field(delayField), SpikeOperand.imm(imm)))
    }

    // ===== Сервис для последующих этапов (AST/IR) =====

    /** Снимок списка всех полей спайковой транзакции (для мидлвари). */
    fun listFieldsSnapshot(): List<SpikeField> = fields.toList()

    fun listOps(): List<SpikeOperation> = ops.toList()

    fun dumpOps() {
        println("Операции SpikeTx:")
        for (op in ops) {
            val aStr = if (op.a.isImm) "imm=${op.a.imm}" else "field=${op.a.fieldName}"
            val bStr = if (op.b.isImm) "imm=${op.b.imm}" else "field=${op.b.fieldName}"

            val dstInfo = buildString {
                append("dst=${op.dst}")
                if (op.dstIsExternal)
                    append(" (external space=${op.dstExtSpace})")
            }

            println("  opcode=${op.opcode}, $dstInfo, a=($aStr), b=($bStr)")
        }
    }
}