import hwast.DEBUG_LEVEL
import neuromorphix.*

// Main.kt
//
//fun main() {
//    val moduleName = "neuromorphic_core"
//    println("Starting $moduleName hardware generation")
//
////    // Параметры SNN (как у тебя)
////    val snn = SnnArch(
////        modelName = "LIF_demo",
////        networkType = NEURAL_NETWORK_TYPE.SFNN,
////        PresynNeuronsCount = 28*25,
////        PostsynNeuronsCount = 128,
////        weightBitWidth = 16,
////        potentialBitWidth = 16,
////        leakageMaxValue = 1,
////        thresholdValue = 1,
////        resetValue = 0,
////        ThresholdMaxValue = 15
////    )
//
//    // Сборка ядра
//    val neu = Neuromorphic(moduleName) // , snn)
//    val g   = neu.build()
//
//    // Отладочный дамп AST/IR (если используешь Activecore’овские тулзы)
////    val logsDir = "logs/"
////    HwDebugWriter("$logsDir/AST.txt").use { it.WriteExec(g.proc) }
//
//    // Экспорт в RTL
//    val hdlDir = "SystemVerilog/"
//    val rtl = g.export_to_rtl(DEBUG_LEVEL.FULL)
//    rtl.export_to_sv(hdlDir + moduleName, DEBUG_LEVEL.FULL)
//
//    println("Done. RTL at $hdlDir$moduleName")
//}


