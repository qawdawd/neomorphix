package demo

import bnmm.description.ControllerConfig
import bnmm.description.PhaseUnitConfig
import bnmm.description.MemoryBankConfig
import bnmm.description.QueueConfig
import bnmm.description.SelectorConfig
import bnmm.description.TickGenConfig
import bnmm.memory.DynamicMemoryBank
import bnmm.memory.MemoryBankPorts
import bnmm.memory.MemoryReadPort
import bnmm.memory.StaticMemoryBank
import bnmm.phase.EmissionPhaseConfig
import bnmm.phase.EmissionPhaseContext
import bnmm.phase.EmissionPhaseRuntime
import bnmm.phase.EmissionPhaseUnit
import bnmm.phase.SomaticPhaseConfig
import bnmm.phase.SomaticPhaseContext
import bnmm.phase.SomaticPhaseRuntime
import bnmm.phase.SomaticPhaseUnit
import bnmm.phase.SynapticPhaseConfig
import bnmm.phase.SynapticPhaseContext
import bnmm.phase.SynapticPhaseRuntime
import bnmm.phase.SynapticPhaseUnit
import bnmm.queue.FifoConfig
import bnmm.queue.FifoInput
import bnmm.queue.FifoOutput
import bnmm.selector.NeuronSelector
import bnmm.selector.NeuronSelectorConfig
import bnmm.selector.NeuronSelectorPlan
import bnmm.selector.NeuronSelectorPorts
import bnmm.selector.NeuronSelectorRuntime
import bnmm.selector.ReadPort
import bnmm.selector.SynapseSelector
import bnmm.selector.SynapseSelectorConfig
import bnmm.selector.SynapseSelectorPorts
import bnmm.selector.SynapticPackingConfig
import bnmm.tickgen.TickGenerator
import cyclix.Generic
import export.SystemVerilogExporter
import generation.GeneratedKernel
import hwast.hw_dim_static
import hwast.hw_imm
import hwast.hw_var

/**
 * Semi-manual assembly demo that reuses BNMM building blocks directly instead of
 * consuming transaction ASTs. The flow stitches together tick generation,
 * ingress/egress FIFOs, static and dynamic memories, selectors, the three phase
 * handlers, and a tiny FSM. Finally, it exports the constructed Cyclix design to
 * SystemVerilog using the ActiveCore backend.
 */
fun main() {
    val controllerCfg = ControllerConfig(
        name = "manual_bnmm_controller",
        selectors = listOf(SelectorConfig(name = "syn_selector", indexWidth = 4, stepByTick = true)),
        phases = listOf(
            PhaseUnitConfig(name = "syn_manual", stepByTick = true),
            PhaseUnitConfig(name = "som_manual", stepByTick = true),
            PhaseUnitConfig(name = "em_manual", stepByTick = true)
        ),
        queues = listOf(
            QueueConfig(name = "fifo_in", dataWidth = 16, depth = 8),
            QueueConfig(name = "fifo_out", dataWidth = 8, depth = 8)
        ),
        memoryBanks = listOf(
            MemoryBankConfig(name = "weights", addrWidth = 4, dataWidth = 16, depth = 16, writable = false),
            MemoryBankConfig(name = "state", addrWidth = 4, dataWidth = 12, depth = 16, writable = true)
        ),
        tickGen = TickGenConfig(name = "tick_manual", periodCycles = 4, pulseWidthCycles = 1),
        notes = listOf("semi-manual demo layout")
    )

    val demo = ManualAssembly(controllerCfg)
    val kernel = demo.buildKernel()
    val artifact = SystemVerilogExporter().export(kernel)

    println("Manual BNMM demo exported: module=${artifact.moduleName}, dir=${artifact.outputDir}")
    println("Expected top-level ports: ingress fifo_in, egress fifo_out, weight memory read-only, state memory read/write, tick and reset")
}

private data class AssemblyPorts(
    val tick: hw_var,
    val fifoIn: bnmm.queue.FifoInIF,
    val fifoOut: bnmm.queue.FifoOutIF,
    val weightMem: MemoryReadPort,
    val dynMem: MemoryBankPorts,
    val synSelector: SynapseSelectorPorts,
    val neurSelector: NeuronSelectorPorts,
    val synPhase: bnmm.phase.SynapticPhasePorts,
    val somPhase: bnmm.phase.SomaticPhasePorts,
    val emPhase: bnmm.phase.EmissionPhasePorts
)

