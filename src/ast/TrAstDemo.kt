package ast

import arch.ConnectivityType
import arch.SnnArch
import arch.StaticParamDescriptor
import transaction.ComparisonOp
import transaction.NeuronTx
import transaction.SpikeTx
import transaction.TxFieldType
// enum для выбора модели
enum class NeuronModel { LIF, IZHIKEVICH }

// import arch.ConnectivityType
// import arch.SnnArch
// import arch.StaticParamDescriptor
// import transaction.*

fun main() {
    // --- 1) Выбор модели и режимов ---
    val model = NeuronModel.LIF   // <-- LIF или IZHIKEVICH
    val doPhaseExpansion = true          // печать фаз и развёртки циклов

    // --- 2) Архитектура под выбранную модель ---
    val staticParams =
        when (model) {
            NeuronModel.LIF -> listOf(
                StaticParamDescriptor(name = "threshold", bitWidth = 8), // vtrsh
                StaticParamDescriptor(name = "reset",     bitWidth = 8),
                StaticParamDescriptor(name = "leak",      bitWidth = 6)
            )
            NeuronModel.IZHIKEVICH -> listOf(
                StaticParamDescriptor(name = "a",   bitWidth = 8),
                StaticParamDescriptor(name = "b",   bitWidth = 8),
                StaticParamDescriptor(name = "c",   bitWidth = 12), // reset v
                StaticParamDescriptor(name = "d",   bitWidth = 12), // increment u
                StaticParamDescriptor(name = "vth", bitWidth = 12)  // threshold
            )
        }

    val arch = SnnArch(
        layerCount = 2,
        neuronsPerLayer = listOf(4, 3),
        connectivity = ConnectivityType.FULLY_CONNECTED,
        staticParameters = staticParams
    )

    // --- 3) Построение транзакций под выбранную модель ---
    val (spikeTx, neuronTx) = when (model) {
        NeuronModel.LIF -> buildLifTransactions(arch)
        NeuronModel.IZHIKEVICH -> buildIzhTransactions(arch)
    }

    // --- 4) Вывод AST транзакций ---
    println("Spike transaction AST:\n${spikeTx.toAst()}\n")
    println("Neuron transaction AST:\n${neuronTx.toAst()}\n")

    println("Spike transaction (tree):")
    println(spikeTx.dumpTree())
    println()
    println("Neuron transaction (tree):")
    println(neuronTx.dumpTree())
    println()

    // --- 5) (опционально) Разделение на фазы и развёртывание циклов ---
    if (doPhaseExpansion) {
        val builder = TrAstBuilder()
        val initial = builder.buildInitial(spikeTx, neuronTx)
        val phased  = builder.attachPhaseRegions(initial)

        val expandedPhases = phased.statements.map { node ->
            val phaseBlock = node as AstPhaseBlock
            when (phaseBlock.phase) {
                AstPhase.SYNAPTIC   -> builder.expandSynapticLoops(phaseBlock, arch)
                AstPhase.SOMATIC    -> builder.expandSomaticLoops(phaseBlock, arch)
                AstPhase.EMISSION   -> builder.expandEmissionLoops(phaseBlock, arch)
                AstPhase.REFRACTORY -> builder.expandRefractoryLoops(phaseBlock, arch)
            }
        }

        val expandedRoot = AstBlock(origin = null, statements = expandedPhases)
        builder.normalize(expandedRoot)

        println("=== Phased & Expanded Program ===")
        println(builder.dump(expandedRoot))
    }
}

/* ----------------------- Реализации моделей ----------------------- */

