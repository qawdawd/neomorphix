package neuromorphix

/* ================================================================
 *  LayoutPlan: статическая компоновка БММ + конфиги компонентов
 * ================================================================ */

// было:
// data class LayoutPlan(..., val wmem: StaticMemPlan, ...)

// стало:
interface LayoutPlan {
    val tick: TickPlan
    val fifoIn: FifoInPlan
    val fifoOut: FifoOutPlan
    val wmems: Map<String, StaticMemPlan>   // ключ = имя SPIKE-поля SYNAPTIC_PARAM (напр. "w", "tag")
    val dyn: DynArrays
    val regBank: RegBankPlan
    val selector: SynSelPlan
    val phases: PhasePlans
    val topology: TopologySpec
}

data class DefaultLayoutPlan(
    override val tick: TickPlan,
    override val fifoIn: FifoInPlan,
    override val fifoOut: FifoOutPlan,
    override val wmems: Map<String, StaticMemPlan>,
    override val dyn: DynArrays,
    override val regBank: RegBankPlan,
    override val selector: SynSelPlan,
    override val phases: PhasePlans,
    override val topology: TopologySpec
) : LayoutPlan

// ——— «тик» как источник временной разметки
data class TickPlan(
    val signalName: String,     // логическое имя тика в HDL (например, "tick")
    val cfg: TickGenCfg
)

// ——— входной/выходной FIFO
data class FifoInPlan(val role: String, val cfg: FifoCfg)
data class FifoOutPlan(val role: String, val cfg: FifoCfg)


// ——— набор динамических массивов (главный + дополнительные)
data class DynArrays(
    val main: DynParamPlan,
    val extra: List<DynParamPlan> = emptyList()
)
data class DynParamPlan(val field: String, val bitWidth: Int, val count: Int)

// ——— банк регистров (описания совпадают с твоим RegBankGen)
data class RegBankPlan(val regs: List<RegDesc>, val mapApiKeys: Map<String, String> = emptyMap())

// ——— селектор синапсов
data class SynSelPlan(val cfg: SynSelCfg)

// ——— фазы
data class PhasePlans(
    val syn: SynPhasePlan,
    val neur: NeurPhasePlan,
    val emit: EmitPlan
)

//enum class NeurOpKind { ADD, SUB, SHL, SHR }
//data class NeurOpSpec(val kind: NeurOpKind, val regKey: String? = null) // если null — оперируем только над dyn.acc

data class NeurPhasePlan(
    val ops: List<NeurOpSpec>,
    val postsynCountRegKey: String   // откуда брать фактическое N пост-нейронов (ключ в RegBank)
)

//enum class CmpKind { GT, LT, GE, LE }
data class EmitPlan(
    val cmp: CmpKind,
    val cmpRegKey: String,           // ключ регистра порога (обычно "threshold")
    val refractory: Boolean,
    val resetRegKey: String?,        // если refractory=true — ключ регистра сброса (обычно "reset")
    val outRole: String = "spike_out"
)

// Описание одного упакованного под-поля в слове памяти.
data class PackedField(
    val name: String,
    val width: Int,
    val lsb: Int,            // младший бит в слове
    val msb: Int             // старший бит (включительно)
)

// Если pack != null — это «упакованная» память, где слово содержит несколько полей.
data class StaticMemPlan(
    val role: String,
    val cfg: StaticMemCfg,
    val pack: SynPackPlan? = null     // null => «однополезная» память (классический случай)
)

data class SynPackSlice(val lsb: Int, val msb: Int)


data class SynPackPlan(
    val wordWidth: Int,
    val fields: Map<String, SynPackSlice>    // <-- исправлено: вместо slices → fields
) {
    fun sliceOf(field: String): SynPackSlice =
        fields[field] ?: error("SynPackPlan: no slice for field '$field'")
}

// План синфазы хранит и имя поля, и (опционально) план упаковки.
data class SynPhasePlan(
    val op: SynOpKind,
    val gateByTick: Boolean,
    val connects: Map<String, String>,
    val synParamField: String? = null,     // напр. "w"
    val packedSlices: SynPackPlan? = null  // если null — берём целое слово памяти
)

