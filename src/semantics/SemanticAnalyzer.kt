package semantics

import ir.IrAssignment
import ir.IrBlock
import ir.IrBuilder
import ir.IrConditional
import ir.IrEmit
import ir.IrIterator
import ir.IrLoop
import ir.IrOperation
import ir.IrPhase
import ir.IrPhaseBlock
import ir.IrProgram
import ir.IrTransformKind
import transaction.TxFieldType
import kotlin.math.min

/**
 * Performs semantic analysis over the intermediate representation and produces reusable plans for
 * downstream stages such as layout, phase binding and controller synthesis.
 */
class SemanticAnalyzer(
    private val program: IrProgram,
    private val config: SemanticsConfig
) {
    private val irTools = IrBuilder()
    private val messages = mutableListOf<String>()

    /** Computes synaptic packing information based on configuration constraints. */
    fun planSynapticPacking(): SynapticPackingPlan {
        val options = config.packing
        val fields = program.symbols.allFields().filter { it.type == TxFieldType.SYNAPTIC_PARAM }
        val assignments = mutableListOf<PackedFieldAssignment>()
        val notes = mutableListOf<String>()
        val mode = if (options.enabled) PackingMode.PACKED else PackingMode.INDIVIDUAL_WORD
        val constraints = options.constraints

        var currentWord = 0
        var bitOffset = 0
        var initialized = false

        fun ensureInitialized() {
            if (!initialized) {
                currentWord = 0
                bitOffset = 0
                initialized = true
            }
        }

        fields.forEach { entry ->
            val width = entry.bitWidth
            val fits = options.enabled && width <= constraints.wordWidth
            if (mode == PackingMode.PACKED && fits) {
                ensureInitialized()
                if (bitOffset + width > constraints.wordWidth) {
                    currentWord += 1
                    bitOffset = 0
                }
                assignments += PackedFieldAssignment(entry, currentWord, bitOffset, width)
                bitOffset += width
                if (bitOffset == constraints.wordWidth) {
                    currentWord += 1
                    bitOffset = 0
                }
            } else {
                if (initialized) {
                    if (bitOffset != 0) {
                        currentWord += 1
                        bitOffset = 0
                    } else if (mode == PackingMode.INDIVIDUAL_WORD) {
                        currentWord += 1
                    }
                } else {
                    ensureInitialized()
                }
                assignments += PackedFieldAssignment(entry, currentWord, 0, width)
                bitOffset = 0
                if (mode == PackingMode.PACKED && width > constraints.wordWidth) {
                    notes += "Field '${entry.name}' width ${width}b exceeds word width ${constraints.wordWidth}b"
                    currentWord += 1
                }
            }
        }

        val plan = SynapticPackingPlan(
            enabled = options.enabled && fields.isNotEmpty(),
            mode = mode,
            wordWidth = constraints.wordWidth,
            memoryPorts = constraints.memoryPorts,
            assignments = assignments,
            notes = notes
        )

        when {
            fields.isEmpty() -> messages += "Synaptic packing skipped: no synaptic parameters detected"
            plan.enabled -> {
                val distinctWords = assignments.map { it.wordIndex }.distinct().size
                messages += "Synaptic packing enabled: ${assignments.size} field(s) across $distinctWords word(s)"
                recordTransform(IrTransformKind.APPLY_PACKING, "words=$distinctWords, wordWidth=${constraints.wordWidth}")
            }
            else -> messages += "Synaptic packing disabled; ${fields.size} field(s) stored individually"
        }

        return plan
    }

    /** Produces a synaptic pipeline skeleton based on IR operations. */
    fun planSynapticPipeline(): SynapticPipelinePlan {
        val options = config.pipeline
        val phaseBlock = findPhase(IrPhase.SYNAPTIC)
        val operations = collectPipelineOperations(phaseBlock)
        val notes = mutableListOf<String>()

        if (phaseBlock == null) {
            notes += "Synaptic phase is absent in IR"
        }
        if (operations.isEmpty()) {
            notes += "Synaptic phase does not contain operations"
        }

        val stageCount = when {
            operations.isEmpty() -> 1
            !options.enabled -> 1
            else -> min(options.constraints.maxStages, operations.size.coerceAtLeast(1))
        }
        val chunkSize = operations.size.takeIf { it > 0 }?.let { ceilDiv(it, stageCount) } ?: 1
        val stages = if (operations.isEmpty()) {
            listOf(PipelineStage("stage1", emptyList()))
        } else {
            operations.chunked(chunkSize).mapIndexed { index, ops ->
                PipelineStage("stage${index + 1}", ops)
            }
        }

        val plan = SynapticPipelinePlan(
            enabled = options.enabled && operations.isNotEmpty(),
            stageLatencyCycles = options.constraints.operationLatencyCycles,
            stages = stages,
            notes = notes
        )

        when {
            operations.isEmpty() -> messages += "Synaptic pipeline skipped: no operations detected"
            plan.enabled -> {
                messages += "Synaptic pipeline enabled with ${stages.size} stage(s)"
                recordTransform(IrTransformKind.PIPELINE_SYNAPTIC, "stages=${stages.size}, latency=${plan.stageLatencyCycles}")
            }
            else -> messages += "Synaptic pipeline disabled; ${operations.size} operation(s) remain sequential"
        }

        return plan
    }

    /** Plans parallel execution for the somatic phase. */
    fun planSomaticParallelism(groupSize: Int? = null): SomaticParallelPlan =
        computeParallelPlan(IrPhase.SOMATIC, config.somaticParallelism, groupSize)

    /** Plans parallel execution for the emission phase. */
    fun planEmissionParallelism(groupSize: Int? = null): EmissionParallelPlan =
        computeParallelPlan(IrPhase.EMISSION, config.emissionParallelism, groupSize)

    /** Plans parallel execution for the refractory phase. */
    fun planRefractoryParallelism(groupSize: Int? = null): RefractoryParallelPlan =
        computeParallelPlan(IrPhase.REFRACTORY, config.refractoryParallelism, groupSize)

    /** Returns a text report with accumulated semantic analysis messages. */
    fun report(): String {
        if (messages.isEmpty()) {
            return "Semantic analysis report:\n  (no entries)"
        }
        return buildString {
            appendLine("Semantic analysis report:")
            messages.forEach { appendLine("  - $it") }
        }
    }

    private fun computeParallelPlan(
        phase: IrPhase,
        baseOptions: ParallelismOptions,
        groupSizeOverride: Int?
    ): PhaseParallelPlan {
        val options = baseOptions.withGroupSizeOverride(groupSizeOverride)
        val totalNeurons = program.architecture.neuronsPerLayer.lastOrNull() ?: 0
        val requestedSize = options.groupSize
        val effectiveSize = if (options.enabled) requestedSize else 1
        val safeGroupSize = effectiveSize.coerceAtLeast(1)
        val totalGroups = if (totalNeurons == 0) 0 else ceilDiv(totalNeurons, safeGroupSize)
        val activeGroups = options.maxActiveGroups?.let { min(it, totalGroups) } ?: totalGroups
        val remainder = if (safeGroupSize == 0) 0 else totalNeurons % safeGroupSize
        val notes = mutableListOf<String>()
        if (totalNeurons == 0) {
            notes += "Architecture defines no postsynaptic neurons"
        }

        val plan = PhaseParallelPlan(
            phase = phase,
            enabled = options.enabled && totalNeurons > 0,
            requestedGroupSize = requestedSize,
            effectiveGroupSize = safeGroupSize,
            totalGroups = totalGroups,
            activeGroups = activeGroups,
            remainder = remainder,
            maxGroups = options.maxActiveGroups,
            notes = notes
        )

        when {
            totalNeurons == 0 -> messages += "Parallel ${phase.name.lowercase()} skipped: no postsynaptic neurons"
            plan.enabled -> {
                messages += "Parallel ${phase.name.lowercase()} enabled: groupSize=${plan.effectiveGroupSize}, activeGroups=${plan.activeGroups}"
                recordParallelTransform(phase, "groupSize=${plan.effectiveGroupSize}, activeGroups=${plan.activeGroups}")
            }
            else -> messages += "Parallel ${phase.name.lowercase()} disabled; sequential evaluation with group size ${plan.effectiveGroupSize}"
        }

        return plan
    }

    private fun collectPipelineOperations(phaseBlock: IrPhaseBlock?): List<PipelineOperationRef> {
        if (phaseBlock == null) return emptyList()
        val operations = mutableListOf<PipelineOperationRef>()
        collectOperationsFromBlock(phaseBlock.body, emptyList(), operations)
        return operations
    }

    private fun collectOperationsFromBlock(
        block: IrBlock,
        loopStack: List<IrIterator>,
        sink: MutableList<PipelineOperationRef>
    ) {
        block.statements.forEach { statement ->
            when (statement) {
                is IrAssignment -> sink += PipelineOperationRef(
                    kind = PipelineOperationKind.ASSIGNMENT,
                    target = statement.target.name,
                    opcode = null,
                    loopContext = loopStack
                )

                is IrOperation -> sink += PipelineOperationRef(
                    kind = PipelineOperationKind.OPERATION,
                    target = statement.target.name,
                    opcode = statement.opcode,
                    loopContext = loopStack
                )

                is IrEmit -> sink += PipelineOperationRef(
                    kind = PipelineOperationKind.EMIT,
                    target = statement.target?.name ?: "emit#${statement.emitId}",
                    opcode = null,
                    loopContext = loopStack
                )

                is IrConditional -> {
                    collectOperationsFromBlock(statement.thenBlock, loopStack, sink)
                    statement.elseIfBranches.forEach { branch ->
                        collectOperationsFromBlock(branch.body, loopStack, sink)
                    }
                    statement.elseBlock?.let { collectOperationsFromBlock(it, loopStack, sink) }
                }

                is IrLoop -> {
                    val extended = loopStack + statement.iterator
                    collectOperationsFromBlock(statement.body, extended, sink)
                }
            }
        }
    }

    private fun findPhase(phase: IrPhase): IrPhaseBlock? =
        program.phases.firstOrNull { it.phase == phase }

    private fun recordTransform(kind: IrTransformKind, details: String) {
        if (program.plannedTransformations.any { it.kind == kind }) return
        when (kind) {
            IrTransformKind.PIPELINE_SYNAPTIC -> irTools.pipelineSynapticPhase(program, details)
            IrTransformKind.PARALLEL_SOMATIC -> irTools.parallelizeSomatic(program, details)
            IrTransformKind.PARALLEL_EMISSION -> irTools.parallelizeEmission(program, details)
            IrTransformKind.PARALLEL_REFRACTORY -> irTools.parallelizeRefractory(program, details)
            IrTransformKind.APPLY_PACKING -> irTools.applyPacking(program, details)
        }
    }

    private fun recordParallelTransform(phase: IrPhase, details: String) {
        val kind = when (phase) {
            IrPhase.SOMATIC -> IrTransformKind.PARALLEL_SOMATIC
            IrPhase.EMISSION -> IrTransformKind.PARALLEL_EMISSION
            IrPhase.REFRACTORY -> IrTransformKind.PARALLEL_REFRACTORY
            IrPhase.SYNAPTIC -> return
        }
        recordTransform(kind, details)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int {
        require(divisor > 0) { "Divisor must be positive" }
        if (value <= 0) return 0
        return (value + divisor - 1) / divisor
    }
}