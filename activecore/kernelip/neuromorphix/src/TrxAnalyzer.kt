package neuromorphix

/** Результат анализа транзакций для помощи билдеру LayoutPlan. */
data class PlanInsights(
    // SYN-фаза
    val synParamPreferred: String,     // напр. "w"
    val synOp: SynOpKind,              // ADD/SUB/REPLACE (пока ADD/SUB)
    val mainDynamicName: String,       // напр. "Vmemb" — куда писать из SYN
    // EMIT-фаза
    val emitCmp: CmpKind,
    val emitCmpRegKey: String,         // реальное имя регистра порога или сгенерированный cmpImm_*
    val emitRefractory: Boolean,
    val emitResetRegKey: String?,      // реальное имя или rstImm_*
    val extraRegs: List<RegDesc> = emptyList(),  // cmpImm_*, rstImm_* и пр.
    // Упаковка (если применимо)
    val synPack: SynPackPlan? = null
)

private data class EmitDerivation(
    val cmp: CmpKind,
    val cmpRegKey: String,
    val refractory: Boolean,
    val resetRegKey: String?,
    val extraRegs: List<RegDesc>     // синтезированные константные регистры
)

/** Анализатор IR/AST → возвращает подсказки для билдера LayoutPlan. */
object TxAnalyzer {

    fun analyze(
        arch: SnnArch,
        symbols: Symbols,
        ir: TxIR?,
        packAllSynParams: Boolean,
        synParams: List<SpikeFieldDesc>
    ): PlanInsights {
        val synParamPreferred = deriveSynParamPreferred(ir, synParams)
        val synOp             = deriveSynOp(ir)
        val mainDynamicName   = deriveMainDynamic(symbols, ir)

        val emit = deriveEmit(arch, symbols, ir)
        val pack = if (packAllSynParams) buildSynPack(synParams) else null

        return PlanInsights(
            synParamPreferred = synParamPreferred,
            synOp = synOp,
            mainDynamicName = mainDynamicName,
            emitCmp = emit.cmp,
            emitCmpRegKey = emit.cmpRegKey,
            emitRefractory = emit.refractory,
            emitResetRegKey = emit.resetRegKey,
            extraRegs = emit.extraRegs,
            synPack = pack
        )
    }

    /** Выбор рабочего синпараметра: по IR → "w" → первый. */
    private fun deriveSynParamPreferred(
        ir: TxIR?,
        synParams: List<SpikeFieldDesc>
    ): String {
        val fromIr: String? = ir?.ops
            ?.asSequence()
            ?.filter { it.phase == TxPhase.SYNAPTIC && it.dstSpace == SPACE_NEURON }
            ?.mapNotNull { op -> listOfNotNull(op.a, op.b).firstOrNull { o -> !o.isImm && o.space == SPACE_SPIKE }?.name }
            ?.firstOrNull()

        return fromIr ?: (if (synParams.any { it.name == "w" }) "w" else synParams.first().name)
    }

    /** Операция синфазы из IR (ADD/SUB), дефолт: ADD. */
    private fun deriveSynOp(ir: TxIR?): SynOpKind =
        ir?.ops
            ?.filter { it.phase == TxPhase.SYNAPTIC && it.dstSpace == SPACE_NEURON }
            ?.mapNotNull {
                when (it.opcode) {
                    TxOpcode.ADD -> SynOpKind.ADD
                    TxOpcode.SUB -> SynOpKind.SUB
                    else -> null
                }
            }?.distinct()?.singleOrNull() ?: SynOpKind.ADD

    /** Главная динамика: dst нейрона в SYN → "Vmemb" → первый DYNAMIC. */
    private fun deriveMainDynamic(symbols: Symbols, ir: TxIR?): String {
        val dynamicFields = symbols.neuronFields.values.filter { it.kind == NeuronFieldKind.DYNAMIC }
        require(dynamicFields.isNotEmpty()) { "Нет нейронных полей с kind=DYNAMIC" }

        val fromIr = ir?.ops
            ?.asSequence()
            ?.filter { it.phase == TxPhase.SYNAPTIC && it.dstSpace == SPACE_NEURON }
            ?.mapNotNull { it.dst }
            ?.firstOrNull { dst -> dynamicFields.any { it.name == dst } }

        return fromIr ?: if (dynamicFields.any { it.name == "Vmemb" }) "Vmemb" else dynamicFields.first().name
    }

    /** Достаёт EMIT_IF и подготавливает cmp/reset ключи + extraRegs. */
    private fun deriveEmit(arch: SnnArch, symbols: Symbols, ir: TxIR?): EmitDerivation {
        val emit = ir?.ops?.firstOrNull { it.phase == TxPhase.NEURONAL && it.opcode == TxOpcode.EMIT_IF }
            ?: return EmitDerivation(
                cmp = CmpKind.GE,
                cmpRegKey = symbols.neuronFields.keys.firstOrNull() ?: "Vthr",
                refractory = false,
                resetRegKey = null,
                extraRegs = emptyList()
            )

        val cmp = when (emit.cmp ?: NeuronCmp.GE) {
            NeuronCmp.GT -> CmpKind.GT
            NeuronCmp.LT -> CmpKind.LT
            NeuronCmp.GE -> CmpKind.GE
            NeuronCmp.LE -> CmpKind.LE
            NeuronCmp.EQ, NeuronCmp.NE -> CmpKind.GE // заглушка
        }

        val extra = mutableListOf<RegDesc>()

        val b = emit.b ?: error("EMIT_IF: right operand (b) is null")
        val cmpRegKey = if (!b.isImm) {
            b.name ?: error("EMIT_IF: right operand has no name")
        } else {
            val imm = b.imm ?: error("EMIT_IF: immediate 'b.imm' is null")
            val name = "cmpImm_$imm"
            extra += RegDesc(name = name, width = arch.d.potentialW, init = imm.toString())
            name
        }

        var refractory = false
        var resetRegKey: String? = null

        if (emit.resetDst != null || emit.resetImm != null) {
            refractory = true
            if (emit.resetImm != null) {
                val imm = emit.resetImm!!
                if ("Vrst" in symbols.neuronFields && imm == 0) {
                    resetRegKey = "Vrst"
                } else {
                    val name = "rstImm_$imm"
                    extra += RegDesc(name = name, width = arch.d.potentialW, init = imm.toString())
                    resetRegKey = name
                }
            } else {
                resetRegKey = emit.resetDst
            }
        }

        return EmitDerivation(
            cmp = cmp,
            cmpRegKey = cmpRegKey,
            refractory = refractory,
            resetRegKey = resetRegKey,
            extraRegs = extra
        )
    }
}