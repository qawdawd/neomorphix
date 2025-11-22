package generation

import arch.ArchWidths
import arch.SnnArch
import bnmm.description.MemoryBankConfig
import bnmm.description.TickGenConfig
import bnmm.memory.DynamicMemoryBank
import bnmm.memory.StaticMemoryBank
import bnmm.phase.EmissionPhaseConfig
import bnmm.phase.EmissionPhaseIrLogic
import bnmm.phase.EmissionPhaseRuntime
import bnmm.phase.EmissionPhaseUnit
import bnmm.phase.SomaticPhaseConfig
import bnmm.phase.SomaticPhaseIrLogic
import bnmm.phase.SomaticPhaseRuntime
import bnmm.phase.SomaticPhaseUnit
import bnmm.phase.SynapticPhaseConfig
import bnmm.phase.SynapticPhaseIrLogic
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
import bnmm.selector.SynapseSelectorRuntime
import bnmm.selector.SynapticPackingConfig
import bnmm.tickgen.TickGenerator
import control.ControlPlan
import ir.IrPhase
import ir.IrProgram
import layout.LayoutPlan
import layout.SynPackPlan
import naming.NamingPlan
import phasebinding.PhaseBindingPlan
import cyclix.Generic
import hwast.hw_dim_static
import hwast.hw_var
import kotlin.math.max

/** Representation of a synthesized Cyclix kernel ready for export. */
data class GeneratedKernel(
    val name: String,
    val generic: Generic
)

/**
 * Assembles a Cyclix kernel that mirrors the ActiveCore/CoreAsm composition. The generator
 * instantiates all BNMM building blocks according to the provided plans and returns the
 * constructed [Generic] for backend export.
 */
class CyclixGenerator {

    fun generate(
        program: IrProgram,
        layoutPlan: LayoutPlan,
        bindingPlan: PhaseBindingPlan,
        controlPlan: ControlPlan,
        namingPlan: NamingPlan
    ): GeneratedKernel {
        val g = Generic(namingPlan.kernelName)
        val widths = program.architecture.getDerivedWidths()

        val tick = buildTick(g, namingPlan, layoutPlan)
        val fifoIn = buildFifoIn(g, namingPlan, layoutPlan, tick)
        val fifoOut = buildFifoOut(g, namingPlan, layoutPlan, tick)
        val regBank = buildRegisters(g, layoutPlan, namingPlan)

        val weightMems = buildStaticMems(g, layoutPlan, namingPlan)
        val dynMems = buildDynamicMems(g, layoutPlan, namingPlan)

        val selector = buildSynSelector(g, layoutPlan, namingPlan, regBank, weightMems, tick, widths, program.architecture)
        val neuronSelector = buildNeuronSelector(g, namingPlan, widths, tick)

        val synPhase = buildSynapticPhase(g, program, layoutPlan, namingPlan, selector, fifoIn, tick)
        val somPhase = buildSomaticPhase(g, program, layoutPlan, namingPlan, neuronSelector, tick)
        val emitPhase = buildEmissionPhase(g, program, layoutPlan, namingPlan, neuronSelector, fifoOut, tick)

        connectPhaseAliases(g, namingPlan, synPhase, somPhase, emitPhase)
        buildController(g, controlPlan, namingPlan, synPhase, somPhase, emitPhase)

        dynMems // currently instantiated for completeness; future work will wire them

        return GeneratedKernel(namingPlan.kernelName, g)
    }

    private fun buildTick(g: Generic, namingPlan: NamingPlan, layoutPlan: LayoutPlan) =
        TickGenerator(namingPlan.assigned.tickInst).emit(
            g,
            TickGenConfig(
                name = namingPlan.assigned.tickInst,
                periodCycles = layoutPlan.tick.cfg.timeslot.toInt().coerceAtLeast(1),
                pulseWidthCycles = 1
            )
        ).tick

    private fun buildFifoIn(
        g: Generic,
        namingPlan: NamingPlan,
        layoutPlan: LayoutPlan,
        tick: hwast.hw_var
    ) =
        FifoInput(namingPlan.assigned.fifoInInst).emit(
            g,
            layoutPlan.fifoIn.cfg.toBnmmCfg(namingPlan.assigned.fifoInInst),
            tick
        )

    private fun buildFifoOut(
        g: Generic,
        namingPlan: NamingPlan,
        layoutPlan: LayoutPlan,
        tick: hwast.hw_var
    ) =
        FifoOutput(namingPlan.assigned.fifoOutInst).emit(
            g,
            layoutPlan.fifoOut.cfg.toBnmmCfg(namingPlan.assigned.fifoOutInst),
            tick
        )