//
//fun main() {
//
//
//    // ---------- 1) Спайковая транзакция ----------
//    val spike = SpikeTx()
//    spike.addField("w",     16, SpikeFieldKind.SYNAPTIC_PARAM) // вес
//    spike.addField("delay", 8,  SpikeFieldKind.DELAY)          // задержка
//    spike.addField("tag",   8,  SpikeFieldKind.SYNAPTIC_PARAM) // метка/тег
//
//
//    spike.opAddExt(
//        dstNeuronName = "Vmemb",
//        a = SpikeOperand.neuronField("Vmemb"),
//        b = SpikeOperand.field("w")
//    )
//
//
//    // пример использования нейронного поля внутри SpikeTx:
//    // acc = acc + neuron.gain   (нейронное поле — внешний операнд)
//    // (если в нейроне будет поле "gain")
//    // spike.opAdd(
//    //     dst = "acc",
//    //     a   = SpikeOperand.field("acc"),
//    //     b   = SpikeOperand.neuronField("gain")
//    // )
//
//    // ---------- 2) Нейронная транзакция ----------
//    val neuron = NeuronTx()
//    neuron.addField("Vmemb", 16, NeuronFieldKind.DYNAMIC)         // мембранный потенциал
//    neuron.addField("Vthr",  16, NeuronFieldKind.STATIC)          // порог
//    neuron.addField("Vrst",  16, NeuronFieldKind.STATIC)          // значение сброса (если нужно как поле)
//
//    // --- G: интеграция веса (пример: Vmemb = Vmemb + w) ---
////    neuron.opAdd(dst = "Vmemb", aField = "Vmemb", bField = "w")   // w — из SpikeTx → мидлварь смэпит источник
//
//// --- F_dyn: утечка ---
//    neuron.opShrImm(dst = "Vmemb", aField = "Vmemb", shift = 1)
//
//// условие: if (Vmemb >= Vthr)
//    val cond = neuron.ifGe("Vmemb", "Vthr")
//
//// emit(if cond) + сброс Vmemb := 0
//    neuron.opEmitIf(cond, resetDst = "Vmemb", resetImm = 0)
//// или без сброса: neuron.opEmitIf(cond)
//
//    // ---------- 3) Отладочный вывод ----------
//    println("== Spike fields ==")
//    for (f in spike.listFieldsSnapshot()) {
//        println("  ${f.name} : width=${f.width}, kind=${f.kind}")
//    }
//    println("== Spike ops ==")
//    spike.dumpOps()
//
//    println("== Neuron fields ==")
//    neuron.listFields()
//    println("== Neuron ops ==")
//    neuron.dumpOps()
//
//    // 4. Построить IR и вывести
//    val ir = buildTxIR(spike, neuron)
//    ir.dump()
//
//// (опционально) JSON
//    val json = ir.toJson()
//    println(json.toString(2))   // красивый отступ 2
//
//    // 4) Построить AST и напечатать
//    val ast = buildAst(spike, neuron, name = "lif_ast")
//    ast.dump()
//
//}
//fun main() {
//    // ---------- 0) Архитектура (задай здесь нужные параметры) ----------
//    val arch = SnnArch(
//        modelName = "MyLIF",
//        nnType    = NeuralNetworkType.SFNN,
//        dims      = NnDims(presynCount = 28*28, postsynCount = 128),
//        neuron    = NeuronParams(threshold = 1, reset = 0, leakage = 1),
//        numeric   = NumericLayout(weightWidth = 16, potentialWidth = 16)
//    )
//    // arch.d содержит все DerivedWidths (presynIdxW, postsynIdxW, weightW, potentialW, ...)
//
//    // ---------- 1) Спайковая транзакция ----------
//    val spike = SpikeTx()
//    spike.addField("w",     arch.d.weightW,    SpikeFieldKind.SYNAPTIC_PARAM) // вес
//    spike.addField("delay", 8,                 SpikeFieldKind.DELAY)          // задержка (оставил фикс)
//    spike.addField("tag",   8,                 SpikeFieldKind.SYNAPTIC_PARAM) // метка/тег
//
//    spike.opAddExt(
//        dstNeuronName = "Vmemb",
//        a = SpikeOperand.neuronField("Vmemb"),
//        b = SpikeOperand.field("w")
//    )
//
//    // ---------- 2) Нейронная транзакция ----------
//    val neuron = NeuronTx()
//    neuron.addField("Vmemb", arch.d.potentialW, NeuronFieldKind.DYNAMIC) // мембранный потенциал
//    neuron.addField("Vthr",  maxOf(arch.d.thresholdW, arch.d.potentialW), NeuronFieldKind.STATIC) // порог
//    neuron.addField("Vrst",  arch.d.potentialW, NeuronFieldKind.STATIC)  // значение сброса
//
//    // --- F_dyn: утечка ---
//    neuron.opShrImm(dst = "Vmemb", aField = "Vmemb", shift = 1)
//
//    // условие: if (Vmemb >= Vthr)
//    val cond = neuron.ifGe("Vmemb", "Vthr")
//
//    // emit(if cond) + сброс Vmemb := 0
//    neuron.opEmitIf(cond, resetDst = "Vmemb", resetImm = 0)
//    // или без сброса: neuron.opEmitIf(cond)
//
//    // ---------- 3) Отладочный вывод ----------
//    println("== Spike fields ==")
//    for (f in spike.listFieldsSnapshot()) {
//        println("  ${f.name} : width=${f.width}, kind=${f.kind}")
//    }
//    println("== Spike ops ==")
//    spike.dumpOps()
//
//    println("== Neuron fields ==")
//    neuron.listFields()
//    println("== Neuron ops ==")
//    neuron.dumpOps()
//
//    // ---------- 4) IR / JSON ----------
//    val ir = buildTxIR(spike, neuron)
//    ir.dump()
//    println(ir.toJson().toString(2))
//
//    // ---------- 5) AST ----------
//    val ast = buildAst(spike, neuron, name = "lif_ast")
//    ast.dump()
//
//    // ---------- 6) Symbols (на базе arch + tx) ----------
//    val symbols = buildSymbols(arch, spike, neuron)
//    println("== Symbols ==")
//    println("SPIKE: ${symbols.spikeFields.keys}")
//    println("NEURON: ${symbols.neuronFields.keys}")
//}


/** Красивый дамп LayoutPlan с учётом множества wmem и возможной упаковки. */
fun dumpLayout(layout: LayoutPlan) {
    println("tick: ${layout.tick.signalName}  ${layout.tick.cfg}")
    println("fifoIn:  ${layout.fifoIn.role}  ${layout.fifoIn.cfg}")
    println("fifoOut: ${layout.fifoOut.role} ${layout.fifoOut.cfg}")

    // wmems: ключ — имя синаптического поля (например, "w", "tag")
    println("wmems:")
    // во избежание неоднозначности forEach с деструктуризацией — через entry
    layout.wmems.forEach { entry ->
        val field = entry.key
        val plan  = entry.value
        val cfg = plan.cfg
        val packInfo = plan.pack?.let { pack ->
            val slices = pack.fields.entries.joinToString { (fname, sl) -> "$fname[${sl.msb}:${sl.lsb}]" }
            "PACKED(wordWidth=${pack.wordWidth}): {$slices}"
        } ?: "single(wordWidth=${cfg.wordWidth})"
        println("  [$field] role=${plan.role}  name=${cfg.name}  depth=${cfg.depth}  $packInfo")
    }

    // ——— Динамические поля
    println("dyn.main: ${layout.dyn.main}  (main)")
    if (layout.dyn.extra.isNotEmpty()) {
        println("dyn.extra: ${layout.dyn.extra.joinToString()}")
    }
    println("syn uses dyn='${layout.phases.syn.connects["dyn"]}'")

    println("regBank: ${layout.regBank.regs}")
    println("selector: ${layout.selector.cfg}")

    // синфаза: покажем выбранное поле и упаковку, если есть
    val syn = layout.phases.syn
    val packed = syn.packedSlices?.let { p ->
        val sl = syn.synParamField?.let { f -> p.sliceOf(f) }
        val detail = sl?.let { "slice(${syn.synParamField})=[${it.msb}:${it.lsb}] in ${p.wordWidth}-bit word" }
        detail ?: "(packed plan present)"
    } ?: "no packing"
    println("phases.syn: op=${syn.op}, gateByTick=${syn.gateByTick}, field=${syn.synParamField}, $packed")

    println("phases.neur.ops: ${layout.phases.neur.ops}  postsynCountRegKey=${layout.phases.neur.postsynCountRegKey}")
    println("phases.emit: ${layout.phases.emit}")
    println("topology: ${layout.topology}")
}

