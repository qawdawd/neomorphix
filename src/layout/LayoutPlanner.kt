package layout

import arch.ConnectivityType
import ir.IrAssignment
import ir.IrConditional
import ir.IrLoop
import ir.IrOperation
import ir.IrPhase
import ir.IrProgram
import semantics.*
import symbols.SymbolEntry
import transaction.ComparisonOp
import transaction.OpCode
import transaction.TxFieldType

interface LayoutPlan {
    val tick: TickPlan
    val fifoIn: FifoInPlan
    val fifoOut: FifoOutPlan
    val wmems: Map<String, StaticMemPlan>
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

// ——— tick generator
enum class TimeUnit { NS, US, MS, S }
data class TickGenCfg(val timeslot: Long, val unit: TimeUnit, val clkPeriodNs: Long)
data class TickPlan(val signalName: String, val cfg: TickGenCfg)

// ——— FIFO configuration
data class FifoCfg(
    val name: String,
    val dataWidth: Int,
    val depth: Int,
    val creditWidth: Int = 8,
    val useTickDoubleBuffer: Boolean = true
)
data class FifoInPlan(val role: String, val cfg: FifoCfg)
data class FifoOutPlan(val role: String, val cfg: FifoCfg)

// ——— static memories (weights)
data class StaticMemCfg(
    val name: String,
    val wordWidth: Int,
    val depth: Int,
    val preIdxWidth: Int,
    val postIdxWidth: Int,
    val postsynCount: Int,
    val useEn: Boolean = true
)
data class SynPackSlice(val lsb: Int, val msb: Int)
data class SynPackPlan(val wordWidth: Int, val fields: Map<String, SynPackSlice>) {
    fun sliceOf(field: String): SynPackSlice =
        fields[field] ?: error("SynPackPlan: no slice for field '$field'")
}
data class StaticMemPlan(val role: String, val cfg: StaticMemCfg, val pack: SynPackPlan? = null)

// ——— dynamic neuron state arrays
data class DynParamPlan(val field: String, val bitWidth: Int, val count: Int)
data class DynArrays(val main: DynParamPlan, val extra: List<DynParamPlan> = emptyList())

// ——— register bank
data class RegDesc(val name: String, val width: Int, val init: String = "0")
data class RegBankPlan(val regs: List<RegDesc>, val mapApiKeys: Map<String, String> = emptyMap())

// ——— selector
data class SynSelCfg(
    val name: String,
    val addrWidth: Int,
    val preWidth: Int,
    val postWidth: Int,
    val stepByTick: Boolean = false,
    val useLinearAddr: Boolean = true
)
data class SynSelPlan(val cfg: SynSelCfg)

// ——— phases
enum class SynOpKind { ADD, SUB, SHL, SHR }
data class SynPhasePlan(
    val op: SynOpKind,
    val gateByTick: Boolean,
    val connects: Map<String, String>,
    val synParamField: String? = null,
    val packedSlices: SynPackPlan? = null
)

enum class NeurOpKind { ADD, SUB, SHL, SHR }
data class NeurOpSpec(val kind: NeurOpKind, val regKey: String? = null)
data class NeurPhasePlan(val ops: List<NeurOpSpec>, val postsynCountRegKey: String)

enum class CmpKind { GT, LT, GE, LE }
data class EmitPlan(
    val cmp: CmpKind,
    val cmpRegKey: String,
    val refractory: Boolean,
    val resetRegKey: String?,
    val outRole: String
)

data class PhasePlans(
    val syn: SynPhasePlan,
    val neur: NeurPhasePlan,
    val emit: EmitPlan
)

// ——— topology
enum class TopologyKind { FULLY_CONNECTED, RECURRENT, SPARSE, CONV }
data class TopologySpec(val kind: TopologyKind = TopologyKind.FULLY_CONNECTED)

/**
 * Builds [LayoutPlan] objects by combining semantic plans with architecture metadata
 * from the IR program. The planner keeps calculations simple and deterministic
 * so that downstream passes can trust the resulting layout when synthesising hardware.
 */
class LayoutPlanner(private val program: IrProgram) {