// helper: из списка SPIKE-полей SYNAPTIC_PARAM строим упакованный формат
// вход: список синаптических параметров из Symbols (SYNAPTIC_PARAM)
fun buildSynPack(synParams: List<SpikeFieldDesc>): SynPackPlan {
    require(synParams.isNotEmpty()) { "buildSynPack: empty synParams" }


    // фиксируем детерминированный порядок упаковки
    val ordered = synParams.sortedBy { it.name }

    var offset = 0
    val map = LinkedHashMap<String, SynPackSlice>()
    for (p in ordered) {
        val lsb = offset
        val msb = offset + p.width - 1
        map[p.name] = SynPackSlice(lsb = lsb, msb = msb)
        offset += p.width
    }
    return SynPackPlan(wordWidth = offset, fields = map)
}
private fun buildRegBankFromSymbols(
    arch: SnnArch,
    symbols: Symbols,
    phases: PhasePlans,
    extraRegs: List<RegDesc> = emptyList()   // <-- НОВОЕ
): RegBankPlan {
    val regs = mutableListOf<RegDesc>()

    // 1) CONFIG-поля нейрона: STATIC + (опц.) REFRACTORY
    symbols.neuronFields.values
        .filter { it.kind == NeuronFieldKind.STATIC || it.kind == NeuronFieldKind.REFRACTORY }
        .forEach { nf ->
            regs += RegDesc(name = nf.name, width = nf.width, init = "0")
        }

    // 2) Рантайм
    regs += RegDesc("postsynCount", arch.d.postsynIdxW, init = arch.dims.postsynCount.toString())
    regs += RegDesc("baseAddr",     arch.d.weightAddrW, init = "0")

    // 3) Что реально требуют фазы (ключи — это уже ИСТИННЫЕ имена, пришедшие из IR/планов)
    val needed = buildSet {
        phases.neur.ops.mapNotNullTo(this) { it.regKey }
        add(phases.neur.postsynCountRegKey)
        add(phases.emit.cmpRegKey)
        phases.emit.resetRegKey?.let { add(it) }
    }

    // 4) Добиваем недостающие (без дублей)
    val have = regs.map { it.name }.toMutableSet()
    for (k in needed) if (k !in have) {
        val w = when (k) {
            // если в symbols есть такие поля — возьмём их реальную ширину
            in symbols.neuronFields -> symbols.neuronFields[k]!!.width
            "postsynCount" -> arch.d.postsynIdxW
            "baseAddr"     -> arch.d.weightAddrW
            else            -> arch.d.potentialW   // безопасный дефолт
        }
        regs += RegDesc(k, w, init = "0")
        have += k
    }

    // 5) Синтезированные «константные» регистры (cmpImm_*, rstImm_*)
    for (r in extraRegs) if (r.name !in have) {
        regs += r
        have += r.name
    }

    // 6) mapApiKeys: тождественная карта (никаких «threshold→Vthr» по умолчанию)
    val mapApi = regs.associate { it.name to it.name }

    return RegBankPlan(regs = regs, mapApiKeys = mapApi)
}

/* ================================================================
 *  Построение DefaultLayoutPlan
 *  — статический план «что инстанцировать и как связать»
 * ================================================================ */