/** Красивый дамп BindPlan (под актуальные типы правил). */
fun dumpBind(bind: BindPlan) {
    println("phaseOrder: ${bind.phaseOrder.joinToString()}")

    // synRules
    if (bind.synRules.isEmpty()) {
        println("synRules: (none)")
    } else {
        println("synRules:")
        bind.synRules.forEachIndexed { i, r ->
            println("  [$i] ir#${r.irIndex}  ${r.opcode}  synParam='${r.synParamField}'  -> dyn='${r.dynField}'")
        }
    }

    // neurRules
    if (bind.neurRules.isEmpty()) {
        println("neurRules: (none)")
    } else {
        println("neurRules:")
        bind.neurRules.forEachIndexed { i, r ->
            val reg = r.regKey ?: "-"
            println("  [$i] ir#${r.irIndex}  ${r.opcode}  dst='${r.dstDynField}'  via regKey='$reg'")
        }
    }

    // emitRule
    println(
        "emitRule: cmp=${bind.emitRule.cmp}, " +
                "cmpRegKey=${bind.emitRule.cmpRegKey}, " +
                "refractory=${bind.emitRule.refractory}, " +
                "resetRegKey=${bind.emitRule.resetRegKey}"
    )
}

fun dumpFsm(fsm: FsmPlan) {
    println("fsm.states: ${fsm.states.joinToString()}")
    if (fsm.gates.isEmpty()) println("fsm.gates: (none)") else {
        println("fsm.gates:")
        fsm.gates.forEach { (k, v) -> println("  $k -> $v") }
    }
    if (fsm.starts.isEmpty()) println("fsm.starts: (none)") else {
        println("fsm.starts:")
        fsm.starts.forEach { (k, v) -> println("  $k -> $v") }
    }
    if (fsm.waits.isEmpty()) println("fsm.waits: (none)") else {
        println("fsm.waits:")
        fsm.waits.forEach { (state, doneSig) -> println("  $state : wait $doneSig") }
    }
}