    private fun buildRegisters(
        g: Generic,
        layoutPlan: LayoutPlan,
        namingPlan: NamingPlan
    ): Map<String, hwast.hw_var> {
        val regs = mutableMapOf<String, hwast.hw_var>()
        layoutPlan.regBank.regs.forEach { reg ->
            val rtlName = namingPlan.assigned.regApiToRtl[reg.name] ?: reg.name
            val regVar = g.uglobal(rtlName, hw_dim_static(reg.width), reg.init)
            regs[reg.name] = regVar
        }
        return regs
    }

    private fun buildStaticMems(
        g: Generic,
        layoutPlan: LayoutPlan,
        namingPlan: NamingPlan
    ): Map<String, ReadPort> {
        val ports = mutableMapOf<String, ReadPort>()
        val uniquePlans = layoutPlan.wmems.values.associateBy { it.cfg.name }
        val physIfs = uniquePlans.mapValues { (_, plan) ->
            val instName = namingPlan.assigned.wmemInsts.values.firstOrNull { it == plan.cfg.name } ?: plan.cfg.name
            val addrWidth = MemoryBankConfig.log2ceil(plan.cfg.depth)
            StaticMemoryBank(instName).emit(
                g,
                MemoryBankConfig(
                    name = plan.cfg.name,
                    addrWidth = addrWidth,
                    dataWidth = plan.cfg.wordWidth,
                    depth = plan.cfg.depth,
                    ports = 1,
                    writable = false
                )
            ).readPorts.first()
        }

        layoutPlan.wmems.forEach { (field, plan) ->
            val memPorts = physIfs.getValue(plan.cfg.name)
            ports[field] = ReadPort(addr = memPorts.addr, en = memPorts.en, data = memPorts.data)
        }
        return ports
    }

    private fun buildDynamicMems(
        g: Generic,
        layoutPlan: LayoutPlan,
        namingPlan: NamingPlan
    ): Map<String, bnmm.memory.MemoryBankPorts> {
        val mems = mutableMapOf<String, bnmm.memory.MemoryBankPorts>()
        layoutPlan.dyn.extra.forEach { extra ->
            val cfg = MemoryBankConfig(
                name = namingPlan.assigned.dynExtraInsts[extra.field] ?: extra.field,
                addrWidth = MemoryBankConfig.log2ceil(extra.count),
                dataWidth = extra.bitWidth,
                depth = extra.count,
                writable = true
            )
            mems[extra.field] = DynamicMemoryBank(cfg.name).emit(g, cfg)
        }

        val mainCfg = MemoryBankConfig(
            name = namingPlan.assigned.dynMainInst,
            addrWidth = MemoryBankConfig.log2ceil(layoutPlan.dyn.main.count),
            dataWidth = layoutPlan.dyn.main.bitWidth,
            depth = layoutPlan.dyn.main.count,
            writable = true
        )
        mems[layoutPlan.dyn.main.field] = DynamicMemoryBank(mainCfg.name).emit(g, mainCfg)
        return mems
    }

    private fun buildSynSelector(
        g: Generic,
        layoutPlan: LayoutPlan,
        namingPlan: NamingPlan,
        regs: Map<String, hwast.hw_var>,
        wmems: Map<String, ReadPort>,
        tick: hwast.hw_var,
        widths: ArchWidths,
        arch: SnnArch
    ) =
        SynapseSelector(namingPlan.assigned.selectorInst).emit(
            g = g,
            cfg = layoutPlan.toSynSelectorCfg(namingPlan, arch),
            runtime = SynapseSelectorRuntime(
                postsynCount = regs["postsynCount"] ?: g.uglobal("postsynCount", hw_dim_static(widths.neuronGlobalIdWidth), "0"),
                baseAddress = regs["baseAddr"]
            ),
            mem = wmems.getValue(layoutPlan.phases.syn.synParamField ?: layoutPlan.wmems.keys.first()),
            tick = if (layoutPlan.selector.cfg.stepByTick) tick else null
        )

    private fun buildNeuronSelector(
        g: Generic,
        namingPlan: NamingPlan,
        widths: ArchWidths,
        tick: hwast.hw_var
    ): NeuronSelectorPorts {
        val plan = NeuronSelectorPlan(groupSize = 1, totalGroups = widths.totalNeuronCount, activeGroups = widths.totalNeuronCount, remainder = 0)
        return NeuronSelector(namingPlan.phaseNames.getValue(IrPhase.SOMATIC)).emit(
            g = g,
            cfg = NeuronSelectorConfig(
                name = namingPlan.phaseNames.getValue(IrPhase.SOMATIC),
                indexWidth = widths.neuronGlobalIdWidth,
                plan = plan,
                stepByTick = false
            ),
            runtime = NeuronSelectorRuntime(
                totalNeurons = g.uglobal("${namingPlan.phaseNames.getValue(IrPhase.SOMATIC)}_count", hw_dim_static(widths.neuronGlobalIdWidth), widths.totalNeuronCount.toString()),
                tick = tick
            )
        )
    }

