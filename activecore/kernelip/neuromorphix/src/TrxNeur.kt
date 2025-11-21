package neuromorphix



// NeuronTx.kt — минимальный класс нейронной транзакции (согласовано)

// NeuronTx.kt — минимальный класс нейронной транзакции (с EMIT и REFRACTORY)

/** Тип поля нейронной транзакции. */
enum class NeuronFieldKind {
    DYNAMIC,          // динамический параметр (Vmemb и т.п.)
    STATIC,           // статический параметр (Vthr, leakK, Vrst)
    EMIT_PREDICATE,   // предикат/флаг эмиссии спайка
    REFRACTORY        // параметры/состояния для пост-эмиссии (рефракторный период)
}

/** Дескриптор поля нейронной транзакции. */
class NeuronField(
    var name: String,
    var width: Int,
    var kind: NeuronFieldKind
)

/** Ссылка на поле (для будущего использования в AST/IR). */
class NeuronFieldRef(var name: String)

/** Опкоды базовых операций. */
enum class NeuronOpCode {
    ADD,   // +
    SUB,   // -
    SHL,   // <<
    SHR,   // >>
    EMIT,  // эмиссия спайка по предикату
    CMP_GE, // dstPredicate = (a >= b)
    EMIT_IF
}

// Операторы сравнения
// Операторы сравнения
enum class NeuronCmp {
    GT, LT, GE, LE,
    EQ, NE   // <- добавили для внутреннего использования (== и !=)
}
//enum class NeuronCmp {
//    GT, LT, GE, LE
//}

// Условие для if: (a ? b)
class NeuronCond(
    var cmp: NeuronCmp,
    var a: NeuronOperand,
    var b: NeuronOperand
)

/** Операнд операции: поле или константа. */
class NeuronOperand {
    var isImm: Boolean = false
    var fieldName: String? = null
    var imm: Int = 0

    companion object {
        fun field(name: String): NeuronOperand {
            val o = NeuronOperand()
            o.isImm = false
            o.fieldName = name
            return o
        }
        fun imm(value: Int): NeuronOperand {
            val o = NeuronOperand()
            o.isImm = true
            o.imm = value
            return o
        }
    }
}

/** Узел операции. */
class NeuronOperation(
    val opcode: NeuronOpCode,
    val dst: String,           // назначение: имя поля ИЛИ служебное имя для EMIT (напр. "spike_out")
    val a: NeuronOperand,      // аргумент A: для EMIT — это предикат (поле)
    val b: NeuronOperand,       // аргумент B: не используется для EMIT (можно 0)
    val cmp: NeuronCmp? = null,   // GT/LT/GE/LE для EMIT_IF
    val resetDst: String? = null, // поле для сброса (или null)
    val resetImm: Int? = null     // константа сброса (или null)
)

/**
 * Нейронная транзакция: контейнер полей и список операций.
 * Типы полей: DYNAMIC / STATIC / EMIT_PREDICATE / REFRACTORY.
 */
class NeuronTx {

    // Поля
    private val fields = mutableListOf<NeuronField>()

    // Операции над полями
    private val ops = mutableListOf<NeuronOperation>()

    /**
     * Добавить поле в транзакцию.
     * name — имя поля
     * width — разрядность (бит)
     * kind — тип поля (по умолчанию DYNAMIC)
     */
    fun addField(name: String, width: Int, kind: NeuronFieldKind = NeuronFieldKind.DYNAMIC): NeuronFieldRef {
        // Проверка уникальности имени
        for (f in fields) {
            if (f.name == name) {
                println("Ошибка: поле с именем '$name' уже существует.")
                return NeuronFieldRef(name)
            }
        }
        val field = NeuronField(name, width, kind)
        fields.add(field)
        return NeuronFieldRef(name)
    }

    /** Получить поле по имени (или null). */
    fun getField(name: String): NeuronField? {
        for (f in fields) if (f.name == name) return f
        return null
    }

    /** Есть ли поле с таким именем. */
    fun hasField(name: String): Boolean {
        for (f in fields) if (f.name == name) return true
        return false
    }

    /** Показать все поля (для отладки). */
    fun listFields() {
        println("Поля NeuronTx:")
        for (f in fields) {
            println("  ${f.name} (${f.width} бит) тип=${f.kind}")
        }
    }

    // ===== Базовые операции: +, -, <<, >> =====

    fun opAdd(dst: String, aField: String, bField: String) {
        if (!hasField(dst) || !hasField(aField) || !hasField(bField)) {
            println("Ошибка: одно из полей не найдено (dst/a/b)."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.ADD, dst, NeuronOperand.field(aField), NeuronOperand.field(bField)))
    }
    fun opAddImm(dst: String, aField: String, imm: Int) {
        if (!hasField(dst) || !hasField(aField)) {
            println("Ошибка: поле dst или a не найдено."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.ADD, dst, NeuronOperand.field(aField), NeuronOperand.imm(imm)))
    }

