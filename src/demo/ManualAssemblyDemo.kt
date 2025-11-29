package demo

import arch.ConnectivityType
import arch.SnnArch
import arch.StaticParamDescriptor
import bnmm.description.ControllerConfig
import bnmm.description.PhaseUnitConfig
import bnmm.description.MemoryBankConfig
import bnmm.description.QueueConfig
import bnmm.description.SelectorConfig
import bnmm.description.TickGenConfig
import layout.TimeUnit
import bnmm.memory.DynamicMemoryBank
import bnmm.memory.MemoryBankPorts
import bnmm.memory.MemoryReadPort
import bnmm.memory.RegisterBankAdapter
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
import hwast.PORT_DIR
import hwast.hw_dim_static
import hwast.hw_var
import kotlin.math.max
import kotlin.math.min

/**
 * Semi-manual assembly demo that reuses BNMM building blocks directly instead of
 * consuming transaction ASTs. The flow stitches together tick generation,
 * ingress/egress FIFOs, static and dynamic memories, selectors, the three phase
 * handlers, and a tiny FSM. Finally, it exports the constructed Cyclix design to
 * SystemVerilog using the ActiveCore backend.
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
    val archWidths = arch.getDerivedWidths()
    val presynCount = arch.neuronsPerLayer.first()
    val postsynCount = arch.neuronsPerLayer.last()
    val preIndexWidth = archWidths.neuronIndexWidths.first()
    val postIndexWidth = archWidths.neuronIndexWidths.last()
    val registerNames = arch.staticParameterDescriptors().map { it.name } +
        listOf("weight_base", "neuron_base", "postsyn_count", "emit_tag")

    val controllerCfg = ControllerConfig(
        name = "manual_bnmm_controller",
        selectors = listOf(
            SelectorConfig(
                name = "syn_selector",
                indexWidth = max(preIndexWidth, postIndexWidth),
                stepByTick = true
            )
        ),
        phases = listOf(
            PhaseUnitConfig(name = "syn_manual", stepByTick = true),
            PhaseUnitConfig(name = "som_manual", stepByTick = true),
            PhaseUnitConfig(name = "em_manual", stepByTick = true)
        ),
        queues = listOf(
            QueueConfig(name = "fifo_in", dataWidth = 32, depth = presynCount),
            QueueConfig(name = "fifo_out", dataWidth = 32, depth = postsynCount)
        ),
        memoryBanks = listOf(
            MemoryBankConfig(
                name = "weights",
                addrWidth = archWidths.synapseAddressWidth,
                dataWidth = 32,
                depth = archWidths.totalSynapseCount,
                writable = false,
                external = true
            ),
            MemoryBankConfig(
                name = "state",
                addrWidth = bitWidthForCount(postsynCount),
                dataWidth = 12,
                depth = postsynCount,
                writable = true
            ),
            MemoryBankConfig(
                name = "regs",
                addrWidth = bitWidthForCount(registerNames.size),
                dataWidth = 16,
                depth = registerNames.size,
                writable = true,
                registerAdapter = true,
                notes = listOf("static parameter registers")
            )
        ),
        tickGen = TickGenConfig(
            name = "tick_manual",
            periodCycles = 4,
            pulseWidthCycles = 1,
            period = 100_000,
            periodUnit = TimeUnit.NS,
            clockPeriodNs = 10
        ),
        notes = listOf("semi-manual demo layout")
    )

    val demo = ManualAssembly(controllerCfg, arch, registerNames)
    val kernel = demo.buildKernel()
    val artifact = SystemVerilogExporter().export(kernel)

    println("Manual BNMM demo exported: module=${artifact.moduleName}, dir=${artifact.outputDir}")
    println("Synapse and neuron selectors rely on the shared postsyn_count register for post-synaptic iteration")
    println("Expected top-level ports: ingress fifo_in, egress fifo_out, weight memory read-only, state memory read/write, tick and reset")
}

private data class AssemblyPorts(
    val tick: hw_var,
    val enLif: hw_var,
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

private data class RegisterSet(
    val leakage: hw_var,
    val threshold: hw_var,
    val vreset: hw_var,
    val weightBase: hw_var,
    val neuronBase: hw_var,
    val postsynCount: hw_var,
    val emitTag: hw_var
)

private class ManualAssembly(
    private val cfg: ControllerConfig,
    private val arch: SnnArch,
    private val registerNames: List<String>
) {

    fun buildKernel(): GeneratedKernel {
        val g = Generic("bnmm_manual_demo")

        logModuleParameters()

        val enLif = g.uport("en_lif", PORT_DIR.IN, hw_dim_static(1), "0")
        val tick = TickGenerator(cfg.tickGen?.name ?: "tick").emit(g, cfg.tickGen ?: TickGenConfig()).tick
        val fifoIn = FifoInput(cfg.queues.first().name).emit(g, cfg.queues.first().toFifoConfig(), tick)
        val fifoOut = FifoOutput(cfg.queues.last().name).emit(g, cfg.queues.last().toFifoConfig(), tick)

        val weightPorts = StaticMemoryBank("wmem").emit(g, cfg.memoryBanks.first()).readPorts.first()
        val dynPorts = DynamicMemoryBank("dmem").emit(g, cfg.memoryBanks[1])
        val regCfg = cfg.memoryBanks.first { it.registerAdapter }
        val regMap = RegisterBankAdapter("reg").build(g, regCfg, registerNames)
        val regs = RegisterSet(
            leakage = regMap.getValue("leakage"),
            threshold = regMap.getValue("threshold"),
            vreset = regMap.getValue("vreset"),
            weightBase = regMap.getValue("weight_base"),
            neuronBase = regMap.getValue("neuron_base"),
            postsynCount = regMap.getValue("postsyn_count"),
            emitTag = regMap.getValue("emit_tag")
        )

        val selectorAddrWidth = min(cfg.memoryBanks.first().addrWidth, regCfg.dataWidth)
        val archWidths = arch.getDerivedWidths()
        val preIndexWidth = archWidths.neuronIndexWidths.first()
        val postIndexWidth = archWidths.neuronIndexWidths.last()
        val selectorCfg = SynapseSelectorConfig(
            name = cfg.selectors.first().name,
            addrWidth = selectorAddrWidth,
            preIndexWidth = preIndexWidth,
            postIndexWidth = postIndexWidth,
//            packing = SynapticPackingConfig(wordWidth = cfg.memoryBanks.first().dataWidth, weightWidth = 16, weightsPerWord = 2),
            packing = SynapticPackingConfig(wordWidth = 32, weightWidth = 16, weightsPerWord = 2),
            useLinearAddress = true,
            stepByTick = true
        )
        // Keep selector ranges consistent by sourcing both selectors from one register.
        val sharedPostCount = regs.postsynCount[postIndexWidth - 1, 0]
        val weightBaseWidth = regs.weightBase.vartype.dimensions.first().GetWidth()
        val selectorRuntime = bnmm.selector.SynapseSelectorRuntime(
            postsynCount = sharedPostCount,
            baseAddress = regs.weightBase[min(weightBaseWidth, selectorCfg.addrWidth) - 1, 0]
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
                plan = NeuronSelectorPlan(
                    groupSize = 1,
                    totalGroups = arch.neuronsPerLayer.last(),
                    activeGroups = arch.neuronsPerLayer.last(),
                    remainder = 0
                ),
                stepByTick = false
            ),
            runtime = NeuronSelectorRuntime(
                totalNeurons = sharedPostCount,
                baseIndex = regs.neuronBase[selectorCfg.postIndexWidth - 1, 0],
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
            customLogic = somaticThreshold(g, dynPorts, regs)
        )

        val emPhase = EmissionPhaseUnit(cfg.phases[2].name).emit(
            g = g,
            cfg = EmissionPhaseConfig(name = cfg.phases[2].name, stepByTick = true),
            runtime = EmissionPhaseRuntime(tick = tick),
            selector = neurSelector,
            outQueue = fifoOut,
            customLogic = emissionWriter(g, fifoOut, regs)
        )

        wireController(g, synPhase, somPhase, emPhase, fifoIn, enLif)

        val ports = AssemblyPorts(
            tick = tick,
            enLif = enLif,
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

        return GeneratedKernel(g.name, g)
    }

    private fun logModuleParameters() {
        val archWidths = arch.getDerivedWidths()
        println("=== Manual BNMM generation parameters ===")
        println(arch.describe())
        println(cfg.describe(registerNames))

        val selectorCfg = cfg.selectors.firstOrNull()
        val weightCfg = cfg.memoryBanks.firstOrNull()
        if (selectorCfg != null && weightCfg != null) {
//            val packing = SynapticPackingConfig(wordWidth = weightCfg.dataWidth, weightWidth = 16, weightsPerWord = 2)
            val packing = SynapticPackingConfig(wordWidth = 32, weightWidth = 16, weightsPerWord = 2)
            println("Synapse selector: ${selectorCfg.describe()}")
            println("  packing: ${packing.describe()}")
            println("  weight memory: ${weightCfg.describe()}")
            println("  runtime base address register: weight_base (${cfg.memoryBanks.first { it.registerAdapter }.dataWidth}b)")
        }

        val neuronPlanTotal = arch.neuronsPerLayer.last()
        println("Neuron selector:")
        println("  ${cfg.phases.getOrNull(1)?.name ?: "neuron"}_selector -> postCountRegister=postsyn_count (${bitWidthForCount(neuronPlanTotal)}b used=${archWidths.neuronIndexWidths.last()}b) baseRegister=neuron_base")

        val dynMem = cfg.memoryBanks.getOrNull(1)
        if (dynMem != null) {
            println("Dynamic state memory: ${dynMem.describe()}")
        }

        val regCfg = cfg.memoryBanks.firstOrNull { it.registerAdapter }
        if (regCfg != null) {
            println("Register set (static params and runtime controls):")
            arch.staticParameterDescriptors().forEach { param ->
                println("  ${param.name}: ${param.bitWidth}b (stored in ${regCfg.dataWidth}b slot)")
            }
            println("  weight_base / neuron_base / postsyn_count / emit_tag: stored in ${regCfg.dataWidth}b slots")
            println("  register bank depth=${regCfg.depth} addrWidth=${regCfg.addrWidth}")
        }

        println("------------------------------------------------------------")
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
        dynMem: MemoryBankPorts,
        regs: RegisterSet
    ): (SomaticPhaseContext) -> Unit {
        val spikeFlag = g.uglobal("spike_flag", hw_dim_static(1), "0")
        val dynRd = dynMem.readPorts.first()
        val dynWr = dynMem.writePorts.first()
        return { ctx ->
            dynRd.en?.assign(ctx.runStep)
            dynRd.addr.assign(ctx.selector.postIndex)
            dynWr.en.assign(0)
            g.begif(g.eq2(ctx.runStep, 1)); run {
                val above = g.gr(dynRd.data, regs.threshold[11, 0])
                spikeFlag.assign(above)
                g.begif(above); run {
                    dynWr.en.assign(1)
                    dynWr.addr.assign(ctx.selector.postIndex)
                    dynWr.data.assign(regs.vreset[11, 0])
                }; g.endif()
            }; g.endif()
        }
    }

    private fun emissionWriter(g: Generic, fifoOut: bnmm.queue.FifoOutIF, regs: RegisterSet): (EmissionPhaseContext) -> Unit {
        return { ctx ->
            g.begif(g.eq2(ctx.runStep, 1)); run {
                g.begif(g.bnot(fifoOut.full_o)); run {
                    fifoOut.we_i.assign(1)
                    fifoOut.wr_data_i.assign(g.add(regs.emitTag[7, 0], ctx.selector.postIndex))
                }; g.endif()
            }; g.endif()
        }
    }

    private fun wireController(
        g: Generic,
        synPhase: bnmm.phase.SynapticPhasePorts,
        somPhase: bnmm.phase.SomaticPhasePorts,
        emPhase: bnmm.phase.EmissionPhasePorts,
        fifoIn: bnmm.queue.FifoInIF,
        enLif: hw_var
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
            g.begif(g.eq2(enLif, 1)); run {
                g.begif(g.bnot(fifoIn.empty_o)); run {
                    fifoIn.rd_o.assign(1)
                    synPhase.start.assign(1)
                    stateNext.assign(runSyn)
                }; g.endif()
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

}

private fun QueueConfig.toFifoConfig() = FifoConfig(
    name = name,
    dataWidth = dataWidth,
    depth = depth,
    creditWidth = creditWidth,
    useTickDoubleBuffer = useTickDoubleBuffer
)

private fun bitWidthForCount(count: Int): Int {
    require(count >= 0) { "Count must not be negative" }
    if (count <= 1) return 1
    var value = count - 1
    var width = 0
    while (value > 0) {
        width += 1
        value = value ushr 1
    }
    return width
}