    fun buildPlan(
        packingPlan: SynapticPackingPlan,
        pipelinePlan: SynapticPipelinePlan,
        somaticPlan: SomaticParallelPlan,
        emissionPlan: EmissionParallelPlan,
        refractoryPlan: RefractoryParallelPlan
    ): LayoutPlan {
        val arch = program.architecture
        val widths = arch.getDerivedWidths()

        val tick = TickPlan("tick", TickGenCfg(timeslot = 1, unit = TimeUnit.US, clkPeriodNs = 10))

        val fifoIn = FifoInPlan(
            role = "spike_in",
            cfg = FifoCfg(
                name = "spike_in",
                dataWidth = widths.neuronGlobalIdWidth,
                depth = 256,
                useTickDoubleBuffer = true
            )
        )
        val fifoOut = FifoOutPlan(
            role = "spike_out",
            cfg = FifoCfg(
                name = "spike_out",
                dataWidth = widths.neuronGlobalIdWidth,
                depth = 256,
                useTickDoubleBuffer = true
            )
        )

        val synapticParams = program.symbols.allFields()
            .filter { it.type == TxFieldType.SYNAPTIC_PARAM }
            .sortedBy { it.name }
        val wmems = buildWmems(widths.totalSynapseCount, widths.neuronGlobalIdWidth, synapticParams, packingPlan)

        val dynamicFields = program.symbols.allFields().filter { it.type == TxFieldType.DYNAMIC }
        require(dynamicFields.isNotEmpty()) { "LayoutPlan: no dynamic neuron fields found" }
        val mainDynName = pickMainDynamic(dynamicFields, program)
        val allDynPlans = dynamicFields.map { nf ->
            DynParamPlan(field = nf.name, bitWidth = nf.bitWidth, count = widths.totalNeuronCount)
        }
        val dyn = DynArrays(
            main = allDynPlans.first { it.field == mainDynName },
            extra = allDynPlans.filter { it.field != mainDynName }
        )

        val regBank = buildRegBank(widths.neuronGlobalIdWidth, widths.synapseAddressWidth)

        val selector = SynSelPlan(
            cfg = SynSelCfg(
                name = "selector0",
                addrWidth = widths.synapseAddressWidth,
                preWidth = widths.neuronGlobalIdWidth,
                postWidth = widths.neuronGlobalIdWidth,
                stepByTick = pipelinePlan.enabled,
                useLinearAddr = true
            )
        )

        val phases = buildPhasePlans(
            fifoIn,
            fifoOut,
            dyn,
            synapticParams,
            packingPlan,
            pipelinePlan,
            emissionPlan,
            refractoryPlan
        )

        val topology = TopologySpec(
            kind = when (arch.connectivity) {
                ConnectivityType.FULLY_CONNECTED -> TopologyKind.FULLY_CONNECTED
                else -> TopologyKind.SPARSE
            }
        )

        return DefaultLayoutPlan(
            tick = tick,
            fifoIn = fifoIn,
            fifoOut = fifoOut,
            wmems = wmems,
            dyn = dyn,
            regBank = regBank,
            selector = selector,
            phases = phases,
            topology = topology
        )
    }

    private fun buildWmems(
        depth: Int,
        indexWidth: Int,
        synapticParams: List<SymbolEntry>,
        packingPlan: SynapticPackingPlan
    ): Map<String, StaticMemPlan> {
        if (packingPlan.enabled && packingPlan.mode == PackingMode.PACKED && packingPlan.assignments.isNotEmpty()) {
            val packedSlices = packingPlan.assignments.associate { assignment ->
                assignment.symbol.name to SynPackSlice(
                    lsb = assignment.bitOffset,
                    msb = assignment.bitOffset + assignment.bitWidth - 1
                )
            }
            val pack = SynPackPlan(packingPlan.wordWidth, packedSlices)
            val mem = StaticMemPlan(
                role = "synparams_packed",
                cfg = StaticMemCfg(
                    name = "wmem_pack",
                    wordWidth = packingPlan.wordWidth,
                    depth = depth,
                    preIdxWidth = indexWidth,
                    postIdxWidth = indexWidth,
                    postsynCount = depth
                ),
                pack = pack
            )
            return synapticParams.associate { it.name to mem }
        }

        return synapticParams.associate { desc ->
            desc.name to StaticMemPlan(
                role = "synparam:${desc.name}",
                cfg = StaticMemCfg(
                    name = "wmem_${desc.name}",
                    wordWidth = desc.bitWidth,
                    depth = depth,
                    preIdxWidth = indexWidth,
                    postIdxWidth = indexWidth,
                    postsynCount = depth
                )
            )
        }
    }

