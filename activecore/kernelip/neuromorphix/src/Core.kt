/*
 * Neuromorphic.kt
 *     License: See LICENSE file for details
 */

package neuromorphix

import cyclix.*
import hwast.*

//private fun NmMath.log2ceil(v: Int): Int {
//    require(v > 0)
//    var n = v - 1
//    var r = 0
//    while (n > 0) { n = n shr 1; r++ }
//    return maxOf(r, 1)
//}


class Neuromorphic(
    val name: String
) : hw_astc_stdif() {

    private val g = cyclix.Generic(name)

    // tick
    private val tick = g.uglobal("tick", "0")
    private val tg   = TickGen("tg0")

    // компоненты
    private val inFifo  = FifoInput("in0")
    private val outFifo = FifoOutput("out0")
    private val dynVm   = DynamicParamMem("vmem")
    private val wIfGen  = StaticMemIfGen("wif0")

    fun build(): Generic = g

    init {
        // 1) Тик: 1 мс при 100 МГц
        tg.emit(
            g,
            TickGenCfg(timeslot = 1, unit = TimeUnit.MS, clkPeriodNs = 10),
            tick
        )

        // 2) Входной FIFO
        val fifoInIf = inFifo.emit(
            g = g,
            cfg = FifoCfg(
                name = "spike_in",
                dataWidth = 32,
                depth = 64,
                creditWidth = 8,
                useTickDoubleBuffer = true
            ),
            tick = tick
        )

        // 3) Выходной FIFO
        val fifoOutIf = outFifo.emit(
            g = g,
            cfg = FifoCfg(
                name = "spike_out",
                dataWidth = 32,
                depth = 64,
                creditWidth = 8,
                useTickDoubleBuffer = true
            ),
            tick = tick
        )

        // 4) Динамическая память (напр., Vmemb)
        val vmIf = dynVm.emit(
            g = g,
            cfg = DynParamCfg(
                name = "Vmemb",
                bitWidth = 16,
                count = 8      // TODO: model.PostsynNeuronsCount
            )
        )

        // 5) Интерфейс к статической памяти весов
        val pre  = 8   // TODO: model.PresynNeuronsCount
        val post = 8   // TODO: model.PostsynNeuronsCount
        val wIf = wIfGen.emit(
            g = g,
            cfg = StaticMemCfg(
                name = "w_l1",
                wordWidth = 8,                 // TODO: model.weightBitWidth
                depth = pre * post,
                preIdxWidth  = NmMath.log2ceil(pre),
                postIdxWidth = NmMath.log2ceil(post),
                postsynCount = post,
                useEn = true
            )
        )

        // 6) Регистр-банк (интерфейсные регистры ядра)
        val rbCfg = RegBankCfg(
            bankName = "core_cfg",
            regs = listOf(
                RegDesc("threshold",     width = 12, init = "0"),
                RegDesc("leakage",       width = 8,  init = "0"),
                RegDesc("baseAddr",      width = 32, init = "0"),
                RegDesc("postsynCount",  width = 16, init = "0"),
                RegDesc("layerBase",     width = 32, init = "0", count = 4)
            ),
            prefix = "cfg"
        )
        val regBank = RegBankGen("rb0").emit(g, rbCfg)

        // удобные ссылки
        val baseAdr     = regBank["baseAddr"]
        val postsynCnt  = regBank["postsynCount"]

        // 7) Рантайм для селектора
        val rt = RegIf(
            postsynCount = postsynCnt,
            baseAddr = baseAdr
        )

        // 8) Селектор синапсов (адресует wIf)
        val sel = SynapseSelector("sel0")
        val selIf = sel.emit(
            g     = g,
            cfg   = SynSelCfg(
                name          = "sel0",
                addrWidth     = wIf.addrWidth,
                preWidth      = NmMath.log2ceil(pre),
                postWidth     = NmMath.log2ceil(post),
                stepByTick    = false,
                useLinearAddr = true
            ),
            topo  = TopologySpec(TopologyKind.FULLY_CONNECTED),
            rt    = rt,
            tick  = tick,
            mem   = wIf
        )

        // 9) Обработчик синаптической фазы
        val syn = SynapticPhase("syn")
        // gate для этой фазы отдаст CoreFSM (ниже)
        // временно создадим плейсхолдер, потом переприсвоим через FSM:
        val synGateStub = g.uglobal("syn_gate_stub", hw_dim_static(1), "0")
        val synIf = syn.emit(
            g      = g,
            cfg    = SynPhaseCfg(
                name = "syn",
                op = SynOpKind.ADD,
                preIdxWidth = NmMath.log2ceil(pre)
            ),
            inFifo = fifoInIf,
            sel    = selIf,
            wmem   = wIf,
            dyn    = vmIf,
            gate   = synGateStub         // позже заменит CoreFSM
        )

        // 10) Нейронная фаза
        val neur = NeuronalPhase("neur")
        val neurIf = neur.emit(
            g   = g,
            cfg = NeurPhaseCfg(
                name = "neur",
                idxWidth  = NmMath.log2ceil(post),
                dataWidth = 16,
                ops = listOf(
                    NeurOpSpec(NeurOpKind.SUB, "leakage"),
                    NeurOpSpec(NeurOpKind.SHR, "threshold")
                )
            ),
            dyn  = vmIf,
            regs = regBank,
            postsynCount = postsynCnt
        )

        // 11) Эмиттер спайков
        val emit = SpikeEmitter("emit")
        val emitIf = emit.emit(
            g   = g,
            cfg = SpikeEmitCfg(
                name        = "emit",
                idxWidth    = NmMath.log2ceil(post),
                cmp         = CmpKind.GE,
                cmpRegKey   = "threshold",
                refractory  = true,
                resetRegKey = "leakage"   // либо добавь отдельный "reset"
            ),
            out  = fifoOutIf,
            dyn  = vmIf,
            regs = regBank,
            postsynCount = postsynCnt
        )

        // 12) Верхнеуровневый FSM: управляет “окном” синфазы, стартапами нейронной и эмиттера
        val coreFsm = CoreFSM("core")
        val coreIf  = coreFsm.emit(
            g     = g,
            tick  = tick,
            synIf = synIf,
            neurIf= neurIf,
            emitIf= emitIf
        )

        // Пробрасываем реальный gate в SynapticPhase (подменяем stub на сигнал FSM).
        // Т.к. в DSL мы уже сделали assign(stub, ...), просто назначим:
        synGateStub.assign(coreIf.syn_gate_o)

        // ВАЖНО: не генерируем никаких своих “kick”-импульсов здесь.
        // CoreFSM сам делает neurIf.start_i := neur_start и emitIf.start_i := emit_start.
    }
}