fun buildLayoutPlan(
    arch: SnnArch,
    symbols: Symbols,
    ir: TxIR? = null,
    fifoDepthIn: Int = 256,
    fifoDepthOut: Int = 256,
    tickCfg: TickGenCfg = TickGenCfg(timeslot = 1, unit = TimeUnit.US, clkPeriodNs = 10),
    packAllSynParams: Boolean = true
): LayoutPlan {

    val d = arch.d
    val synParams = symbols.spikeFields.values
        .filter { it.kind == SpikeFieldKind.SYNAPTIC_PARAM }
        .sortedBy { it.name }           // фиксируем порядок, чтобы упаковка была детерминированной
    require(synParams.isNotEmpty()) { "нет SPIKE полей SYNAPTIC_PARAM — нечего читать в синфазе" }

    val insights = TxAnalyzer.analyze(
        arch = arch,
        symbols = symbols,
        ir = ir,
        packAllSynParams = packAllSynParams,
        synParams = synParams
    )

    val weightDepth = arch.dims.presynCount * arch.dims.postsynCount

    // ——— 1) Tick
    val tick = TickPlan(
        signalName = "tick",
        cfg = tickCfg
    )

    // ——— 2) FIFO In/Out
    val fifoIn = FifoInPlan(
        role = "spike_in",
        cfg = FifoCfg(
            name = "spike_in",
            dataWidth = d.spikeIdW,   // ширина идентификатора входящего пресинапса
            depth = fifoDepthIn,
            useTickDoubleBuffer = true
        )
    )
    val fifoOut = FifoOutPlan(
        role = "spike_out",
        cfg = FifoCfg(
            name = "spike_out",
            dataWidth = d.postsynIdxW, // пэйлоад спайка = индекс пост-нейрона
            depth = fifoDepthOut,
            useTickDoubleBuffer = true
        )
    )

// === 2) StaticMem: либо pack, либо по отдельности ===
    val wmems: Map<String, StaticMemPlan>
    if (insights.synPack != null) {
        val mem = StaticMemPlan(
            role = "synparams_packed",
            cfg = StaticMemCfg(
                name = "wmem_pack",
                wordWidth = insights.synPack.wordWidth,
                depth = weightDepth,
                preIdxWidth = d.presynIdxW,
                postIdxWidth = d.postsynIdxW,
                postsynCount = arch.dims.postsynCount,
                useEn = true
            ),
            pack = insights.synPack
        )
        wmems = synParams.associate { it.name to mem }  // один IF на всё
    } else {
        wmems = synParams.associate { desc ->
            desc.name to StaticMemPlan(
                role = "synparam:${desc.name}",
                cfg = StaticMemCfg(
                    name = "wmem_${desc.name}",
                    wordWidth = desc.width,
                    depth = weightDepth,
                    preIdxWidth = d.presynIdxW,
                    postIdxWidth = d.postsynIdxW,
                    postsynCount = arch.dims.postsynCount,
                    useEn = true
                ),
                pack = null
            )
        }
    }


// ——— 4) Динамика: все поля нейронной транзакции с kind = DYNAMIC
    val dynamicFields = symbols.neuronFields.values
        .filter { it.kind == NeuronFieldKind.DYNAMIC }
        .toList()
    require(dynamicFields.isNotEmpty()) { "LayoutPlan: нет нейронных полей с kind=DYNAMIC" }

// Попытка выбрать «главный» динамический массив из IR: dst в SYNAPTIC → SPACE_NEURON
//    val dynFromIr: String? = ir?.ops
//        ?.asSequence()
//        ?.filter { it.phase == TxPhase.SYNAPTIC && it.dstSpace == SPACE_NEURON }
//        ?.mapNotNull { it.dst }
//        ?.firstOrNull { dstName -> dynamicFields.any { it.name == dstName } }

// Политика выбора главного:
// 1) по IR, 2) Vmemb, 3) первый динамический
    val mainDynName = insights.mainDynamicName

    val allDynPlans = dynamicFields.map { nf ->
        DynParamPlan(
            field = nf.name,
            bitWidth = nf.width,
            count = arch.dims.postsynCount
        )
    }
    val mainDynPlan  = allDynPlans.first { it.field == mainDynName }
    val extraDynPlan = allDynPlans.filter { it.field != mainDynName }

    val dyn = DynArrays(
        main  = mainDynPlan,
        extra = extraDynPlan
    )

    // ——— 5) Банк регистров: threshold/leakage/reset/… + служебные
//    val regs = mutableListOf<RegDesc>()
//    regs += RegDesc("threshold", d.thresholdW, init = "0")
//    regs += RegDesc("leakage",   d.leakageW,   init = "1")  // в твоём примере SHR на 1 — ок положить 1
//    regs += RegDesc("reset",     d.resetW,     init = "0")
//    regs += RegDesc("postsynCount", d.postsynIdxW, init = arch.dims.postsynCount.toString())
//    regs += RegDesc("baseAddr",     d.weightAddrW, init = "0")
//    val regBank = RegBankPlan(
//        regs = regs,
//        mapApiKeys = mapOf(
//            // логические имена → реальные имена регов (если надо ремапить)
//            "threshold"     to "threshold",
//            "leakage"       to "leakage",
//            "reset"         to "reset",
//            "postsynCount"  to "postsynCount",
//            "baseAddr"      to "baseAddr"
//        )
//    )

    // ——— 6) Селектор синапсов
    val selector = SynSelPlan(
        cfg = SynSelCfg(
            name          = "sel0",
            addrWidth     = d.weightAddrW,
            preWidth      = d.presynIdxW,
            postWidth     = d.postsynIdxW,
            stepByTick    = false,       // по умолчанию бежим каждый такт; можно включить по tick
            useLinearAddr = true
        )
    )
    // === 6) Выбор рабочего параметра синфазы (как и раньше) ===
    val synParamFromIr: String? = ir?.ops
        ?.filter { it.phase == TxPhase.SYNAPTIC && it.dstSpace == SPACE_NEURON }
        ?.asSequence()
        ?.mapNotNull { op ->
            listOfNotNull(op.a, op.b).firstOrNull { o ->
                !o.isImm && o.space == SPACE_SPIKE && o.name != null
            }?.name
        }
        ?.firstOrNull()

    val preferred = synParamFromIr
        ?: (if ("w" in wmems) "w" else synParams.first().name)

    val synOp = ir?.ops
        ?.filter { it.phase == TxPhase.SYNAPTIC && it.dstSpace == SPACE_NEURON }
        ?.mapNotNull {
            when (it.opcode) {
                TxOpcode.ADD -> SynOpKind.ADD
                TxOpcode.SUB -> SynOpKind.SUB
                TxOpcode.SHL, TxOpcode.SHR -> null
                else -> null
            }
        }?.distinct()?.singleOrNull() ?: SynOpKind.ADD

// ... селектор и выбор preferred/synOp — как у тебя ...
//    val emitDer = deriveEmitFromIr(arch, symbols, ir)

// === ФАЗЫ (как у тебя сейчас) ===
    val phases = PhasePlans(
        syn = SynPhasePlan(
            op = insights.synOp,
            gateByTick = true,
            synParamField = insights.synParamPreferred,
            packedSlices = insights.synPack,
            connects = mapOf(
                "inFifo"   to "spike_in",
                "selector" to "sel0",
                "dyn"      to mainDynName
            )
        ),
        neur = NeurPhasePlan(
            ops = listOf(NeurOpSpec(NeurOpKind.SHR, regKey = "leakage")),
            postsynCountRegKey = "postsynCount"
        ),
        emit = EmitPlan(
            cmp         = insights.emitCmp,
            cmpRegKey   = insights.emitCmpRegKey,
            refractory  = insights.emitRefractory,
            resetRegKey = insights.emitResetRegKey,
            outRole     = "spike_out"
        )
    )

// === БАНК РЕГИСТРОВ: теперь строим из symbols + phases ===
    val regBank = buildRegBankFromSymbols(
        arch = arch,
        symbols = symbols,
        phases = phases,
        extraRegs = insights.extraRegs          // <— важно
    )

    return DefaultLayoutPlan(
        tick = tick,
        fifoIn = fifoIn,
        fifoOut = fifoOut,
        wmems = wmems,           // в pack-режиме все ключи указывают на один и тот же StaticMemPlan
        dyn = dyn,
        regBank = regBank,
        selector = selector,
        phases = phases,
        topology = TopologySpec(kind = arch.dims.topology)
    )

}