    private fun pickMainDynamic(dynamicFields: List<SymbolEntry>, program: IrProgram): String {
        val synapticBlock = program.phases.firstOrNull { it.phase == IrPhase.SYNAPTIC }
        if (synapticBlock != null) {
            val dynTarget = collectOperations(synapticBlock.body)
                .firstOrNull { it.target.type == TxFieldType.DYNAMIC }?.target?.name
            if (dynTarget != null) return dynTarget
        }
        return dynamicFields.first().name
    }

    private data class OperationRef(val target: SymbolEntry, val opcode: OpCode?)

    private fun collectOperations(block: ir.IrBlock): List<OperationRef> {
        val ops = mutableListOf<OperationRef>()
        block.statements.forEach { stmt ->
            when (stmt) {
                is IrAssignment -> ops += OperationRef(stmt.target, null)
                is IrOperation -> ops += OperationRef(stmt.target, stmt.opcode)
                is IrConditional -> {
                    ops += collectOperations(stmt.thenBlock)
                    stmt.elseIfBranches.forEach { ops += collectOperations(it.body) }
                    stmt.elseBlock?.let { ops += collectOperations(it) }
                }
                is IrLoop -> ops += collectOperations(stmt.body)
            }
        }
        return ops
    }

    private fun buildRegBank(postWidth: Int, weightAddrWidth: Int): RegBankPlan {
        val regs = mutableListOf<RegDesc>()
        program.symbols.allFields()
            .filter { it.type == TxFieldType.STATIC }
            .forEach { regs += RegDesc(it.name, it.bitWidth, init = "0") }
        regs += RegDesc("postsynCount", postWidth, init = postWidth.toString())
        regs += RegDesc("baseAddr", weightAddrWidth, init = "0")

        val mapApi = regs.associate { it.name to it.name }
        return RegBankPlan(regs = regs, mapApiKeys = mapApi)
    }

    private fun buildPhasePlans(
        fifoIn: FifoInPlan,
        fifoOut: FifoOutPlan,
        dyn: DynArrays,
        synapticParams: List<SymbolEntry>,
        packingPlan: SynapticPackingPlan,
        pipelinePlan: SynapticPipelinePlan,
        emissionPlan: EmissionParallelPlan,
        refractoryPlan: RefractoryParallelPlan
    ): PhasePlans {
        val synapticBlock = program.phases.firstOrNull { it.phase == IrPhase.SYNAPTIC }
        val synOp = synapticBlock?.let { deriveSynOp(it) } ?: SynOpKind.ADD
        val synParamFromIr = synapticBlock?.let { block ->
            collectOperandSymbols(block.body).firstOrNull { it.type == TxFieldType.SYNAPTIC_PARAM }?.name
        }
        val preferredParam = synParamFromIr ?: synapticParams.firstOrNull()?.name
        val pack = if (packingPlan.enabled && packingPlan.mode == PackingMode.PACKED && packingPlan.assignments.isNotEmpty()) {
            SynPackPlan(
                wordWidth = packingPlan.wordWidth,
                fields = packingPlan.assignments.associate { it.symbol.name to SynPackSlice(it.bitOffset, it.bitOffset + it.bitWidth - 1) }
            )
        } else null

        val synPlan = SynPhasePlan(
            op = synOp,
            gateByTick = pipelinePlan.enabled,
            synParamField = preferredParam,
            packedSlices = pack,
            connects = mapOf(
                "inFifo" to fifoIn.role,
                "selector" to "selector0",
                "dyn" to dyn.main.field
            )
        )

        val neurBlock = program.phases.firstOrNull { it.phase == IrPhase.SOMATIC }
        val neurOps = neurBlock?.let { deriveNeurOps(it) } ?: emptyList()
        val neurPlan = NeurPhasePlan(
            ops = neurOps,
            postsynCountRegKey = "postsynCount"
        )

        val emitBlock = program.phases.firstOrNull { it.phase == IrPhase.EMISSION }
        val emitPlan = deriveEmitPlan(emitBlock, fifoOut, refractoryPlan)

        return PhasePlans(syn = synPlan, neur = neurPlan, emit = emitPlan)
    }