//
//class Neuromorphic(
//    val name: String
//) : hw_astc_stdif() {
//
//    private val g = cyclix.Generic(name)
//
//    // tick
//    private val tick = g.uglobal("tick", "0")
//    private val tg   = TickGen("tg0")
//
//    // компоненты
//    private val inFifo  = FifoInput("in0")
//    private val outFifo = FifoOutput("out0")
//    private val dynVm   = DynamicParamMem("vmem")
//    private val wIfGen  = StaticMemIfGen("wif0")
//
//    init {
//        // 0) enable ядра (порт -> регистр)
//        val en_core   = g.uport("en_core", PORT_DIR.IN, hw_dim_static(1), "0")
//        val en_core_r = g.uglobal("en_core_r", hw_dim_static(1), "0")
//        en_core_r.assign(en_core)
//
//        // 1) Тик: 1 мс при 100 МГц
//        tg.emit(g, TickGenCfg(timeslot = 1, unit = TimeUnit.MS, clkPeriodNs = 10), tick)
//
//        // 2) Входной FIFO
//        val fifoInIf = inFifo.emit(
//            g = g,
//            cfg = FifoCfg("spike_in", dataWidth = 32, depth = 64, creditWidth = 8, useTickDoubleBuffer = true),
//            tick = tick
//        )
//
//        // 3) Выходной FIFO
//        val fifoOutIf = outFifo.emit(
//            g = g,
//            cfg = FifoCfg("spike_out", dataWidth = 32, depth = 64, creditWidth = 8, useTickDoubleBuffer = true),
//            tick = tick
//        )
//
//        // 4) Динамическая память (Vmemb)
//        val vmIf = dynVm.emit(
//            g = g,
//            cfg = DynParamCfg(name = "Vmemb", bitWidth = 16, count = 8)
//        )
//
//        // 5) Интерфейс к статической памяти весов
//        val pre  = 8
//        val post = 8
//        val wIf = wIfGen.emit(
//            g = g,
//            cfg = StaticMemCfg(
//                name = "w_l1",
//                wordWidth = 8,
//                depth = pre * post,
//                preIdxWidth  = NmMath.log2ceil(pre),
//                postIdxWidth = NmMath.log2ceil(post),
//                postsynCount = post,
//                useEn = true
//            )
//        )
//
//        // 6) Регистр-банк
//        val regBank = RegBankGen("rb0").emit(
//            g, RegBankCfg(
//                bankName = "core_cfg",
//                regs = listOf(
//                    RegDesc("threshold",     width = 12, init = "0"),
//                    RegDesc("leakage",       width = 8,  init = "0"),
//                    RegDesc("baseAddr",      width = 32, init = "0"),
//                    RegDesc("postsynCount",  width = 16, init = "0"),
//                    RegDesc("layerBase",     width = 32, init = "0", count = 4)
//                ),
//                prefix = "cfg"
//            )
//        )
//        val postsynCnt = regBank["postsynCount"]
//        val baseAdr    = regBank["baseAddr"]
//
//        // 7) Рантайм для селектора
//        val rt = RegIf(postsynCount = postsynCnt, baseAddr = baseAdr)
//
//        // 8) Селектор синапсов
//        val selIf = SynapseSelector("sel0").emit(
//            g     = g,
//            cfg   = SynSelCfg(
//                name          = "sel0",
//                addrWidth     = wIf.addrWidth,
//                preWidth      = NmMath.log2ceil(pre),
//                postWidth     = NmMath.log2ceil(post),
//                stepByTick    = false,
//                useLinearAddr = true
//            ),
//            topo  = TopologySpec(TopologyKind.FULLY_CONNECTED),
//            rt    = rt,
//            tick  = tick,
//            mem   = wIf
//        )
//
//        // 9) Синфаза (операция: Vmemb += W), с gate
//        val syn_gate = g.uglobal("syn_gate", hw_dim_static(1), "0")   // управляется FSM
//        val synIf = SynapticPhase("syn").emit(
//            g      = g,
//            cfg    = SynPhaseCfg(name = "syn", op = SynOpKind.ADD, preIdxWidth = NmMath.log2ceil(pre)),
//            inFifo = fifoInIf,
//            sel    = selIf,
//            wmem   = wIf,
//            dyn    = vmIf,
//            gate   = syn_gate
//        )
//
//        // 10) Нейронная фаза
//        val neurIf = NeuronalPhase("neur").emit(
//            g   = g,
//            cfg = NeurPhaseCfg(
//                name = "neur",
//                idxWidth  = NmMath.log2ceil(post),
//                dataWidth = 16,
//                ops = listOf(
//                    NeurOpSpec(NeurOpKind.SUB, "leakage"),
//                    NeurOpSpec(NeurOpKind.SHR, "threshold")
//                )
//            ),
//            dyn  = vmIf,
//            regs = regBank,
//            postsynCount = postsynCnt
//        )
//
//        // 11) Эмиттер спайков
//        val emitIf = SpikeEmitter("emit").emit(
//            g   = g,
//            cfg = SpikeEmitCfg(
//                name       = "emit",
//                idxWidth   = NmMath.log2ceil(post),
//                cmp        = CmpKind.GE,
//                cmpRegKey  = "threshold",
//                refractory = true,
//                resetRegKey= "leakage"
//            ),
//            out  = fifoOutIf,
//            dyn  = vmIf,
//            regs = regBank,
//            postsynCount = postsynCnt
//        )
//
//        // =========================
//        // 12) FSM управления фазами
//        // =========================
//
//        val S_IDLE      = 0
//        val S_WAIT_TICK = 1
//        val S_SYN       = 2
//        val S_NEUR      = 3
//        val S_EMIT      = 4
//        val SW          = NmMath.log2ceil(5)
//
//        val fsm_st  = g.uglobal("fsm_st",  hw_dim_static(SW), "0")
//        val fsm_stN = g.uglobal("fsm_stn", hw_dim_static(SW), "0")
//        fsm_st.assign(fsm_stN)
//
//        // однотактные импульсы старта фаз
//        val neur_kick = g.uglobal("neur_kick", hw_dim_static(1), "0")
//        val emit_kick = g.uglobal("emit_kick", hw_dim_static(1), "0")
//
//        // дефолты на каждый такт
//        syn_gate.assign(0)
//        neur_kick.assign(0)
//        emit_kick.assign(0)
//
//        // подключаем импульсы к фазам
//        neurIf.start_i.assign(neur_kick)
//        emitIf.start_i.assign(emit_kick)
//
//        // IDLE: ждём en_core_r
//        g.begif(g.eq2(fsm_st, S_IDLE)); run {
//            g.begif(g.eq2(en_core_r, 1)); run {
//            fsm_stN.assign(S_WAIT_TICK)
//        }; g.endif()
//        }; g.endif()
//
//        // WAIT_TICK: первый тик -> SYN
//        g.begif(g.eq2(fsm_st, S_WAIT_TICK)); run {
//            g.begif(g.eq2(tick, 1)); run {
//            fsm_stN.assign(S_SYN)
//        }; g.endif()
//        }; g.endif()
//
//        // SYN: окно синфазы до следующего тика
//        g.begif(g.eq2(fsm_st, S_SYN)); run {
//            syn_gate.assign(1)               // разрешаем синфазе работать
//            g.begif(g.eq2(tick, 1)); run {
//            // закрываем gate на следующий такт, одновременно стартуем NEUR
//            syn_gate.assign(0)
//            neur_kick.assign(1)         // однотактный старт нейрофазы
//            fsm_stN.assign(S_NEUR)
//        }; g.endif()
//        }; g.endif()
//
//        // NEUR: ждём завершения нейрофазы
//        g.begif(g.eq2(fsm_st, S_NEUR)); run {
//            g.begif(g.eq2(neurIf.done_o, 1)); run {
//            emit_kick.assign(1)         // однотактный старт эмиттера
//            fsm_stN.assign(S_EMIT)
//        }; g.endif()
//        }; g.endif()
//
//        // EMIT: ждём завершения, затем — к следующему тиковому окну
//        g.begif(g.eq2(fsm_st, S_EMIT)); run {
//            g.begif(g.eq2(emitIf.done_o, 1)); run {
//            fsm_stN.assign(S_WAIT_TICK)
//        }; g.endif()
//        }; g.endif()
//    }
//
//    fun build(): Generic = g
//}

