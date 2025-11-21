package neuromorphix

/* ================================================================
 * 2) Symbols: таблица символов IR-пространства + widths из SnnArch
 * ================================================================ */

data class Symbols(
    val spikeFields: Map<String, SpikeFieldDesc>,
    val neuronFields: Map<String, NeuronFieldDesc>,
    val widths: DerivedWidths
) {
    fun has(space: String, name: String): Boolean = when (space) {
        SPACE_SPIKE  -> spikeFields.containsKey(name)
        SPACE_NEURON -> neuronFields.containsKey(name)
        else -> false
    }

    fun widthOf(space: String, name: String): Int = when (space) {
        SPACE_SPIKE  -> spikeFields[name]?.width
        SPACE_NEURON -> neuronFields[name]?.width
        else -> null
    } ?: error("Symbols: unknown symbol $space.$name")

    /** Упрощённый resolve для TxOperand (для биндинга). */
    fun resolve(op: TxOperand?): ResolvedOperand {
        if (op == null) return ResolvedOperand(isImm = true, imm = 0)
        return if (op.isImm) {
            ResolvedOperand(isImm = true, imm = op.imm ?: 0)
        } else {
            val sp = op.space ?: error("TxOperand.space is null for field ${op.name}")
            val nm = op.name  ?: error("TxOperand.name is null (space=$sp)")
            ResolvedOperand(isImm = false, space = sp, name = nm, width = widthOf(sp, nm))
        }
    }
}

data class SpikeFieldDesc(val name: String, val width: Int, val kind: SpikeFieldKind)
data class NeuronFieldDesc(val name: String, val width: Int, val kind: NeuronFieldKind)

data class ResolvedOperand(
    val isImm: Boolean,
    val imm: Int? = null,
    val space: String? = null,
    val name: String? = null,
    val width: Int = 0
)

/* ================================================================
 * Хелперы: строим Symbols из текущей версии SnnArch + Tx
 * ================================================================ */

/** В актуальной модели архитектуры просто берём уже посчитанные ширины. */
fun toDerivedWidths(arch: SnnArch): DerivedWidths = arch.d

/** Нужен «снимок» полей в NeuronTx (по аналогии со SpikeTx). */
fun NeuronTx.listFieldsSnapshot(): List<NeuronField> = run {
    // если у тебя уже есть такой метод — оставь свой и удали этот.
    val acc = mutableListOf<NeuronField>()
    // Простой способ: пройдёмся по известным именам в твоём сценарии.
    // Лучше — храни fields как внутренний список и верни .toList().
    for (name in listOf("Vmemb", "Vthr", "Vrst")) {
        getField(name)?.let { acc += it }
    }
    acc
}

/** Сборка Symbols из объявленных транзакций и DerivedWidths. */
fun buildSymbols(
    arch: SnnArch,
    spike: SpikeTx,
    neuron: NeuronTx,
    addSpikeOut: Boolean = true
): Symbols {
    val spikeMap = spike.listFieldsSnapshot().associate { f ->
        f.name to SpikeFieldDesc(f.name, f.width, f.kind)
    }

    val neuronMap = LinkedHashMap<String, NeuronFieldDesc>().apply {
        neuron.listFieldsSnapshot().forEach { nf ->
            put(nf.name, NeuronFieldDesc(nf.name, nf.width, nf.kind))
        }
    }

    if (addSpikeOut && "spike_out" !in neuronMap) {
        neuronMap["spike_out"] = NeuronFieldDesc(
            name  = "spike_out",
            width = arch.d.postsynIdxW,              // ширина пэйлоада события = ширина post-индекса
            kind  = NeuronFieldKind.EMIT_PREDICATE
        )
    }

    return Symbols(
        spikeFields = spikeMap,
        neuronFields = neuronMap,
        widths = toDerivedWidths(arch)
    )
}

/* ================================================================
 * Склейка: инициализация SnnArch + Tx + Symbols (готов к биндингу)
 * ================================================================ */

data class TxContext(
    val arch: SnnArch,
    val spike: SpikeTx,
    val neuron: NeuronTx,
    val symbols: Symbols,
    val ir: TxIR,
    val ast: AstModule
)

/** Построить контекст по твоему примерному сценарию. */
fun buildDefaultTxContext(loadJson: String? = null): TxContext {
    // 1) Архитектура
    val arch = loadJson?.let { SnnArch.fromJsonFile(it) } ?: SnnArch(
        modelName = "Default",
        nnType    = NeuralNetworkType.SFNN,
        dims      = NnDims(presynCount = 28*28, postsynCount = 128),
        neuron    = NeuronParams(threshold = 1, reset = 0, leakage = 1),
        numeric   = NumericLayout(weightWidth = 16, potentialWidth = 16)
    )

    // 2) Спайковая транзакция
    val spike = SpikeTx().apply {
        addField("w",     16, SpikeFieldKind.SYNAPTIC_PARAM) // вес
        addField("delay", 8,  SpikeFieldKind.DELAY)          // задержка
        addField("tag",   8,  SpikeFieldKind.SYNAPTIC_PARAM) // метка/тег

        // Vmemb := Vmemb + w   (обновляем НЕЙРОННОЕ поле из спайковой фазы)
        opAddExt(
            dstNeuronName = "Vmemb",
            a = SpikeOperand.neuronField("Vmemb"),
            b = SpikeOperand.field("w")
        )
    }

    // 3) Нейронная транзакция
    val neuron = NeuronTx().apply {
        addField("Vmemb", 16, NeuronFieldKind.DYNAMIC)
        addField("Vthr",  16, NeuronFieldKind.STATIC)
        addField("Vrst",  16, NeuronFieldKind.STATIC)

        // Утечка: Vmemb >>= 1
        opShrImm(dst = "Vmemb", aField = "Vmemb", shift = 1)

        // if (Vmemb >= Vthr) emit; reset Vmemb := 0
        val cond = ifGe("Vmemb", "Vthr")
        opEmitIf(cond, resetDst = "Vmemb", resetImm = 0)
    }

    // 4) IR/AST (на будущее)
    val ir  = buildTxIR(spike, neuron)
    val ast = buildAst(spike, neuron, name = "lif_ast")

    // 5) Таблица символов
    val symbols = buildSymbols(arch, spike, neuron)

    return TxContext(arch, spike, neuron, symbols, ir, ast)
}

/* ====== Пример вызова (можно в main) ======
fun main() {
    val ctx = buildDefaultTxContext()
    println(ctx.arch.info())
    println("== Spike fields ==")
    ctx.spike.listFields()
    println("== Neuron fields ==")
    ctx.neuron.listFields()
    println("== IR ==")
    ctx.ir.dump()
    println("== AST ==")
    ctx.ast.dump()
    println("== Symbols ==")
    println("SPIKE: " + ctx.symbols.spikeFields.keys)
    println("NEURON: " + ctx.symbols.neuronFields.keys)
}
*/