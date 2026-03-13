package demo

import arch.ConnectivityType
import arch.SnnArch
import arch.StaticParamDescriptor
import bnmm.description.ControllerConfig
import bnmm.description.MemoryBankConfig
import bnmm.description.QueueConfig
import bnmm.description.TickGenConfig
import bnmm.memory.DynamicMemoryBank
import bnmm.memory.RegisterBankAdapter
import bnmm.memory.StaticMemoryBank
import bnmm.queue.FifoConfig
import bnmm.queue.FifoInput
import bnmm.queue.FifoOutput
import bnmm.tickgen.TickGenerator
import cyclix.Generic
import export.SystemVerilogExporter
import generation.GeneratedKernel
import hwast.PORT_DIR
import hwast.hw_dim_static
import hwast.hw_imm
import layout.TimeUnit
import kotlin.math.max

/**
 * Монолитный (без фаз/селекторов) каркас ядра с теми же внешними портами,
 * что у текущей manual-сборки.
 */
fun main() {
    val arch = SnnArch(
        layerCount = 2,
        neuronsPerLayer = listOf(16, 16),
        connectivity = ConnectivityType.FULLY_CONNECTED,
        staticParameters = listOf(
            StaticParamDescriptor(name = "leakage", bitWidth = 8),
            StaticParamDescriptor(name = "threshold", bitWidth = 12),
            StaticParamDescriptor(name = "vreset", bitWidth = 12)
        )
    )

    val widths = arch.getDerivedWidths()
    val regs = arch.staticParameterDescriptors().map { it.name } + listOf("weight_base", "neuron_base", "postsyn_count", "emit_tag")

    val cfg = ControllerConfig(
        name = "monolith_bnmm_controller",
        selectors = emptyList(),
        phases = emptyList(),
        queues = listOf(
            QueueConfig(name = "fifo_in", dataWidth = 32, depth = arch.neuronsPerLayer.first()),
            QueueConfig(name = "fifo_out", dataWidth = 32, depth = arch.neuronsPerLayer.last())
        ),
        memoryBanks = listOf(
            MemoryBankConfig(
                name = "weights",
                addrWidth = widths.synapseAddressWidth,
                dataWidth = 32,
                depth = widths.totalSynapseCount,
                writable = false,
                external = true
            ),
            MemoryBankConfig(
                name = "state",
                addrWidth = bitWidthForCount(arch.neuronsPerLayer.last()),
                dataWidth = 12,
                depth = arch.neuronsPerLayer.last(),
                writable = true
            ),
            MemoryBankConfig(
                name = "regs",
                addrWidth = bitWidthForCount(regs.size),
                dataWidth = 16,
                depth = regs.size,
                writable = true,
                registerAdapter = true
            )
        ),
        tickGen = TickGenConfig(
            name = "tick_manual",
            periodCycles = 4,
            pulseWidthCycles = 1,
            period = 100_000,
            periodUnit = TimeUnit.NS,
            clockPeriodNs = 10
        )
    )

    val kernel = MonolithicCoreSkeleton(cfg, arch, regs).buildKernel()
    val artifact = SystemVerilogExporter().export(kernel)
    println("Monolithic skeleton exported: module=${artifact.moduleName}, dir=${artifact.outputDir}")
}