private class ManualAssembly(private val cfg: ControllerConfig) {

    fun buildKernel(): GeneratedKernel {
        val g = Generic("bnmm_manual_demo")

        val tick = TickGenerator(cfg.tickGen?.name ?: "tick").emit(g, cfg.tickGen ?: TickGenConfig()).tick
        val fifoIn = FifoInput(cfg.queues.first().name).emit(g, cfg.queues.first().toFifoConfig(), tick)
        val fifoOut = FifoOutput(cfg.queues.last().name).emit(g, cfg.queues.last().toFifoConfig(), tick)

        val weightPorts = StaticMemoryBank("wmem").emit(g, cfg.memoryBanks.first()).readPorts.first()
        val dynPorts = DynamicMemoryBank("dmem").emit(g, cfg.memoryBanks.last())

        val selectorCfg = SynapseSelectorConfig(
            name = cfg.selectors.first().name,
            addrWidth = cfg.memoryBanks.first().addrWidth,
            preIndexWidth = cfg.selectors.first().indexWidth,
            postIndexWidth = cfg.selectors.first().indexWidth,
            packing = SynapticPackingConfig(wordWidth = cfg.memoryBanks.first().dataWidth, weightWidth = 8, weightsPerWord = 2),
            useLinearAddress = true,
            stepByTick = true
        )
        val selectorRuntime = bnmm.selector.SynapseSelectorRuntime(
            postsynCount = g.uglobal("postsyn_count", hw_dim_static(selectorCfg.postIndexWidth), "4"),
            baseAddress = g.uglobal("weight_base", hw_dim_static(selectorCfg.addrWidth), "0")
        )
        val synSelector = SynapseSelector(selectorCfg.name).emit(
            g = g,
            cfg = selectorCfg,
            runtime = selectorRuntime,
            mem = ReadPort(addr = weightPorts.addr, en = weightPorts.en, data = weightPorts.data),
            tick = tick
        )

        val neuronSelectorName = "${cfg.phases[1].name}_selector"
        val neurSelector = NeuronSelector(neuronSelectorName).emit(
            g = g,
            cfg = NeuronSelectorConfig(
                name = neuronSelectorName,
                indexWidth = selectorCfg.postIndexWidth,
                plan = NeuronSelectorPlan(groupSize = 1, totalGroups = 4, activeGroups = 4, remainder = 0),
                stepByTick = false
            ),
            runtime = NeuronSelectorRuntime(
                totalNeurons = g.uglobal("total_neurons", hw_dim_static(selectorCfg.postIndexWidth), "4"),
                baseIndex = g.uglobal("neuron_base", hw_dim_static(selectorCfg.postIndexWidth), "0"),
                tick = tick
            )
        )

        val synPhase = SynapticPhaseUnit(cfg.phases[0].name).emit(
            g = g,
            cfg = SynapticPhaseConfig(name = cfg.phases[0].name, stepByTick = true),
            runtime = SynapticPhaseRuntime(preIndex = fifoIn.rd_data_o, tick = tick),
            selector = synSelector,
            customLogic = synapticAccumulator(g, dynPorts)
        )

        val somPhase = SomaticPhaseUnit(cfg.phases[1].name).emit(
            g = g,
            cfg = SomaticPhaseConfig(name = cfg.phases[1].name, stepByTick = true),
            runtime = SomaticPhaseRuntime(tick = tick),
            selector = neurSelector,
            customLogic = somaticThreshold(g, dynPorts)
        )

        val emPhase = EmissionPhaseUnit(cfg.phases[2].name).emit(
            g = g,
            cfg = EmissionPhaseConfig(name = cfg.phases[2].name, stepByTick = true),
            runtime = EmissionPhaseRuntime(tick = tick),
            selector = neurSelector,
            outQueue = fifoOut,
            customLogic = emissionWriter(g, fifoOut)
        )

        wireController(g, synPhase, somPhase, emPhase, fifoIn)

        val ports = AssemblyPorts(
            tick = tick,
            fifoIn = fifoIn,
            fifoOut = fifoOut,
            weightMem = weightPorts,
            dynMem = dynPorts,
            synSelector = synSelector,
            neurSelector = neurSelector,
            synPhase = synPhase,
            somPhase = somPhase,
            emPhase = emPhase
        )

        exposeMemoryDefaults(g, ports)

        return GeneratedKernel(g.name, g)
    }

