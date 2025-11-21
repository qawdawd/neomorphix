package neuromorphix

// 3) Bind plan: кто какую IR-операцию исполняет и какие ключи нужны
data class BindPlan(
    val phaseOrder: List<TxPhase>,
    val synRules: List<SynBindRule>,
    val neurRules: List<NeurBindRule>,
    val emitRule: EmitRule
)

// — SYNAPTIC: операция на весе, пишет в динамику (mainDynamicName из insights)
data class SynBindRule(
    val irIndex: Int,                // индекс IR-операции (для трассировки)
    val opcode: TxOpcode,            // ADD/SUB (SHL/SHR при желании)
    val synParamField: String,       // "w" и т.п.
    val dynField: String             // куда писать (например, "Vmemb")
)

// — NEURONAL: скалярные операции над динамикой с использованием регбанка
data class NeurBindRule(
    val irIndex: Int,
    val opcode: TxOpcode,            // ADD/SUB/SHL/SHR
    val dstDynField: String,         // обычно "Vmemb"
    val regKey: String?              // если операция использует регистр (например, SHR by leakage)
)

// — EMIT: одно правило на фазу
data class EmitRule(
    val cmp: CmpKind,
    val cmpRegKey: String,
    val refractory: Boolean,
    val resetRegKey: String?
)

// 2) TxBinder — новый объект для построения BindPlan
object TxBinder {
    fun buildBindPlan(
        ir: TxIR,
        symbols: Symbols,
        layout: LayoutPlan,
        insights: PlanInsights
    ): BindPlan {
        val dynamicSet = symbols.neuronFields.values
            .filter { it.kind == NeuronFieldKind.DYNAMIC }
            .map { it.name }.toSet()

        val synRules = ir.ops.withIndex()
            .filter { (_, op) -> op.phase == TxPhase.SYNAPTIC && op.dstSpace == SPACE_NEURON && op.dst in dynamicSet }
            .map { (idx, op) ->
                val synParam = listOfNotNull(op.a, op.b)
                    .firstOrNull { o -> !o.isImm && o.space == SPACE_SPIKE }?.name
                    ?: insights.synParamPreferred
                SynBindRule(
                    irIndex = idx,
                    opcode = op.opcode,
                    synParamField = synParam!!,
                    dynField = op.dst ?: insights.mainDynamicName
                )
            }

        val neurRules = ir.ops.withIndex()
            .filter { (_, op) ->
                op.phase == TxPhase.NEURONAL && op.dst in dynamicSet &&
                        (op.opcode == TxOpcode.ADD || op.opcode == TxOpcode.SUB ||
                                op.opcode == TxOpcode.SHL || op.opcode == TxOpcode.SHR)
            }
            .map { (idx, op) ->
                val regKey = when {
                    op.b?.isImm == true -> null
                    op.b?.space == SPACE_NEURON -> op.b?.name
                    else -> null
                }
                NeurBindRule(
                    irIndex = idx,
                    opcode = op.opcode,
                    dstDynField = op.dst!!,
                    regKey = regKey
                )
            }

        val emitRule = EmitRule(
            cmp = insights.emitCmp,
            cmpRegKey = insights.emitCmpRegKey,
            refractory = insights.emitRefractory,
            resetRegKey = insights.emitResetRegKey
        )

        return BindPlan(
            phaseOrder = listOf(TxPhase.SYNAPTIC, TxPhase.NEURONAL),
            synRules = synRules,
            neurRules = neurRules,
            emitRule = emitRule
        )
    }
}