package semantics

import ir.IrIterator
import ir.IrPhase
import symbols.SymbolEntry
import transaction.OpCode

/**
 * Packing mode applied to synaptic parameters.
 */
enum class PackingMode {
    INDIVIDUAL_WORD,
    PACKED
}

/**
 * Assignment of a synaptic parameter into a memory word.
 */
data class PackedFieldAssignment(
    val symbol: SymbolEntry,
    val wordIndex: Int,
    val bitOffset: Int,
    val bitWidth: Int
)

/**
 * Result of the synaptic packing analysis.
 */
data class SynapticPackingPlan(
    val enabled: Boolean,
    val mode: PackingMode,
    val wordWidth: Int,
    val memoryPorts: Int,
    val assignments: List<PackedFieldAssignment>,
    val notes: List<String> = emptyList()
)

/**
 * Classification of operations stored inside pipeline stages.
 */
enum class PipelineOperationKind {
    ASSIGNMENT,
    OPERATION,
    EMIT
}

/**
 * Reference to an operation that participates in the synaptic pipeline.
 */
data class PipelineOperationRef(
    val kind: PipelineOperationKind,
    val target: String,
    val opcode: OpCode?,
    val loopContext: List<IrIterator>
)

/**
 * Description of a single pipeline stage.
 */
data class PipelineStage(
    val name: String,
    val operations: List<PipelineOperationRef>
)

/**
 * Result of synaptic pipeline planning.
 */
data class SynapticPipelinePlan(
    val enabled: Boolean,
    val stageLatencyCycles: Int,
    val stages: List<PipelineStage>,
    val notes: List<String> = emptyList()
)

/**
 * Unified representation for phase-level parallelism plans.
 */
data class PhaseParallelPlan(
    val phase: IrPhase,
    val enabled: Boolean,
    val requestedGroupSize: Int,
    val effectiveGroupSize: Int,
    val totalGroups: Int,
    val activeGroups: Int,
    val remainder: Int,
    val maxGroups: Int?,
    val notes: List<String> = emptyList()
)

typealias SomaticParallelPlan = PhaseParallelPlan
typealias EmissionParallelPlan = PhaseParallelPlan
typealias RefractoryParallelPlan = PhaseParallelPlan
