package neomorphix

import arch.ConnectivityType
import arch.SnnArch
import arch.StaticParamDescriptor
import ast.AstBlock
import ast.AstPhase
import ast.AstPhaseBlock
import ast.TrAstBuilder
import ast.TransactionKind
import control.ControlPlanner
import export.SystemVerilogExporter
import generation.CyclixGenerator
import ir.IrBuilder
import layout.LayoutPlanner
import naming.NameAllocator
import phasebinding.PhaseBinder
import semantics.PackingConstraints
import semantics.PackingOptions
import semantics.PipelineConstraints
import semantics.PipelineOptions
import semantics.SemanticAnalyzer
import semantics.SemanticsConfig
import transaction.ComparisonOp
import transaction.NeuronTx
import transaction.SpikeTx
import transaction.TxFieldType
import symbols.SymbolTable

/**
 * End-to-end demonstration of the compilation pipeline. The flow builds IR from
 * transactions, runs semantic planning, and then executes layout, binding,
 * control, naming, Cyclix generation and SystemVerilog export steps.
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

    val trBuilder = TrAstBuilder()
    val initial = trBuilder.buildInitial(spikeTx, neuronTx)
    val phased = trBuilder.attachPhaseRegions(initial)

    val expandedPhases = phased.statements.map { node ->
        val block = node as AstPhaseBlock
        when (block.phase) {
            AstPhase.SYNAPTIC -> trBuilder.expandSynapticLoops(block, arch)
            AstPhase.SOMATIC -> trBuilder.expandSomaticLoops(block, arch)
            AstPhase.EMISSION -> trBuilder.expandEmissionLoops(block, arch)
            AstPhase.REFRACTORY -> trBuilder.expandRefractoryLoops(block, arch)
        }
    }
    val expandedRoot = AstBlock(origin = null, statements = expandedPhases)
    trBuilder.normalize(expandedRoot)

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

    val semanticsConfig = SemanticsConfig(
        packing = PackingOptions(enabled = true, constraints = PackingConstraints(wordWidth = 32, memoryPorts = 2)),
        pipeline = PipelineOptions(enabled = true, constraints = PipelineConstraints(maxStages = 2, operationLatencyCycles = 2))
    )
    val analyzer = SemanticAnalyzer(program, semanticsConfig)
    val packingPlan = analyzer.planSynapticPacking()
    val pipelinePlan = analyzer.planSynapticPipeline()
    val somaticPlan = analyzer.planSomaticParallelism(groupSize = 2)
    val emissionPlan = analyzer.planEmissionParallelism()
    val refractoryPlan = analyzer.planRefractoryParallelism()

    val layoutPlan = LayoutPlanner(program).buildPlan(packingPlan, pipelinePlan, somaticPlan, emissionPlan, refractoryPlan)
    val bindingPlan = PhaseBinder(program).bind(layoutPlan)
    val controlPlan = ControlPlanner().plan(layoutPlan, bindingPlan)
    val namingPlan = NameAllocator(kernelPrefix = "neomorphix").planNames(controlPlan)
    val generated = CyclixGenerator().generate(program, layoutPlan, bindingPlan, controlPlan, namingPlan)
    val svArtifact = SystemVerilogExporter().export(generated)

    println(analyzer.report())
    println()
    println("Layout plan: ${layoutPlan}")
    println("Phase bindings: ${bindingPlan.bindings}")
    println("Control plan: states=${controlPlan.states.size}, transitions=${controlPlan.transitions.size}")
    println()
    println("Generated Cyclix kernel:\n${generated.cyclixSnippet}")
    println()
    println("SystemVerilog output:\n${svArtifact.body}")
    println()
    println("IR dump with planned transforms:\n${irBuilder.dump(program)}")
}
