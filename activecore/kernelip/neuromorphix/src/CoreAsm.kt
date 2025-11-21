package neuromorphix

import cyclix.Generic
import hwast.*
import neuromorphix.NmMath

/** Набор ссылок на реально созданные IF-компоненты ядра. */
data class CoreHandles(
    val tick: hw_var,
    val fifoIn: FifoInIF,
    val fifoOut: FifoOutIF,
    val wmemIfs: Map<String, StaticMemIF>,
    val selector: SynSelIF,
    val dynMain: DynParamIF,
    val regBank: RegBankIF,
    val syn: SynPhaseIF,
    val neur: NeurPhaseIF,
    val emit: SpikeEmitIF,
    val fsm: CoreFsmIF
)

/**
 * Реальная сборка ядра:
 *  - создаёт все компоненты по LayoutPlan
 *  - делает логические соединения
 *  - использует BindPlan/FsmPlan/Naming, где это нужно
 */
object CoreAssembler {

    fun buildCore(
        g: Generic,
        arch: SnnArch,
        layout: LayoutPlan,
        bind: BindPlan,
        fsmPlan: FsmPlan,
        naming: Naming
    ): CoreHandles {

        // 0) Tick
        val tick = g.uglobal(naming.tickName, hw_dim_static(1), "0")
        TickGen(naming.tickName).emit(g, layout.tick.cfg, tick)

        // 1) FIFO in/out
        val fifoIn  = FifoInput(layout.fifoIn.cfg.name).emit(g, layout.fifoIn.cfg, tick)
        val fifoOut = FifoOutput(layout.fifoOut.cfg.name).emit(g, layout.fifoOut.cfg, tick)

        // 2) Статическая память(и) под синпараметры
//    (в PACKED один физический инстанс для нескольких полей; в SEPARATE — по одному на поле)
        val wmemIfByName = LinkedHashMap<String, StaticMemIF>()

// Сначала создаём по одному StaticMemIF на КАЖДОЕ уникальное имя физической памяти
        for (plan in layout.wmems.values) {
            val physName = plan.cfg.name
            if (!wmemIfByName.containsKey(physName)) {
                val ifc = StaticMemIfGen(physName).emit(g, plan.cfg)
                wmemIfByName[physName] = ifc
            }
        }

// Затем строим карту field -> StaticMemIF, отдавая один и тот же IF для всех полей,
// которые указывают на один физический банк (избегает повторного добавления портов)
        val wmemIfs: Map<String, StaticMemIF> =
            layout.wmems.mapValues { (_, plan) -> wmemIfByName.getValue(plan.cfg.name) }

        // 3) Динамика: главный массив (Vmemb и т.п.)
        val dynMain = DynamicParamMem("dyn_${layout.dyn.main.field}")
            .emit(g, DynParamCfg(
                name = layout.dyn.main.field,
                bitWidth = layout.dyn.main.bitWidth,
                count = layout.dyn.main.count,
                initZero = true
            ))

        // 4) Банк регистров
        val regBank = RegBankGen("reg_bank").emit(
            g,
            RegBankCfg(
                bankName = "cfg_bank",
                regs = layout.regBank.regs,
                prefix = naming.regPrefix
            )
        )
        // Быстрый доступ к postsynCount/baseAddr
        val postsynCount = regBank["postsynCount"]
        val baseAddr     = layout.regBank.regs.firstOrNull { it.name == "baseAddr" }?.let { regBank["baseAddr"] }

        // 5) Селектор синапсов
        //    Привязываем к «рабочему» wmem по выбранному полю синфазы (или к первому попавшемуся)
        val synParamField = layout.phases.syn.synParamField ?: layout.wmems.keys.first()
        val wmemForSyn    = wmemIfs[synParamField]
            ?: error("CoreAssembler: no wmem IF for synParamField='$synParamField'")

        val sel = SynapseSelector(layout.selector.cfg.name).emit(
            g = g,
            cfg = layout.selector.cfg,
            topo = layout.topology,
            rt = RegIf(postsynCount = postsynCount, baseAddr = baseAddr),
            tick = if (layout.selector.cfg.stepByTick) tick else null,
            mem = wmemForSyn
        )

        // 6) Синфаза
        val synIf = SynapticPhase("syn").emit(
            g = g,
            cfg = SynPhaseCfg(
                name = naming.synName,
                op = layout.phases.syn.op,
                preIdxWidth = arch.d.presynIdxW,
                synParamField = layout.phases.syn.synParamField,
                packedSlices  = layout.phases.syn.packedSlices
            ),
            inFifo = fifoIn,
            sel = sel,
            wmem = wmemForSyn,
            dyn = dynMain,
            gate = if (layout.phases.syn.gateByTick) tick else null  // можно заменить на fsm.syn_gate_o, если хочешь именно от ФСМ
        )

        // 7) Нейронная фаза
        val neurIf = NeuronalPhase(naming.neurName).emit(
            g = g,
            cfg = NeurPhaseCfg(
                name = naming.neurName,
                idxWidth = arch.d.postsynIdxW,
                dataWidth = layout.dyn.main.bitWidth,
                ops = layout.phases.neur.ops   // у тебя форматы совпадают
            ),
            dyn = dynMain,
            regs = regBank,
            postsynCount = postsynCount
        )

        // 8) Эмиттер
        val emitIf = SpikeEmitter(naming.emitName).emit(
            g = g,
            cfg = SpikeEmitCfg(
                name = naming.emitName,
                idxWidth = arch.d.postsynIdxW,
                cmp = layout.phases.emit.cmp,
                cmpRegKey = layout.phases.emit.cmpRegKey,
                refractory = layout.phases.emit.refractory,
                resetRegKey = layout.phases.emit.resetRegKey
            ),
            out = fifoOut,
            dyn = dynMain,
            regs = regBank,
            postsynCount = postsynCount
        )

        // 9) Верхний FSM
        val fsmIf = CoreFSM(naming.fsmName).emit(
            g = g,
            tick = tick,
            synIf = synIf,
            neurIf = neurIf,
            emitIf = emitIf
        )

        return CoreHandles(
            tick = tick,
            fifoIn = fifoIn,
            fifoOut = fifoOut,
            wmemIfs = wmemIfs,
            selector = sel,
            dynMain = dynMain,
            regBank = regBank,
            syn = synIf,
            neur = neurIf,
            emit = emitIf,
            fsm = fsmIf
        )
    }
}