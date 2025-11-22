package bnmm.phase

import bnmm.selector.*
import cyclix.Generic
import hwast.PORT_DIR
import hwast.DEBUG_LEVEL
import hwast.hw_dim_static
import ir.IrBlock
import ir.IrValue.Symbol
import transaction.OpCode
import transaction.TxField
import transaction.TxFieldType
import symbols.SymbolTable

/**
 * Небольшой пример ручного использования селектора и обработчика синаптической
 * фазы. Демонстрирует:
 * 1) подключение селектора к памяти с упаковкой весов;
 * 2) каркас управления с start/busy/done;
 * 3) пользовательскую логику на каждом шаге (простое накопление суммы весов).
 */
fun main() {
    val g = Generic("synaptic_phase_demo")

    val packing = SynapticPackingConfig(wordWidth = 32, weightWidth = 16, weightsPerWord = 2)
    val selCfg = SynapseSelectorConfig(
        name = "demo_sel",
        addrWidth = 12,
        preIndexWidth = 8,
        postIndexWidth = 8,
        packing = packing,
        useLinearAddress = true,
        stepByTick = false
    )

    val postsynCount = g.uglobal("postsyn_count", hw_dim_static(selCfg.postIndexWidth), "0")
    val baseAddr = g.uglobal("weights_base", hw_dim_static(selCfg.addrWidth), "0")

    val mem = ReadPortFactory.create(
        g = g,
        name = "wmem",
        addrWidth = selCfg.addrWidth,
        dataWidth = packing.wordWidth,
        useEnable = true
    )

    val selector = SynapseSelector("demo")
    val selectorPorts = selector.emit(
        g = g,
        cfg = selCfg,
        runtime = SynapseSelectorRuntime(postsynCount = postsynCount, baseAddress = baseAddr),
        mem = mem,
        tick = null
    )

    val phaseCfg = SynapticPhaseConfig(name = "demo_phase")
    val phase = SynapticPhaseUnit()

    // Регистр для демонстрации пользовательской арифметики
    val acc = g.uglobal("acc_weight_sum", hw_dim_static(packing.weightWidth + 8), "0")

    val phasePorts = phase.emit(
        g = g,
        cfg = phaseCfg,
        runtime = SynapticPhaseRuntime(preIndex = selectorPorts.preIndex),
        selector = selectorPorts,
        customLogic = {
            // исполняем шаг, только если runStep=1
            g.begif(g.eq2(it.runStep, 1)); run {
            acc.assign(acc.plus(it.selector.weight))
        }; g.endif()
        }
    )

    // ---------------- Автоматическая логика из IR ----------------
    val symbolTable = SymbolTable().apply {
        registerTransactionFields(
            txId = "syn_demo",
            kind = null,
            fields = listOf(
                TxField("weight", packing.weightWidth, TxFieldType.SYNAPTIC_PARAM),
                TxField("acc_auto", packing.weightWidth + 8, TxFieldType.DYNAMIC)
            )
        )
    }

    val weightEntry = requireNotNull(symbolTable.resolveField("weight"))
    val accAutoEntry = requireNotNull(symbolTable.resolveField("acc_auto"))

    val irBlock = IrBlock(
        listOf(
            // acc_auto := acc_auto + weight
            ir.IrOperation(
                opcode = OpCode.ADD,
                target = accAutoEntry,
                operands = listOf(Symbol(accAutoEntry), Symbol(weightEntry))
            )
        )
    )

    val autoPhase = SynapticPhaseUnit(instName = "syn_phase_ir")
    val autoPorts = autoPhase.emit(
        g = g,
        cfg = SynapticPhaseConfig(name = "demo_phase_ir", enableCustomLogic = false),
        runtime = SynapticPhaseRuntime(preIndex = selectorPorts.preIndex),
        selector = selectorPorts,
        irLogic = SynapticPhaseIrLogic(block = irBlock, symbols = symbolTable),
        bindings = mapOf("weight" to selectorPorts.weight)
    )

    // Порты для внешнего строба запуска и наблюдения состояния
    val startPort = g.uport("start_phase", PORT_DIR.IN, hw_dim_static(1), "0")
    phasePorts.start.assign(startPort)

    g.uport("busy_phase", PORT_DIR.OUT, hw_dim_static(1), "0").assign(phasePorts.busy)
    g.uport("done_phase", PORT_DIR.OUT, hw_dim_static(1), "0").assign(phasePorts.done)

    val startIr = g.uport("start_phase_ir", PORT_DIR.IN, hw_dim_static(1), "0")
    autoPorts.start.assign(startIr)
    g.uport("busy_phase_ir", PORT_DIR.OUT, hw_dim_static(1), "0").assign(autoPorts.busy)
    g.uport("done_phase_ir", PORT_DIR.OUT, hw_dim_static(1), "0").assign(autoPorts.done)

    val rtl = g.export_to_rtl(DEBUG_LEVEL.FULL)
    rtl.export_to_sv("out/${g.name}", DEBUG_LEVEL.FULL)
}
