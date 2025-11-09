package semantics

import arch.ConnectivityType
import arch.SnnArch
import arch.StaticParamDescriptor
import ast.AstBlock
import ast.AstPhase
import ast.AstPhaseBlock
import ast.TrAstBuilder
import ast.TransactionKind
import ir.IrBuilder
import symbols.SymbolTable
import transaction.ComparisonOp
import transaction.NeuronTx
import transaction.SpikeTx
import transaction.TxFieldType

/**
 * Demonstrates semantic planning by constructing IR, running the analyser and printing plans.
 */
fun main() {
    val arch = SnnArch(
        layerCount = 2,
        neuronsPerLayer = listOf(4, 3),
        connectivity = ConnectivityType.FULLY_CONNECTED,
        staticParameters = listOf(
            StaticParamDescriptor("threshold", 8),
            StaticParamDescriptor("reset", 8),
            StaticParamDescriptor("leak", 6)
        )
    )

    val spikeTx = SpikeTx("spike_phase").apply {
        addField("w", 8, TxFieldType.SYNAPTIC_PARAM)
        addField("vm", 12, TxFieldType.DYNAMIC)
        build {
            add("vm", field("vm"), field("w"))
        }
    }

    val neuronTx = NeuronTx("neuron_phase").apply {
        addField("vm", 12, TxFieldType.DYNAMIC)
        addField("spike_flag", 1, TxFieldType.LOCAL)
        addStaticFieldFromArch(arch, "threshold")
        addStaticFieldFromArch(arch, "reset")
        addStaticFieldFromArch(arch, "leak")

        withNeuron {
            sub("vm", field("vm"), field("leak"))
            ifCondition(field("vm"), ComparisonOp.GTE, field("threshold")) {
                assign("spike_flag", const(1))
                emit("spike_flag")
                assign("vm", field("reset"))
            }.elseBlock {
                assign("spike_flag", const(0))
                assign("vm", field("vm"))
            }
        }
    }

    val builder = TrAstBuilder()
    val initial = builder.buildInitial(spikeTx, neuronTx)
    val phased = builder.attachPhaseRegions(initial)
    val expandedPhases = phased.statements.map { node ->
        val block = node as AstPhaseBlock
        when (block.phase) {
            AstPhase.SYNAPTIC -> builder.expandSynapticLoops(block, arch)
            AstPhase.SOMATIC -> builder.expandSomaticLoops(block, arch)
            AstPhase.EMISSION -> builder.expandEmissionLoops(block, arch)
            AstPhase.REFRACTORY -> builder.expandRefractoryLoops(block, arch)
        }
    }
    val expandedRoot = AstBlock(origin = null, statements = expandedPhases)
    builder.normalize(expandedRoot)

    val symbolTable = SymbolTable()
    val spikeAst = spikeTx.toAst()
    val neuronAst = neuronTx.toAst()
    symbolTable.registerTransactionFields(spikeTx.name, TransactionKind.SPIKE, spikeAst.fields)
    symbolTable.registerTransactionFields(neuronTx.name, TransactionKind.NEURON, neuronAst.fields)
    val spikeNodes = initial.statements.filter { it.origin == TransactionKind.SPIKE }
    val neuronNodes = initial.statements.filter { it.origin == TransactionKind.NEURON }
    symbolTable.registerOperands(spikeTx.name, TransactionKind.SPIKE, spikeNodes)
    symbolTable.registerOperands(neuronTx.name, TransactionKind.NEURON, neuronNodes)
    symbolTable.validate(expandedRoot)

    val irBuilder = IrBuilder()
    val program = irBuilder.fromAst(expandedRoot, symbolTable, arch)

    val config = SemanticsConfig(
        packing = PackingOptions(
            enabled = true,
            constraints = PackingConstraints(wordWidth = 32, memoryPorts = 2)
        ),
        pipeline = PipelineOptions(
            enabled = true,
            constraints = PipelineConstraints(maxStages = 3, operationLatencyCycles = 2)
        ),
        somaticParallelism = ParallelismOptions(enabled = true, groupSize = 2, maxActiveGroups = 2),
        emissionParallelism = ParallelismOptions(enabled = false, groupSize = 1),
        refractoryParallelism = ParallelismOptions(enabled = true, groupSize = 3)
    )

    val analyzer = SemanticAnalyzer(program, config)
    val packingPlan = analyzer.planSynapticPacking()
    val pipelinePlan = analyzer.planSynapticPipeline()
    val somaticPlan = analyzer.planSomaticParallelism()
    val emissionPlan = analyzer.planEmissionParallelism(groupSize = 2)
    val refractoryPlan = analyzer.planRefractoryParallelism()

    println("Synaptic packing plan (mode=${packingPlan.mode}):")
    packingPlan.assignments.forEach { assignment ->
        println("  - ${assignment.symbol.name} -> word=${assignment.wordIndex}, offset=${assignment.bitOffset}, width=${assignment.bitWidth}")
    }

    println("\nSynaptic pipeline plan: stages=${pipelinePlan.stages.size}, latency=${pipelinePlan.stageLatencyCycles}")
    pipelinePlan.stages.forEach { stage ->
        val ops = stage.operations.joinToString { "${it.kind}:${it.target}" }
        println("  - ${stage.name}: $ops")
    }

    fun printParallelPlan(label: String, plan: PhaseParallelPlan) {
        println("$label: enabled=${plan.enabled}, effectiveGroup=${plan.effectiveGroupSize}, totalGroups=${plan.totalGroups}, activeGroups=${plan.activeGroups}")
    }

    println()
    printParallelPlan("Somatic parallel plan", somaticPlan)
    printParallelPlan("Emission parallel plan", emissionPlan)
    printParallelPlan("Refractory parallel plan", refractoryPlan)

    println()
    println(analyzer.report())

    println("\nIR dump with recorded transformations:")
    println(irBuilder.dump(program))
}