//
//// Main.kt




//
//
//class TickGenerator {
//    fun tick_generation(
//        tick_signal: hw_var,
//        timeslot: Int,
//        units: String,
//        clk_period: Int,
//        cyclix_gen: Generic
//    ) {  // timeslot in ms, clk_period in ns
//        // Generating Tick for timeslot processing period
//        var tick_period_val = 0
//        if (units == "ns") {
////            tick_period_val = clk_period * 1 * timeslot
//            tick_period_val = timeslot / clk_period
//            println(tick_period_val)
//        } else if (units == "us"){
////            tick_period_val = clk_period * 1000 * timeslot
//            tick_period_val = timeslot * 1000 / clk_period
//            println(tick_period_val)
//        } else if (units == "ms") {
//            tick_period_val = timeslot * 1000000 / clk_period
//            println(tick_period_val)
//        } else if (units == "s") {
//            tick_period_val = timeslot * 1000000000 / clk_period
//            println(tick_period_val)
//        }
//
//        val tick_period = cyclix_gen.uglobal("tick_period", hw_imm(timeslot))
//        val clk_counter = cyclix_gen.uglobal("clk_counter", "0")
//        val next_clk_count = cyclix_gen.uglobal("next_clk_count", "0")
//
//        tick_period.assign(tick_period_val)
//
//        cyclix_gen.begif(cyclix_gen.neq2(tick_period, clk_counter))
//        run {
//            tick_signal.assign(0)
//            next_clk_count.assign(clk_counter.plus(1))
//            clk_counter.assign(next_clk_count)
//        }; cyclix_gen.endif()
//
//        cyclix_gen.begelse()
//        run {
//            tick_signal.assign(1)
//            clk_counter.assign(0)
//        }; cyclix_gen.endif()
//    }
//}

