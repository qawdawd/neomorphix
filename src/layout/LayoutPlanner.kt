package layout

import ir.IrPhase
import ir.IrProgram
import semantics.*

/** Describes how IR constructs are placed onto BNMM resources. */
data class LayoutPlan(
    val memory: MemoryLayout,
    val pipeline: PipelineLayout,
    val parallelism: Map<IrPhase, PhaseParallelPlan>,
    val notes: List<String>
)

data class MemoryLayout(
    val wordCount: Int,
    val wordWidth: Int,
    val ports: Int
)

data class PipelineLayout(
    val enabled: Boolean,
    val stageCount: Int,
    val stageLatencyCycles: Int
)

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
        val notes = mutableListOf<String>()

        val memoryWords = packingPlan.assignments.map { it.wordIndex }.maxOrNull()?.plus(1) ?: 0
        if (!packingPlan.enabled) {
            notes += "Packing disabled; mapping each synaptic parameter to an individual word"
        }
        if (memoryWords == 0) {
            notes += "No synaptic memory required by architecture ${program.architecture.layerCount} layers"
        }

        val memoryLayout = MemoryLayout(
            wordCount = memoryWords,
            wordWidth = packingPlan.wordWidth,
            ports = packingPlan.memoryPorts
        )

        val pipelineLayout = PipelineLayout(
            enabled = pipelinePlan.enabled,
            stageCount = pipelinePlan.stages.size,
            stageLatencyCycles = pipelinePlan.stageLatencyCycles
        )
        if (!pipelinePlan.enabled) {
            notes += "Synaptic operations remain sequential"
        }

        val parallelPlans = linkedMapOf<IrPhase, PhaseParallelPlan>()
        parallelPlans[IrPhase.SOMATIC] = somaticPlan
        parallelPlans[IrPhase.EMISSION] = emissionPlan
        parallelPlans[IrPhase.REFRACTORY] = refractoryPlan

        val noParallel = parallelPlans.filterValues { !it.enabled }.keys
        if (noParallel.isNotEmpty()) {
            notes += "Sequential evaluation for phases: ${noParallel.joinToString { it.name.lowercase() }}"
        }

        return LayoutPlan(memoryLayout, pipelineLayout, parallelPlans, notes)
    }
}
