package bnmm.phase

import bnmm.selector.NeuronSelector
import bnmm.selector.NeuronSelectorConfig
import bnmm.selector.NeuronSelectorPlan
import bnmm.selector.NeuronSelectorRuntime
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
 * Демонстрация обработчика рефрактерной фазы с двумя вариантами логики:
 * 1) Пользовательская: накопление суммы индексов постсинаптических нейронов.
 * 2) Автоматическая из IR: acc_auto := acc_auto + post_idx (post_idx привязан через bindings).
 */
fun main() {
    val g = Generic("refractory_phase_demo")

    val plan = NeuronSelectorPlan(groupSize = 2, totalGroups = 4, activeGroups = 4, remainder = 0)
    val selCfg = NeuronSelectorConfig(name = "ref_sel", indexWidth = 8, plan = plan, stepByTick = false)

    val totalNeurons = g.uglobal("postsyn_total", hw_dim_static(selCfg.indexWidth), "0")
    val baseIndex = g.uglobal("postsyn_base", hw_dim_static(selCfg.indexWidth), "0")

    // ------------- Пользовательская логика -------------
    val selectorManual = NeuronSelector("demo_ref_manual")
    val selManualPorts = selectorManual.emit(
        g = g,
        cfg = selCfg,
        runtime = NeuronSelectorRuntime(totalNeurons = totalNeurons, baseIndex = baseIndex, tick = null)
    )

    val phaseCfg = RefractoryPhaseConfig(name = "ref_phase_demo")
    val phase = RefractoryPhaseUnit()
    val acc = g.uglobal("acc_manual", hw_dim_static(selCfg.indexWidth + 8), "0")

    val phasePorts = phase.emit(
        g = g,
        cfg = phaseCfg,
        runtime = RefractoryPhaseRuntime(),
        selector = selManualPorts,
        customLogic = {
            g.begif(g.eq2(it.runStep, 1)); run {
            acc.assign(acc.plus(it.selector.postIndex))
        }; g.endif()
        }
    )

    // ------------- Автоматическая логика из IR -------------
    val symbolTable = SymbolTable().apply {
        registerTransactionFields(
            txId = "ref_demo",
            kind = null,
            fields = listOf(
                TxField("post_idx", selCfg.indexWidth, TxFieldType.DYNAMIC),
                TxField("acc_auto", selCfg.indexWidth + 8, TxFieldType.DYNAMIC)
            )
        )
    }

    val postEntry = requireNotNull(symbolTable.resolveField("post_idx"))
    val accAutoEntry = requireNotNull(symbolTable.resolveField("acc_auto"))

    val irBlock = IrBlock(
        listOf(
            ir.IrOperation(
                opcode = OpCode.ADD,
                target = accAutoEntry,
                operands = listOf(Symbol(accAutoEntry), Symbol(postEntry))
            )
        )
    )

    val selectorAuto = NeuronSelector("demo_ref_auto")
    val selAutoPorts = selectorAuto.emit(
        g = g,
        cfg = selCfg.copy(name = "ref_sel_auto"),
        runtime = NeuronSelectorRuntime(totalNeurons = totalNeurons, baseIndex = baseIndex, tick = null)
    )

    val autoPhase = RefractoryPhaseUnit(instName = "ref_phase_ir")
    val autoPorts = autoPhase.emit(
        g = g,
        cfg = RefractoryPhaseConfig(name = "ref_phase_ir", enableCustomLogic = false),
        runtime = RefractoryPhaseRuntime(),
        selector = selAutoPorts,
        irLogic = RefractoryPhaseIrLogic(block = irBlock, symbols = symbolTable),
        bindings = mapOf("post_idx" to selAutoPorts.postIndex)
    )

    // Порты для внешнего строба запуска и наблюдения состояния
    val startManual = g.uport("start_ref_manual", PORT_DIR.IN, hw_dim_static(1), "0")
    phasePorts.start.assign(startManual)
    g.uport("busy_ref_manual", PORT_DIR.OUT, hw_dim_static(1), "0").assign(phasePorts.busy)
    g.uport("done_ref_manual", PORT_DIR.OUT, hw_dim_static(1), "0").assign(phasePorts.done)

    val startAuto = g.uport("start_ref_auto", PORT_DIR.IN, hw_dim_static(1), "0")
    autoPorts.start.assign(startAuto)
    g.uport("busy_ref_auto", PORT_DIR.OUT, hw_dim_static(1), "0").assign(autoPorts.busy)
    g.uport("done_ref_auto", PORT_DIR.OUT, hw_dim_static(1), "0").assign(autoPorts.done)

    val rtl = g.export_to_rtl(DEBUG_LEVEL.FULL)
    rtl.export_to_sv("out/${g.name}", DEBUG_LEVEL.FULL)
}