private fun bitWidthForCount(count: Int): Int {
    require(count > 0) { "Count must be > 0" }
    var v = count - 1
    var bits = 0
    while (v > 0) {
        bits++
        v = v shr 1
    }
    return max(1, bits)
}
private class MonolithicCoreSkeleton(
    private val cfg: ControllerConfig,
    private val arch: SnnArch,
    private val registerNames: List<String>
) {

    fun buildKernel(): GeneratedKernel {
        val g = Generic("bnmm_monolithic_skeleton")
        val widths = arch.getDerivedWidths()

        val enLif = g.uport("en_lif", PORT_DIR.IN, hw_dim_static(1), "0")
        val emissionDone = g.uport("emission_done", PORT_DIR.OUT, hw_dim_static(1), "0")

        val tick = TickGenerator(cfg.tickGen?.name ?: "tick_manual").emit(g, cfg.tickGen ?: TickGenConfig()).tick
        val fifoIn = FifoInput(cfg.queues.first().name).emit(g, cfg.queues.first().toFifoConfig(), tick)
        val fifoOut = FifoOutput(cfg.queues.last().name).emit(g, cfg.queues.last().toFifoConfig(), tick)

        val weightMem = StaticMemoryBank("wmem").emit(g, cfg.memoryBanks.first()).readPorts.first()
        val dynMem = DynamicMemoryBank("dmem").emit(g, cfg.memoryBanks[1])

        val regCfg = cfg.memoryBanks.first { it.registerAdapter }
        val regMap = RegisterBankAdapter("reg").build(
            g = g,
            cfg = regCfg,
            registerNames = registerNames,
            initialValues = mapOf("postsyn_count" to arch.neuronsPerLayer.last())
        )

        val leakage = regMap.getValue("leakage")
        val threshold = regMap.getValue("threshold")
        val vreset = regMap.getValue("vreset")
        val weightBase = regMap.getValue("weight_base")
        val postsynCount = regMap.getValue("postsyn_count")
        val emitTag = regMap.getValue("emit_tag")

        val preWidth = widths.neuronIndexWidths.first()
        val postWidth = widths.neuronIndexWidths.last()

        val S_IDLE = 0
        val S_SYN_REQ = 1
        val S_SYN_RESP = 2
        val S_SYN_DRAIN = 3
        val S_SOM = 4
        val S_EMIT = 5

        val state = g.uglobal("mono_state", hw_dim_static(3), "0")
        val stateN = g.uglobal("mono_state_n", hw_dim_static(3), "0")
        state.assign(stateN)
        stateN.assign(state)

        val preIdx = g.uglobal("mono_pre_idx", hw_dim_static(preWidth), "0")
        val postIdx = g.uglobal("mono_post_idx", hw_dim_static(postWidth), "0")

        val reqValid_d1 = g.uglobal("mono_req_v_d1", hw_dim_static(1), "0")
        val reqValid_d1_n = g.uglobal("mono_req_v_d1_n", hw_dim_static(1), "0")
        reqValid_d1.assign(reqValid_d1_n)

        val postIdx_d1 = g.uglobal("mono_post_d1", hw_dim_static(postWidth), "0")
        val postIdx_d1_n = g.uglobal("mono_post_d1_n", hw_dim_static(postWidth), "0")
        postIdx_d1.assign(postIdx_d1_n)

        val lane_d1 = g.uglobal("mono_lane_d1", hw_dim_static(1), "0")
        val lane_d1_n = g.uglobal("mono_lane_d1_n", hw_dim_static(1), "0")
        lane_d1.assign(lane_d1_n)

        val weight_d1 = g.uglobal("mono_weight_d1", hw_dim_static(16), "0")
        val weight_d1_n = g.uglobal("mono_weight_d1_n", hw_dim_static(16), "0")
        weight_d1.assign(weight_d1_n)

        val reqValid_d2 = g.uglobal("mono_req_v_d2", hw_dim_static(1), "0")
        val reqValid_d2_n = g.uglobal("mono_req_v_d2_n", hw_dim_static(1), "0")
        reqValid_d2.assign(reqValid_d2_n)

        val postIdx_d2 = g.uglobal("mono_post_d2", hw_dim_static(postWidth), "0")
        val postIdx_d2_n = g.uglobal("mono_post_d2_n", hw_dim_static(postWidth), "0")
        postIdx_d2.assign(postIdx_d2_n)

        val lastRespPending = g.uglobal("mono_last_pending", hw_dim_static(1), "0")
        val lastRespPendingN = g.uglobal("mono_last_pending_n", hw_dim_static(1), "0")
        lastRespPending.assign(lastRespPendingN)
        lastRespPendingN.assign(lastRespPending)

        val drainCtr = g.uglobal("mono_drain_ctr", hw_dim_static(2), "0")
        val drainCtrN = g.uglobal("mono_drain_ctr_n", hw_dim_static(2), "0")
        drainCtr.assign(drainCtrN)
        drainCtrN.assign(drainCtr)

        val doneReg = g.uglobal("mono_done_r", hw_dim_static(1), "0")
        val doneRegN = g.uglobal("mono_done_n", hw_dim_static(1), "0")
        doneReg.assign(doneRegN)
        doneRegN.assign(hw_imm(0))
        emissionDone.assign(doneReg)

        val dynRd = dynMem.readPorts.first()
        val dynWr = dynMem.writePorts.first()

        // defaults
        fifoIn.rd_o.assign(hw_imm(0))
        fifoOut.we_i.assign(hw_imm(0))
        fifoOut.wr_data_i.assign(hw_imm(0))

        weightMem.en.assign(hw_imm(0))
        weightMem.addr.assign(hw_imm(0))
        weightMem.we?.assign(hw_imm(0))

        dynRd.en.assign(hw_imm(0))
        dynRd.addr.assign(hw_imm(0))
        dynWr.en.assign(hw_imm(0))
        dynWr.addr.assign(hw_imm(0))
        dynWr.data.assign(hw_imm(0))

        reqValid_d1_n.assign(hw_imm(0))
        reqValid_d2_n.assign(hw_imm(0))
        postIdx_d1_n.assign(postIdx_d1)
        postIdx_d2_n.assign(postIdx_d2)
        lane_d1_n.assign(lane_d1)
        weight_d1_n.assign(weight_d1)

        val synBase = g.mul(preIdx, postsynCount)

        g.begif(g.eq2(state, hw_imm(S_IDLE))); run {
            postIdx.assign(hw_imm(0))
            g.begif(g.land(g.eq2(enLif, hw_imm(1)), g.bnot(fifoIn.empty_o))); run {
                fifoIn.rd_o.assign(hw_imm(1))
                preIdx.assign(fifoIn.rd_data_o[preWidth - 1, 0])
                stateN.assign(hw_imm(S_SYN_REQ))
            }; g.endif()
        }; g.endif()

        g.begif(g.eq2(state, hw_imm(S_SYN_REQ))); run {
            val fullIndex = g.add(synBase, postIdx)
            val packedWordAddr = g.srl(g.add(fullIndex, weightBase), hw_imm(1))
            val lane = g.band(g.add(fullIndex, weightBase), hw_imm(1))
            val byteAddr = g.sll(packedWordAddr, hw_imm(2)) // 32-bit words

            weightMem.en.assign(hw_imm(1))
            weightMem.addr.assign(byteAddr)

            reqValid_d1_n.assign(hw_imm(1))
            postIdx_d1_n.assign(postIdx)
            lane_d1_n.assign(lane)

            val last = g.eq2(postIdx, g.sub(postsynCount, hw_imm(1)))
            g.begif(last); run {
                lastRespPendingN.assign(hw_imm(1))
            }; g.endif()
            g.begelse(); run {
                postIdx.assign(postIdx.plus(1))
            }; g.endif()

            stateN.assign(hw_imm(S_SYN_RESP))
        }; g.endif()

        g.begif(g.eq2(state, hw_imm(S_SYN_RESP))); run {
            dynRd.en.assign(reqValid_d1)
            dynRd.addr.assign(postIdx_d1)
            reqValid_d2_n.assign(reqValid_d1)
            postIdx_d2_n.assign(postIdx_d1)

            g.begif(g.eq2(reqValid_d1, hw_imm(1))); run {
                g.begif(g.eq2(lane_d1, hw_imm(0))); run {
                    weight_d1_n.assign(weightMem.data[15, 0])
                }; g.endif()
                g.begelse(); run {
                    weight_d1_n.assign(weightMem.data[31, 16])
                }; g.endif()
            }; g.endif()

            dynWr.en.assign(reqValid_d2)
            dynWr.addr.assign(postIdx_d2)
            dynWr.data.assign(g.add(dynRd.data, weight_d1))

            g.begif(g.eq2(lastRespPending, hw_imm(1))); run {
                lastRespPendingN.assign(hw_imm(0))
                drainCtrN.assign(hw_imm(2))
                stateN.assign(hw_imm(S_SYN_DRAIN))
            }; g.endif()
            g.begelse(); run {
                stateN.assign(hw_imm(S_SYN_REQ))
            }; g.endif()
        }; g.endif()

        g.begif(g.eq2(state, hw_imm(S_SYN_DRAIN))); run {
            dynWr.en.assign(reqValid_d2)
            dynWr.addr.assign(postIdx_d2)
            dynWr.data.assign(g.add(dynRd.data, weight_d1))
            reqValid_d2_n.assign(hw_imm(0))

            g.begif(g.eq2(drainCtr, hw_imm(0))); run {
                postIdx.assign(hw_imm(0))
                stateN.assign(hw_imm(S_SOM))
            }; g.endif()
            g.begelse(); run {
                drainCtrN.assign(g.sub(drainCtr, hw_imm(1)))
            }; g.endif()
        }; g.endif()

        g.begif(g.eq2(state, hw_imm(S_SOM))); run {
            dynRd.en.assign(hw_imm(1))
            dynRd.addr.assign(postIdx)
            dynWr.en.assign(hw_imm(1))
            dynWr.addr.assign(postIdx)
            dynWr.data.assign(g.srl(dynRd.data, leakage[7, 0]))

            val last = g.eq2(postIdx, g.sub(postsynCount, hw_imm(1)))
            g.begif(last); run {
                postIdx.assign(hw_imm(0))
                stateN.assign(hw_imm(S_EMIT))
            }; g.endif()
            g.begelse(); run {
                postIdx.assign(postIdx.plus(1))
            }; g.endif()
        }; g.endif()

        g.begif(g.eq2(state, hw_imm(S_EMIT))); run {
            dynRd.en.assign(hw_imm(1))
            dynRd.addr.assign(postIdx)

            val spike = g.geq(dynRd.data, threshold)
            g.begif(g.land(spike, g.bnot(fifoOut.full_o))); run {
                fifoOut.we_i.assign(hw_imm(1))
                fifoOut.wr_data_i.assign(g.add(emitTag[7, 0], postIdx))
                dynWr.en.assign(hw_imm(1))
                dynWr.addr.assign(postIdx)
                dynWr.data.assign(vreset)
            }; g.endif()

            val last = g.eq2(postIdx, g.sub(postsynCount, hw_imm(1)))
            g.begif(last); run {
                doneRegN.assign(hw_imm(1))
                stateN.assign(hw_imm(S_IDLE))
                postIdx.assign(hw_imm(0))
            }; g.endif()
            g.begelse(); run {
                postIdx.assign(postIdx.plus(1))
            }; g.endif()
        }; g.endif()

        return GeneratedKernel(name = g.name, generic = g)
    }

    private fun QueueConfig.toFifoConfig(): FifoConfig = FifoConfig(
        name = name,
        dataWidth = dataWidth,
        depth = depth,
        creditWidth = max(1, bitWidthForCount(depth))
    )
}