    fun opSub(dst: String, aField: String, bField: String) {
        if (!hasField(dst) || !hasField(aField) || !hasField(bField)) {
            println("Ошибка: одно из полей не найдено (dst/a/b)."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.SUB, dst, NeuronOperand.field(aField), NeuronOperand.field(bField)))
    }
    fun opSubImm(dst: String, aField: String, imm: Int) {
        if (!hasField(dst) || !hasField(aField)) {
            println("Ошибка: поле dst или a не найдено."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.SUB, dst, NeuronOperand.field(aField), NeuronOperand.imm(imm)))
    }

    fun opShlImm(dst: String, aField: String, shift: Int) {
        if (!hasField(dst) || !hasField(aField)) {
            println("Ошибка: поле dst или a не найдено."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.SHL, dst, NeuronOperand.field(aField), NeuronOperand.imm(shift)))
    }

    fun opShrImm(dst: String, aField: String, shift: Int) {
        if (!hasField(dst) || !hasField(aField)) {
            println("Ошибка: поле dst или a не найдено."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.SHR, dst, NeuronOperand.field(aField), NeuronOperand.imm(shift)))
    }

    // ===== Сравнение: fired = (aField >= bField) =====
    fun opCmpGe(dstPredicate: String, aField: String, bField: String) {
        // проверки существования полей
        if (!hasField(dstPredicate) || !hasField(aField) || !hasField(bField)) {
            println("Ошибка: одно из полей не найдено (dstPredicate/aField/bField)."); return
        }

        // предупреждения/валидация типа предиката
        val dst = getField(dstPredicate)
        if (dst != null) {
            if (dst.kind != NeuronFieldKind.EMIT_PREDICATE) {
                println("Предупреждение: поле '$dstPredicate' имеет тип ${dst.kind}, ожидается EMIT_PREDICATE.")
            }
            if (dst.width != 1) {
                println("Предупреждение: поле-предикат '$dstPredicate' имеет ширину ${dst.width}, обычно ожидается 1 бит.")
            }
        }

        ops.add(
            NeuronOperation(
                NeuronOpCode.CMP_GE,
                dstPredicate,
                NeuronOperand.field(aField),
                NeuronOperand.field(bField)
            )
        )
    }
    // ===== Сравнение: fired = (aField >= imm) =====
    fun opCmpGeImm(dstPredicate: String, aField: String, imm: Int) {
        if (!hasField(dstPredicate) || !hasField(aField)) {
            println("Ошибка: поле dstPredicate или aField не найдено."); return
        }

        val dst = getField(dstPredicate)
        if (dst != null) {
            if (dst.kind != NeuronFieldKind.EMIT_PREDICATE) {
                println("Предупреждение: поле '$dstPredicate' имеет тип ${dst.kind}, ожидается EMIT_PREDICATE.")
            }
            if (dst.width != 1) {
                println("Предупреждение: поле-предикат '$dstPredicate' имеет ширину ${dst.width}, обычно ожидается 1 бит.")
            }
        }

        ops.add(
            NeuronOperation(
                NeuronOpCode.CMP_GE,
                dstPredicate,
                NeuronOperand.field(aField),
                NeuronOperand.imm(imm)
            )
        )
    }

    // ===== Секция helpers условий (добавить внутрь NeuronTx) =====
    private fun cond(cmp: NeuronCmp, a: NeuronOperand, b: NeuronOperand): NeuronCond =
        NeuronCond(cmp, a, b)

    fun ifGt(aField: String, bField: String): NeuronCond {
        if (!hasField(aField) || !hasField(bField)) {
            println("Ошибка: поле для сравнения не найдено (ifGt: $aField|$bField).");
        }
        return cond(NeuronCmp.GT, NeuronOperand.field(aField), NeuronOperand.field(bField))
    }
    fun ifGtImm(aField: String, imm: Int): NeuronCond {
        if (!hasField(aField)) { println("Ошибка: поле для сравнения не найдено (ifGtImm: $aField).") }
        return cond(NeuronCmp.GT, NeuronOperand.field(aField), NeuronOperand.imm(imm))
    }

    fun ifLt(aField: String, bField: String): NeuronCond {
        if (!hasField(aField) || !hasField(bField)) {
            println("Ошибка: поле для сравнения не найдено (ifLt: $aField|$bField).");
        }
        return cond(NeuronCmp.LT, NeuronOperand.field(aField), NeuronOperand.field(bField))
    }
    fun ifLtImm(aField: String, imm: Int): NeuronCond {
        if (!hasField(aField)) { println("Ошибка: поле для сравнения не найдено (ifLtImm: $aField).") }
        return cond(NeuronCmp.LT, NeuronOperand.field(aField), NeuronOperand.imm(imm))
    }

    fun ifGe(aField: String, bField: String): NeuronCond {
        if (!hasField(aField) || !hasField(bField)) {
            println("Ошибка: поле для сравнения не найдено (ifGe: $aField|$bField).");
        }
        return cond(NeuronCmp.GE, NeuronOperand.field(aField), NeuronOperand.field(bField))
    }
    fun ifGeImm(aField: String, imm: Int): NeuronCond {
        if (!hasField(aField)) { println("Ошибка: поле для сравнения не найдено (ifGeImm: $aField).") }
        return cond(NeuronCmp.GE, NeuronOperand.field(aField), NeuronOperand.imm(imm))
    }