    private fun deriveSynOp(block: ir.IrPhaseBlock): SynOpKind {
        val op = collectOperations(block.body).mapNotNull { it.opcode }.firstOrNull()
        return when (op) {
            OpCode.ADD -> SynOpKind.ADD
            OpCode.SUB -> SynOpKind.SUB
            OpCode.POW, OpCode.MUL, OpCode.LOGICAL_OR, OpCode.LOGICAL_AND, OpCode.LOGICAL_NOT, null -> SynOpKind.ADD
        }
    }

    private fun deriveNeurOps(block: ir.IrPhaseBlock): List<NeurOpSpec> {
        return collectOperations(block.body).mapNotNull { ref ->
            when (ref.opcode) {
                OpCode.ADD -> NeurOpSpec(NeurOpKind.ADD, regKey = ref.target.name)
                OpCode.SUB -> NeurOpSpec(NeurOpKind.SUB, regKey = ref.target.name)
                else -> null
            }
        }
    }

    private fun collectOperandSymbols(block: ir.IrBlock): List<SymbolEntry> {
        val operands = mutableListOf<SymbolEntry>()
        block.statements.forEach { stmt ->
            when (stmt) {
                is IrOperation -> stmt.operands.forEach { value ->
                    if (value is ir.IrValue.Symbol) operands += value.entry
                }
                is IrConditional -> {
                    operands += collectOperandSymbols(stmt.thenBlock)
                    stmt.elseIfBranches.forEach { operands += collectOperandSymbols(it.body) }
                    stmt.elseBlock?.let { operands += collectOperandSymbols(it) }
                }
                is IrLoop -> operands += collectOperandSymbols(stmt.body)
                else -> {}
            }
        }
        return operands
    }

    private fun deriveEmitPlan(
        block: ir.IrPhaseBlock?,
        fifoOut: FifoOutPlan,
        refractoryPlan: RefractoryParallelPlan
    ): EmitPlan {
        val condition = block?.let { findFirstCondition(it.body) }
        val cmpKind = when (condition?.comparison) {
            ComparisonOp.GT -> CmpKind.GT
            ComparisonOp.LT -> CmpKind.LT
            ComparisonOp.GTE -> CmpKind.GE
            ComparisonOp.LTE -> CmpKind.LE
            else -> CmpKind.GE
        }
        val cmpRegKey = when (condition?.right) {
            is ir.IrValue.Symbol -> condition.right.entry.name
            else -> "threshold"
        }
        val resetRegKey = program.symbols.allFields().firstOrNull { it.name == "reset" && it.type == TxFieldType.STATIC }?.name

        return EmitPlan(
            cmp = cmpKind,
            cmpRegKey = cmpRegKey,
            refractory = refractoryPlan.enabled,
            resetRegKey = resetRegKey,
            outRole = fifoOut.role
        )
    }

    private fun findFirstCondition(block: ir.IrBlock): ir.IrCondition? {
        block.statements.forEach { stmt ->
            when (stmt) {
                is IrConditional -> return stmt.condition
                is IrLoop -> findFirstCondition(stmt.body)?.let { return it }
            }
        }
        return null
    }
}