val OP_SYN_PLUS = hwast.hw_opcode("syn_plus")


//var layers: Int = 2
//
//enum class NEURAL_NETWORK_TYPE {
//    SFNN, SCNN
//}
//
//open class SnnArch(
//    var name: String = "Default Name",
//    var nnType: NEURAL_NETWORK_TYPE = NEURAL_NETWORK_TYPE.SFNN,
//    var presyn_neurons: Int = 16,
//    var postsyn_neurons: Int = 16,
//    var outputNeur: Int = 10,
//    var weightWidth: Int = 8,
//    var potentialWidth: Int = 10,
//    var leakage: Int = 1,
//    var threshold: Int = 1,
//    var reset: Int = 0,
//    var spike_width: Int = 4,
//) {
//    fun loadModelFromJson(jsonFilePath: String) {
//        val jsonString = File(jsonFilePath).readText()
//
//        val jsonObject = JSONObject(jsonString)
//
//        val modelTopology = jsonObject.getJSONObject("model_topology")
//        this.presyn_neurons = modelTopology.optInt("input_size", this.presyn_neurons)
//        this.postsyn_neurons = modelTopology.optInt("hidden_size", this.postsyn_neurons)
//        this.outputNeur = modelTopology.optInt("output_size", this.outputNeur)
//        val lifNeurons = jsonObject.getJSONObject("LIF_neurons").getJSONObject("lif1")
//        this.threshold = lifNeurons.optInt("threshold", this.threshold)
//        this.leakage = lifNeurons.optInt("leakage", this.leakage)
//
//        val nnTypeStr = jsonObject.optString("nn_type", "SFNN")
//        this.nnType = NEURAL_NETWORK_TYPE.valueOf(nnTypeStr)
//    }
//
//    fun getArchitectureInfo(): String {
//        return "$name: (NN Type: $nnType, Presynaptic Neurons = $presyn_neurons, Postsynaptic Neurons = $postsyn_neurons)"
//    }
//}
//
//