    fun ifLe(aField: String, bField: String): NeuronCond {
        if (!hasField(aField) || !hasField(bField)) {
            println("Ошибка: поле для сравнения не найдено (ifLe: $aField|$bField).");
        }
        return cond(NeuronCmp.LE, NeuronOperand.field(aField), NeuronOperand.field(bField))
    }
    fun ifLeImm(aField: String, imm: Int): NeuronCond {
        if (!hasField(aField)) { println("Ошибка: поле для сравнения не найдено (ifLeImm: $aField).") }
        return cond(NeuronCmp.LE, NeuronOperand.field(aField), NeuronOperand.imm(imm))
    }

    // ===== Операция эмиссии спайка =====
    /**
     * Эмиссия спайка при истинном предикате.
     * @param predicateField имя поля-предиката (должно существовать, обычно kind=EMIT_PREDICATE)
     * @param outName логическое имя «приёмника» спайка (для IR/бэкенда), по умолчанию "spike_out"
     *
     * Представление в операции:
     *   opcode = EMIT
     *   dst    = outName
     *   a      = predicateField
     *   b      = imm(0)  // зарезервировано, не используется
     */
    /** 1) Просто эмиссия по предикату (без сброса). */
    fun opEmit(predicateField: String, outName: String = "spike_out") {
        if (!hasField(predicateField)) {
            println("Ошибка: предикат '$predicateField' не найден."); return
        }
        val pf = getField(predicateField)
        if (pf != null && pf.kind != NeuronFieldKind.EMIT_PREDICATE) {
            println("Предупреждение: поле '$predicateField' имеет тип ${pf.kind}, ожидается EMIT_PREDICATE.")
        }
        // Соглашение: dst = "spike_out" => только эмиссия
        ops.add(
            NeuronOperation(
                NeuronOpCode.EMIT,
                outName,                             // dst: логическое имя выхода
                NeuronOperand.field(predicateField), // a: предикат
                NeuronOperand.imm(0)                 // b: 0 => без сброса
            )
        )
    }

    /**
     * Соглашение для бэкенда:
     *   opcode = EMIT
     *   dst    = имя поля, которое надо сбросить (напр. "Vmemb")
     *   a      = поле-предикат (например, "fired")
     *   b      = imm(resetImm)  // константа сброса
     *
     * При этом сама эмиссия тоже происходит (как и в простом EMIT).
     */
    fun opEmit(predicateField: String, dstReset: String, resetImm: Int) {
        if (!hasField(predicateField) || !hasField(dstReset)) {
            println("Ошибка: предикат или поле для сброса не найдено."); return
        }
        val pf = getField(predicateField)
        if (pf != null && pf.kind != NeuronFieldKind.EMIT_PREDICATE) {
            println("Предупреждение: поле '$predicateField' имеет тип ${pf.kind}, ожидается EMIT_PREDICATE.")
        }
        ops.add(
            NeuronOperation(
                NeuronOpCode.EMIT,
                dstReset,                             // dst: поле для сброса (а не "spike_out")
                NeuronOperand.field(predicateField),  // a: предикат
                NeuronOperand.imm(resetImm)           // b: константа сброса
            )
        )
    }

    fun opEmitIf(
        cond: NeuronCond,
        resetDst: String? = null,
        resetImm: Int? = null,
        outName: String = "spike_out"
    ) {
        if (resetDst != null && !hasField(resetDst)) {
            println("Ошибка: поле для сброса '$resetDst' не найдено.");
            return
        }
        ops.add(
            NeuronOperation(
                NeuronOpCode.EMIT_IF,
                dst = outName,
                a = cond.a,
                b = cond.b,
                cmp = cond.cmp,
                resetDst = resetDst,
                resetImm = resetImm
            )
        )
    }


    // ===== Сервис для последующих этапов =====

    fun listOps(): List<NeuronOperation> = ops.toList()

    fun dumpOps() {
        println("Операции NeuronTx:")
        for (op in ops) {
            val aStr = if (op.a.isImm) "${op.a.imm}" else op.a.fieldName
            val bStr = if (op.b.isImm) "${op.b.imm}" else op.b.fieldName
            if (op.opcode == NeuronOpCode.EMIT_IF) {
                val cmpStr = when (op.cmp) {
                    NeuronCmp.GT -> ">"
                    NeuronCmp.LT -> "<"
                    NeuronCmp.GE -> ">="
                    NeuronCmp.LE -> "<="
                    NeuronCmp.EQ -> "=="
                    NeuronCmp.NE -> "!="
                    else -> "?"
                }
                val resetInfo = if (op.resetDst != null && op.resetImm != null)
                    ", reset ${op.resetDst} := ${op.resetImm}" else ""
                println("  EMIT_IF ${op.dst}  if ($aStr $cmpStr $bStr)$resetInfo")
            } else {
                println("  ${op.opcode}  ${op.dst} = $aStr , $bStr")
            }
        }
    }

    fun listFieldsSnapshot(): List<NeuronField> = fields.toList()

}


