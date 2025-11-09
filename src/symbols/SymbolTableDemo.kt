package symbols

import arch.ConnectivityType
import arch.SnnArch
import arch.StaticParamDescriptor
import ast.AstPhase
import ast.AstPhaseBlock
import ast.TrAstBuilder
import ast.TransactionKind
import transaction.ComparisonOp
import transaction.NeuronTx
import transaction.SpikeTx
import transaction.TxFieldType

/**
 * Demo entry point that constructs spike and neuron transactions, builds a phased AST and registers
 * all discovered symbols inside the symbol table. The final table dump illustrates the stored
 * metadata.
 */
fun main() {
    val staticParams = listOf(
        StaticParamDescriptor("threshold", 8),
        StaticParamDescriptor("reset", 8),
        StaticParamDescriptor("leak", 6)
    )

    val arch = SnnArch(
        layerCount = 2,
        neuronsPerLayer = listOf(4, 3),
        connectivity = ConnectivityType.FULLY_CONNECTED,
        staticParameters = staticParams
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

    val symbolTable = SymbolTable()
    val spikeAst = spikeTx.toAst()
    val neuronAst = neuronTx.toAst()

    symbolTable.registerTransactionFields(spikeTx.name, TransactionKind.SPIKE, spikeAst.fields)
    symbolTable.registerTransactionFields(neuronTx.name, TransactionKind.NEURON, neuronAst.fields)

    phased.statements.forEach { node ->
        if (node is AstPhaseBlock) {
            val txId = when (node.phase) {
                AstPhase.SYNAPTIC -> spikeTx.name
                AstPhase.SOMATIC,
                AstPhase.EMISSION,
                AstPhase.REFRACTORY -> neuronTx.name
            }
            symbolTable.registerOperands(txId, node.origin, node.body.statements)
        }
    }

    symbolTable.validate(phased)
    println(symbolTable.dump())
}