    private fun buildSynapticPhase(
        g: Generic,
        program: IrProgram,
        layoutPlan: LayoutPlan,
        namingPlan: NamingPlan,
        selector: bnmm.selector.SynapseSelectorPorts,
        fifoIn: bnmm.queue.FifoInIF,
        tick: hwast.hw_var
    ) =
        SynapticPhaseUnit(namingPlan.assigned.synInst).emit(
            g = g,
            cfg = SynapticPhaseConfig(name = namingPlan.assigned.synInst, stepByTick = layoutPlan.phases.syn.gateByTick),
            runtime = SynapticPhaseRuntime(preIndex = fifoIn.rd_data_o, tick = tick),
            selector = selector,
            irLogic = program.phaseBlockOrNull(IrPhase.SYNAPTIC)?.let { SynapticPhaseIrLogic(it.body, program.symbols) },
            bindings = mapOfNotNull(layoutPlan.phases.syn.synParamField to selector.weight)
        ).also {
            fifoIn.rd_o.assign(it.busy)
        }

    private fun buildSomaticPhase(
        g: Generic,
        program: IrProgram,
        layoutPlan: LayoutPlan,
        namingPlan: NamingPlan,
        selector: NeuronSelectorPorts,
        tick: hwast.hw_var
    ) =
        SomaticPhaseUnit(namingPlan.assigned.neurInst).emit(
            g = g,
            cfg = SomaticPhaseConfig(name = namingPlan.assigned.neurInst, stepByTick = false),
            runtime = SomaticPhaseRuntime(tick = tick),
            selector = selector,
            irLogic = program.phaseBlockOrNull(IrPhase.SOMATIC)?.let { SomaticPhaseIrLogic(it.body, program.symbols) }
        )

    private fun buildEmissionPhase(
        g: Generic,
        program: IrProgram,
        layoutPlan: LayoutPlan,
        namingPlan: NamingPlan,
        selector: NeuronSelectorPorts,
        fifoOut: bnmm.queue.FifoOutIF,
        tick: hwast.hw_var
    ) =
        EmissionPhaseUnit(namingPlan.assigned.emitInst).emit(
            g = g,
            cfg = EmissionPhaseConfig(name = namingPlan.assigned.emitInst, stepByTick = layoutPlan.phases.emit.refractory),
            runtime = EmissionPhaseRuntime(tick = tick),
            selector = selector,
            outQueue = fifoOut,
            irLogic = program.phaseBlockOrNull(IrPhase.EMISSION)?.let { EmissionPhaseIrLogic(it.body, program.symbols) }
        )

    private fun connectPhaseAliases(
        g: Generic,
        namingPlan: NamingPlan,
        synPhase: bnmm.phase.SynapticPhasePorts,
        somPhase: bnmm.phase.SomaticPhasePorts,
        emitPhase: bnmm.phase.EmissionPhasePorts
    ) {
        fun aliasPort(name: String, dir: hwast.PORT_DIR, source: hwast.hw_var) {
            val port = g.uport(name, dir, source.vartype.dimensions, "0")
            when (dir) {
                hwast.PORT_DIR.IN -> source.assign(port)
                hwast.PORT_DIR.OUT -> port.assign(source)
                hwast.PORT_DIR.INOUT -> {}
            }
        }

        namingPlan.assigned.synPorts["start_i"]?.let { aliasPort(it, hwast.PORT_DIR.IN, synPhase.start) }
        namingPlan.assigned.synPorts["done_o"]?.let { aliasPort(it, hwast.PORT_DIR.OUT, synPhase.done) }
        namingPlan.assigned.synPorts["busy_o"]?.let { aliasPort(it, hwast.PORT_DIR.OUT, synPhase.busy) }

        namingPlan.assigned.neurPorts["start_i"]?.let { aliasPort(it, hwast.PORT_DIR.IN, somPhase.start) }
        namingPlan.assigned.neurPorts["done_o"]?.let { aliasPort(it, hwast.PORT_DIR.OUT, somPhase.done) }
        namingPlan.assigned.neurPorts["busy_o"]?.let { aliasPort(it, hwast.PORT_DIR.OUT, somPhase.busy) }

        namingPlan.assigned.emitPorts["start_i"]?.let { aliasPort(it, hwast.PORT_DIR.IN, emitPhase.start) }
        namingPlan.assigned.emitPorts["done_o"]?.let { aliasPort(it, hwast.PORT_DIR.OUT, emitPhase.done) }
        namingPlan.assigned.emitPorts["busy_o"]?.let { aliasPort(it, hwast.PORT_DIR.OUT, emitPhase.busy) }
    }