fun main() {
    // 0) Архитектура
    val arch = SnnArch(
        modelName = "LIF_demo",
        nnType    = NeuralNetworkType.SFNN,
        dims      = NnDims(presynCount = 28*28, postsynCount = 128)
    )

    // 1) Транзакции
    val spike = SpikeTx().apply {
        addField("w",     16, SpikeFieldKind.SYNAPTIC_PARAM)
        addField("delay", 8,  SpikeFieldKind.DELAY)
        addField("tag",   8,  SpikeFieldKind.SYNAPTIC_PARAM)
        opAddExt(
            dstNeuronName = "Vmemb",
            a = SpikeOperand.neuronField("Vmemb"),
            b = SpikeOperand.field("w")
        )
    }
    val neuron = NeuronTx().apply {
        addField("Vmemb",   16, NeuronFieldKind.DYNAMIC)
        addField("Vthr",    16, NeuronFieldKind.STATIC)
        addField("Vrst",    16, NeuronFieldKind.STATIC)
        addField("leakage", 16, NeuronFieldKind.STATIC)
        opShrImm(dst = "Vmemb", aField = "Vmemb", shift = 1)
        val cond = ifGe("Vmemb", "Vthr")
        opEmitIf(cond, resetDst = "Vmemb", resetImm = 0)
    }

    // 2) IR/AST
    val ir  = buildTxIR(spike, neuron)
    val ast = buildAst(spike, neuron, name = "lif_ast")

    // 3) Symbols
    val symbols = buildSymbols(arch, spike, neuron)

    // Отладка
    println(arch.info())
    println("SPIKE fields: ${symbols.spikeFields.keys}")
    println("NEURON fields: ${symbols.neuronFields.keys}")
    println("==== TxIR dump ===="); ir.dump()
    println("==== AST dump ===="); ast.dump()

    // Список синаптических полей
    val synParams = symbols.spikeFields.values
        .filter { it.kind == SpikeFieldKind.SYNAPTIC_PARAM }
        .sortedBy { it.name }

    // ---------- PACKED ----------
    val insightsPacked = TxAnalyzer.analyze(
        arch = arch, symbols = symbols, ir = ir,
        packAllSynParams = true, synParams = synParams
    )
    val layoutPacked = buildLayoutPlan(
        arch = arch, symbols = symbols, ir = ir, packAllSynParams = true
    )
    val bindPacked = TxBinder.buildBindPlan(
        ir = ir, symbols = symbols, layout = layoutPacked, insights = insightsPacked
    )
    val fsmPlanPacked = FsmPlanner.build(layoutPacked, bindPacked)
    val namingPacked = NamingPlanner.assign(
        layoutPacked, bindPacked,
        naming = Naming(
            tickName = "tick",
            regPrefix = "cfg_lif0",
            fsmName   = "lif0_fsm"
        )
    )

    println("\n==== LayoutPlan (PACKED) ====");  dumpLayout(layoutPacked)
    println("\n---- BindPlan (PACKED) ----");    dumpBind(bindPacked)
    println("\n---- FsmPlan (PACKED) ----");     dumpFsm(fsmPlanPacked)
    println("\n---- Naming (PACKED) ----");      dumpNamingNames(namingPacked)

//    // ---------- SEPARATE (опционально) ----------
//    val insightsSeparate = TxAnalyzer.analyze(
//        arch = arch, symbols = symbols, ir = ir,
//        packAllSynParams = false, synParams = synParams
//    )
//    val layoutSeparate = buildLayoutPlan(
//        arch = arch, symbols = symbols, ir = ir, packAllSynParams = false
//    )
//    val bindSeparate = TxBinder.buildBindPlan(
//        ir = ir, symbols = symbols, layout = layoutSeparate, insights = insightsSeparate
//    )
//    val fsmPlanSeparate = FsmPlanner.build(layoutSeparate, bindSeparate)
//    val namingSeparate = NamingPlanner.assign(
//        layoutSeparate, bindSeparate,
//        naming = Naming(
//            tickName = "tick",
//            regPrefix = "cfg_lif1",
//            fsmName   = "lif1_fsm"
//        )
//    )

//    println("\n==== LayoutPlan (SEPARATE MEMS) ===="); dumpLayout(layoutSeparate)
//    println("\n---- BindPlan (SEPARATE MEMS) ----");   dumpBind(bindSeparate)
//    println("\n---- FsmPlan (SEPARATE) ----");         dumpFsm(fsmPlanSeparate)
//    println("\n---- Naming (SEPARATE) ----");          dumpNamingNames(namingSeparate)

    // ---------- Генерация HDL для PACKED ----------
    val g = cyclix.Generic("nm_core_top")

    val handlesPacked = CoreAssembler.buildCore(
        g       = g,
        arch    = arch,
        layout  = layoutPacked,
        bind    = bindPacked,
        fsmPlan = fsmPlanPacked,
        // Важно: NamingPlanner.assign() возвращает AssignedNames; CoreAssembler ожидает Naming.
        // Используй базовое поле (например, .base или .naming — как у тебя названо).
        naming  = namingPacked.naming
    )

    val moduleName = "neuromorphic_core"
    val outDir = "SystemVerilog"
    println("Starting $moduleName hardware generation")
    val rtl = g.export_to_rtl(DEBUG_LEVEL.FULL)
    rtl.export_to_sv("$outDir/$moduleName", DEBUG_LEVEL.FULL)
    println("Done. RTL at $outDir/$moduleName")

    println("Core assembled. Dyn main = ${handlesPacked.dynMain.mem.name}, FIFO out = ${handlesPacked.fifoOut.rd_data_o.name}")

    // ---------- Если нужно — сборка SEPARATE ----------
    // val handlesSeparate = CoreAssembler.buildCore(
    //     g, arch, layoutSeparate, bindSeparate, fsmPlanSeparate, namingSeparate.base
    // )
}