    private fun synapticAccumulator(g: Generic, dynMem: MemoryBankPorts): (SynapticPhaseContext) -> Unit {
        val accumulator = g.uglobal("syn_acc", hw_dim_static(16), "0")
        val dynWr = dynMem.writePorts.first()
        return { ctx ->
            g.begif(g.eq2(ctx.runStep, 1)); run {
                accumulator.assign(g.add(accumulator, ctx.selector.weight))
                dynWr.en.assign(1)
                dynWr.addr.assign(ctx.selector.postIndex)
                dynWr.data.assign(accumulator)
            }; g.endif()
        }
    }

    private fun somaticThreshold(
        g: Generic,
        dynMem: MemoryBankPorts
    ): (SomaticPhaseContext) -> Unit {
        val threshold = g.uglobal("threshold", hw_dim_static(12), "32")
        val spikeFlag = g.uglobal("spike_flag", hw_dim_static(1), "0")
        val dynRd = dynMem.readPorts.first()
        return { ctx ->
            dynRd.en?.assign(ctx.runStep)
            dynRd.addr.assign(ctx.selector.postIndex)
            g.begif(g.eq2(ctx.runStep, 1)); run {
                val above = g.gr(dynRd.data, threshold)
                spikeFlag.assign(above)
            }; g.endif()
        }
    }

    private fun emissionWriter(g: Generic, fifoOut: bnmm.queue.FifoOutIF): (EmissionPhaseContext) -> Unit {
        val tag = g.uglobal("emit_tag", hw_dim_static(8), "0")
        return { ctx ->
            g.begif(g.eq2(ctx.runStep, 1)); run {
                g.begif(g.bnot(fifoOut.full_o)); run {
                    fifoOut.we_i.assign(1)
                    tag.assign(ctx.selector.postIndex)
                    fifoOut.wr_data_i.assign(tag)
                }; g.endif()
            }; g.endif()
        }
    }

    private fun wireController(
        g: Generic,
        synPhase: bnmm.phase.SynapticPhasePorts,
        somPhase: bnmm.phase.SomaticPhasePorts,
        emPhase: bnmm.phase.EmissionPhasePorts,
        fifoIn: bnmm.queue.FifoInIF
    ) {
        val idle = 0
        val runSyn = 1
        val runSom = 2
        val runEm = 3
        val state = g.uglobal("fsm_state", hw_dim_static(2), "0")
        val stateNext = g.uglobal("fsm_state_n", hw_dim_static(2), "0")
        state.assign(stateNext)
        stateNext.assign(idle)

        synPhase.start.assign(0)
        somPhase.start.assign(0)
        emPhase.start.assign(0)
        fifoIn.rd_o.assign(0)

        g.begif(g.eq2(state, idle)); run {
            g.begif(g.bnot(fifoIn.empty_o)); run {
                fifoIn.rd_o.assign(1)
                synPhase.start.assign(1)
                stateNext.assign(runSyn)
            }; g.endif()
        }; g.endif()

        g.begif(g.eq2(state, runSyn)); run {
            g.begif(g.eq2(synPhase.done, 1)); run {
                somPhase.start.assign(1)
                stateNext.assign(runSom)
            }; g.endif()
        }; g.endif()

        g.begif(g.eq2(state, runSom)); run {
            g.begif(g.eq2(somPhase.done, 1)); run {
                emPhase.start.assign(1)
                stateNext.assign(runEm)
            }; g.endif()
        }; g.endif()

        g.begif(g.eq2(state, runEm)); run {
            g.begif(g.eq2(emPhase.done, 1)); run {
                stateNext.assign(idle)
            }; g.endif()
        }; g.endif()
    }

    private fun exposeMemoryDefaults(g: Generic, ports: AssemblyPorts) {
        ports.weightMem.en?.assign(1)
        val wr = ports.dynMem.writePorts.first()
        wr.en.assign(0)
        wr.addr.assign(hw_imm(0))
        wr.data.assign(hw_imm(0))
    }
}

private fun QueueConfig.toFifoConfig() = FifoConfig(
    name = name,
    dataWidth = dataWidth,
    depth = depth,
    creditWidth = creditWidth,
    useTickDoubleBuffer = useTickDoubleBuffer
)