    private fun buildController(
        g: Generic,
        controlPlan: ControlPlan,
        namingPlan: NamingPlan,
        synPhase: bnmm.phase.SynapticPhasePorts,
        somPhase: bnmm.phase.SomaticPhasePorts,
        emitPhase: bnmm.phase.EmissionPhasePorts
    ) {
        val stateCount = controlPlan.states.size
        val stateWidth = max(1, log2ceil(stateCount))
        val state = g.uglobal("${namingPlan.assigned.fsmInst}_state", hw_dim_static(stateWidth), "0")
        val stateNext = g.uglobal("${namingPlan.assigned.fsmInst}_state_n", hw_dim_static(stateWidth), "0")
        state.assign(stateNext)

        val encoding = controlPlan.states.mapIndexed { idx, st -> st.name to idx }.toMap()
        val idleIdx = encoding["idle"] ?: 0
        stateNext.assign(idleIdx)

        synPhase.start.assign(0)
        somPhase.start.assign(0)
        emitPhase.start.assign(0)

        fun stateEq(idx: Int) = g.eq2(state, idx)

        controlPlan.phaseOrder.forEach { phase ->
            val stateName = "run_${phase.name.lowercase()}"
            val currentIdx = encoding[stateName] ?: return@forEach
            val nextIdx = encoding[controlPlan.transitions.firstOrNull { it.from == stateName }?.to] ?: encoding["complete"] ?: idleIdx
            val phasePorts = when (phase) {
                IrPhase.SYNAPTIC -> synPhase
                IrPhase.SOMATIC -> somPhase
                IrPhase.EMISSION, IrPhase.REFRACTORY -> emitPhase
            }

            g.begif(stateEq(currentIdx)); run {
                phasePorts.start.assign(1)
                g.begif(g.eq2(phasePorts.done, 1)); run { stateNext.assign(nextIdx) }; g.endif()
            }; g.endif()
        }

        encoding["complete"]?.let { completeIdx ->
            g.begif(stateEq(completeIdx)); run { stateNext.assign(idleIdx) }; g.endif()
        }
    }

    private fun LayoutPlan.toSynSelectorCfg(
        namingPlan: NamingPlan,
        arch: SnnArch
    ): SynapseSelectorConfig {
        val synField = phases.syn.synParamField ?: wmems.keys.first()
        val packPlan: SynPackPlan? = phases.syn.packedSlices
        val weightWidth = packPlan?.sliceOf(synField)?.let { it.msb - it.lsb + 1 } ?: wmems[synField]?.cfg?.wordWidth
            ?: arch.staticParameters.firstOrNull()?.bitWidth ?: 1
        val wordWidth = packPlan?.wordWidth ?: wmems[synField]?.cfg?.wordWidth ?: weightWidth
        val packing = SynapticPackingConfig(
            wordWidth = wordWidth,
            weightWidth = weightWidth,
            weightsPerWord = if (packPlan != null) wordWidth / weightWidth else 1
        )

        return SynapseSelectorConfig(
            name = namingPlan.assigned.selectorInst,
            addrWidth = selector.cfg.addrWidth,
            preIndexWidth = selector.cfg.preWidth,
            postIndexWidth = selector.cfg.postWidth,
            packing = packing,
            useLinearAddress = selector.cfg.useLinearAddr,
            stepByTick = selector.cfg.stepByTick
        )
    }

    private fun log2ceil(value: Int): Int {
        require(value > 0) { "Value must be positive" }
        var v = value - 1
        var r = 0
        while (v > 0) {
            v = v shr 1
            r++
        }
        return max(r, 1)
    }

    private fun mapOfNotNull(key: String?, value: hwast.hw_var?): Map<String, hwast.hw_var> =
        if (key != null && value != null) mapOf(key to value) else emptyMap()

    private fun ir.IrProgram.phaseBlockOrNull(phase: IrPhase) = phases.firstOrNull { it.phase == phase }

    private fun layout.FifoCfg.toBnmmCfg(nameOverride: String) = FifoConfig(
        name = nameOverride,
        dataWidth = dataWidth,
        depth = depth,
        creditWidth = creditWidth,
        useTickDoubleBuffer = useTickDoubleBuffer
    )
}