// LIF: SpikeTx: vm += w
//      NeuronTx: vm := vm - leak; if (vm >= threshold) { emit(spike_flag); vm := reset } else { spike_flag := 0 }
private fun buildLifTransactions(arch: SnnArch): Pair<SpikeTx, NeuronTx> {
    val spikeTx = SpikeTx("lif_spike_phase").apply {
        addField("w",  8,  TxFieldType.SYNAPTIC_PARAM) // вес
        addField("vm", 12, TxFieldType.DYNAMIC)        // мембранный потенциал пост-нейрона
        build {
            add("vm", field("vm"), field("w"))         // классический вклад: vm += w
        }
    }

    val neuronTx = NeuronTx("lif_neuron_phase").apply {
        addField("vm",         12, TxFieldType.DYNAMIC)
        addField("spike_flag",  1, TxFieldType.LOCAL)
        addStaticFieldFromArch(arch, "threshold")
        addStaticFieldFromArch(arch, "reset")
        addStaticFieldFromArch(arch, "leak")

        withNeuron {
            // утечка
            sub("vm", field("vm"), field("leak"))

            // порог + спайк + сброс
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

    return spikeTx to neuronTx
}

// Izhikevich:
// SpikeTx: i_syn += w
// NeuronTx step:
//   v2 = v*v
//   tmp1 = 5*v
//   tmp2 = v2 + tmp1 + 140 - u + i_syn
//   v = v + tmp2
//   tmp3 = a * (b*v - u)
//   u = u + tmp3
//   if (v >= vth) { spike=1; emit(spike); v=c; u=u+d } else { spike=0 }
//   i_syn = 0
private fun buildIzhTransactions(arch: SnnArch): Pair<SpikeTx, NeuronTx> {
    val spikeTx = SpikeTx("izh_spike_phase").apply {
        addField("w",     8,  TxFieldType.SYNAPTIC_PARAM) // вес синапса
        addField("i_syn", 16, TxFieldType.DYNAMIC)        // сумматор входов
        addField("acc",   16, TxFieldType.LOCAL)          // временный аккумулятор
        build {
            assign("acc",  field("i_syn"))
            add("acc",     field("acc"), field("w"))
            assign("i_syn", field("acc"))
        }
    }

    val neuronTx = NeuronTx("izh_neuron_phase").apply {
        // динамика
        addField("v",     16, TxFieldType.DYNAMIC)
        addField("u",     16, TxFieldType.DYNAMIC)
        addField("i_syn", 16, TxFieldType.DYNAMIC)
        addField("spike",  1, TxFieldType.LOCAL)

        // статпараметры
        addStaticFieldFromArch(arch, "a")
        addStaticFieldFromArch(arch, "b")
        addStaticFieldFromArch(arch, "c")
        addStaticFieldFromArch(arch, "d")
        addStaticFieldFromArch(arch, "vth")

        // локальные временные
        addField("v2",   24, TxFieldType.LOCAL)
        addField("tmp1", 24, TxFieldType.LOCAL)
        addField("tmp2", 24, TxFieldType.LOCAL)
        addField("tmp3", 24, TxFieldType.LOCAL)

        withNeuron {
            // v2 := v * v
            mul("v2", field("v"), field("v"))
            // tmp1 := 5 * v
            mul("tmp1", field("v"), const(5))
            // tmp2 := v2 + tmp1
            add("tmp2", field("v2"), field("tmp1"))
            // tmp2 := tmp2 + 140
            add("tmp2", field("tmp2"), const(140))
            // tmp2 := tmp2 - u
            sub("tmp2", field("tmp2"), field("u"))
            // tmp2 := tmp2 + i_syn
            add("tmp2", field("tmp2"), field("i_syn"))
            // v := v + tmp2
            add("v", field("v"), field("tmp2"))

            // u := u + a*(b*v - u)
            mul("tmp3", field("b"), field("v"))   // tmp3 = b*v
            sub("tmp3", field("tmp3"), field("u"))// tmp3 = b*v - u
            mul("tmp3", field("a"), field("tmp3"))// tmp3 = a*(b*v - u)
            add("u", field("u"), field("tmp3"))   // u = u + tmp3

            // порог и спайк
            ifCondition(field("v"), ComparisonOp.GTE, field("vth")) {
                assign("spike", const(1))
                emit("spike")
                assign("v", field("c"))
                add("u", field("u"), field("d"))
            }.elseBlock {
                assign("spike", const(0))
            }

            // обнулить сумматор входов после шага
            assign("i_syn", const(0))
        }
    }

    return spikeTx to neuronTx
}
