package ir

import arch.ConnectivityType
import arch.SnnArch
import arch.StaticParamDescriptor
import ast.AstBlock
import ast.AstPhase
import ast.AstPhaseBlock
import ast.TrAstBuilder
import ast.TransactionKind
import symbols.SymbolTable
import transaction.ComparisonOp
import transaction.NeuronTx
import transaction.SpikeTx
import transaction.TxFieldType

/**
 * Demo program that constructs a small network, builds AST/IR and prints the IR dump. This allows
 * verifying that the intermediate representation mirrors the expectations before implementing
 * optimisation passes.
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
    irBuilder.pipelineSynapticPhase(program, "demo placeholder")
    println(irBuilder.dump(program))
}

