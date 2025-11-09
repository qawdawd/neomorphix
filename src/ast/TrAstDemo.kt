package ast

import arch.ConnectivityType
import arch.SnnArch
import arch.StaticParamDescriptor
import transaction.ComparisonOp
import transaction.NeuronTx
import transaction.SpikeTx
import transaction.TxFieldType

// package demo
// import arch.ConnectivityType
// import arch.SnnArch
// import arch.StaticParamDescriptor
// import transaction.*
// import tast.*

/**
 * Demo: описываем модель сети и транзакции "по-новому" (как во 2-м примере),
 * затем строим транзакционный AST, выполняем разделение на фазы и разворачивание циклов,
 * и печатаем результат.
 */

fun main() {



// 1) Архитектура без JSON: статпараметры и базовая SNN-конфигурация
    val staticParams = listOf(
        StaticParamDescriptor(name = "a", bitWidth = 8),
        StaticParamDescriptor(name = "b", bitWidth = 8),
        StaticParamDescriptor(name = "c", bitWidth = 12),   // reset v
        StaticParamDescriptor(name = "d", bitWidth = 12),   // increment u
        StaticParamDescriptor(name = "vth", bitWidth = 12)  // threshold
    )

    val arch = SnnArch(
        layerCount = 2,
        neuronsPerLayer = listOf(4, 3),
        connectivity = ConnectivityType.FULLY_CONNECTED,
        staticParameters = staticParams
    )

// 2) Спайковая транзакция: аккумулируем синаптический ток/сумму в i_syn
    val spikeTx = SpikeTx("izh_spike_phase")
    spikeTx.addField("w",     8,  TxFieldType.SYNAPTIC_PARAM) // вес синапса
    spikeTx.addField("i_syn", 16, TxFieldType.DYNAMIC)         // сумматор входов на пост-нейроне
    spikeTx.addField("acc",   16, TxFieldType.LOCAL)          // временный аккумулятор

    spikeTx.build {
        // acc := i_syn + w ; i_syn := acc
        assign("acc", field("i_syn"))
        add("acc", field("acc"), field("w"))
        assign("i_syn", field("acc"))
    }

// 3) Нейронная транзакция: дискретный шаг уравнений Ижикевича (целочисленно)
    val neuronTx = NeuronTx("izh_neuron_phase")

// динамические поля
    neuronTx.addField("v",     16, TxFieldType.DYNAMIC) // мембранный потенциал
    neuronTx.addField("u",     16, TxFieldType.DYNAMIC) // восстановительная переменная
    neuronTx.addField("i_syn", 16, TxFieldType.DYNAMIC) // суммарный вход от SpikeTx
    neuronTx.addField("spike", 1,  TxFieldType.LOCAL)   // флаг спайка (локальный)

// статические параметры (подтягиваются из арх-модели)
    neuronTx.addStaticFieldFromArch(arch, "a")
    neuronTx.addStaticFieldFromArch(arch, "b")
    neuronTx.addStaticFieldFromArch(arch, "c")
    neuronTx.addStaticFieldFromArch(arch, "d")
    neuronTx.addStaticFieldFromArch(arch, "vth")

// вспомогательные временные локальные регистры для арифметики
    neuronTx.addField("v2",    24, TxFieldType.LOCAL)  // v^2
    neuronTx.addField("tmp1",  24, TxFieldType.LOCAL)  // рабочие tmp
    neuronTx.addField("tmp2",  24, TxFieldType.LOCAL)
    neuronTx.addField("tmp3",  24, TxFieldType.LOCAL)

    neuronTx.withNeuron {
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

        // u update: u := u + a*(b*v - u)
        // tmp3 := b * v
        mul("tmp3", field("b"), field("v"))
        // tmp3 := tmp3 - u
        sub("tmp3", field("tmp3"), field("u"))
        // tmp3 := a * tmp3
        mul("tmp3", field("a"), field("tmp3"))
        // u := u + tmp3
        add("u", field("u"), field("tmp3"))

        // Проверка порога и спайк
        ifCondition(field("v"), ComparisonOp.GTE, field("vth")) {
            // spike := 1 ; emit(spike) ; v := c ; u := u + d
            assign("spike", const(1))
            emit("spike")
            assign("v", field("c"))
            add("u", field("u"), field("d"))
        }.elseBlock {
            assign("spike", const(0))
        }

        // После шага обнулим сумматор входов
        assign("i_syn", const(0))
    }

//// 4) Печать «плоского» AST и древовидного лог-дампа
//    println("Spike transaction AST:\n${spikeTx.toAst()}")
//    println()
//    println("Neuron transaction AST:\n${neuronTx.toAst()}")
//    println()
//    println("Spike transaction (tree):\n${spikeTx.dumpTree()}")
//    println()
//    println("Neuron transaction (tree):\n${neuronTx.dumpTree()}")


    // 4) Построение, разделение на фазы и разворачивание циклов (как в 1-м примере)
    val builder = TrAstBuilder()
    val initial = builder.buildInitial(spikeTx, neuronTx)
    val phased = builder.attachPhaseRegions(initial)

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

    // 5) Вывод
    println("Spike transaction AST:\n${spikeTx.toAst()}\n")
    println("Neuron transaction AST:\n${neuronTx.toAst()}\n")

    println("=== Phased & Expanded Program ===")
    println(builder.dump(expandedRoot))
}

//
//fun main() {
//    // 1) Архитектура сети (как во 2-м примере)
//    val staticParams = listOf(
//        StaticParamDescriptor(name = "threshold", bitWidth = 8),  // порог
//        StaticParamDescriptor(name = "reset", bitWidth = 8),      // значение сброса
//        StaticParamDescriptor(name = "leak", bitWidth = 6)        // утечка
//    )
//
//    val arch = SnnArch(
//        layerCount = 2,
//        neuronsPerLayer = listOf(4, 3),
//        connectivity = ConnectivityType.FULLY_CONNECTED,
//        staticParameters = staticParams
//    )
//
//    // 2) Спайковая транзакция: классический вклад vm += w
//    val spikeTx = SpikeTx("spike_phase").apply {
//        addField("w", 8, TxFieldType.SYNAPTIC_PARAM)  // вес
//        addField("vm", 12, TxFieldType.DYNAMIC)       // мембранный потенциал пост-нейрона
//        build {
//            add("vm", field("vm"), field("w"))
//        }
//    }
//
//    // 3) Нейронная транзакция: LIF
//    //    vm := vm - leak
//    //    if (vm >= threshold) { emit(spike_flag); vm := reset } else { spike_flag := 0 }
//    val neuronTx = NeuronTx("neuron_phase").apply {
//        addField("vm", 12, TxFieldType.DYNAMIC)
//        addField("spike_flag", 1, TxFieldType.LOCAL)
//        addStaticFieldFromArch(arch, "threshold")
//        addStaticFieldFromArch(arch, "reset")
//        addStaticFieldFromArch(arch, "leak")
//
//        withNeuron {
//            // утечка
//            sub("vm", field("vm"), field("leak"))
//
//            // порог + спайк + сброс
//            ifCondition(field("vm"), ComparisonOp.GTE, field("threshold")) {
//                assign("spike_flag", const(1))
//                emit("spike_flag")
//                assign("vm", field("reset"))
//            }.elseBlock {
//                assign("spike_flag", const(0))
//                assign("vm", field("vm"))
//            }
//        }
//    }
//
//    // 4) Построение, разделение на фазы и разворачивание циклов (как в 1-м примере)
//    val builder = TrAstBuilder()
//    val initial = builder.buildInitial(spikeTx, neuronTx)
//    val phased = builder.attachPhaseRegions(initial)
//
//    val expandedPhases = phased.statements.map { node ->
//        val phaseBlock = node as AstPhaseBlock
//        when (phaseBlock.phase) {
//            AstPhase.SYNAPTIC   -> builder.expandSynapticLoops(phaseBlock, arch)
//            AstPhase.SOMATIC    -> builder.expandSomaticLoops(phaseBlock, arch)
//            AstPhase.EMISSION   -> builder.expandEmissionLoops(phaseBlock, arch)
//            AstPhase.REFRACTORY -> builder.expandRefractoryLoops(phaseBlock, arch)
//        }
//    }
//
//    val expandedRoot = AstBlock(origin = null, statements = expandedPhases)
//    builder.normalize(expandedRoot)
//
//    // 5) Вывод
//    println("Spike transaction AST:\n${spikeTx.toAst()}\n")
//    println("Neuron transaction AST:\n${neuronTx.toAst()}\n")
//
//    println("=== Phased & Expanded Program ===")
//    println(builder.dump(expandedRoot))
//}
//


///**
// * Simple demo that constructs spike and neuron transactions, builds the transactional AST and
// * prints the resulting structure after phase separation and loop expansion.
// */
//fun main() {
//    val archJson = """
//        {
//          "layerCount": 2,
//          "neuronsPerLayer": [3, 2],
//          "connectivity": "fully_connected",
//          "staticParameters": [
//            {"name": "threshold", "width": 8}
//          ]
//        }
//    """.trimIndent()
//
//    val arch = SnnArch.fromConfig(archJson)
//
//    val spikeTx = SpikeTx("SpikePhase").apply {
//        addField("weight", 8, TxFieldType.SYNAPTIC_PARAM)
//        addField("input", 8, TxFieldType.LOCAL)
//        addField("acc", 12, TxFieldType.DYNAMIC)
//        build {
//            add("acc", field("acc"), field("weight"))
//            ifCondition(field("weight"), ComparisonOp.NEQ, const(0)) {
//                sub("acc", field("acc"), field("input"))
//            }
//        }
//    }
//
//    val neuronTx = NeuronTx("NeuronPhase").apply {
//        addField("potential", 12, TxFieldType.DYNAMIC)
//        addField("spikeFlag", 1, TxFieldType.LOCAL)
//        addStaticFieldFromArch(arch, "threshold", "thr")
//        build {
//            assign("spikeFlag", const(0))
//            ifCondition(field("potential"), ComparisonOp.GT, field("thr")) {
//                emit("potential")
//                assign("spikeFlag", const(1))
//            }
//            ifCondition(field("spikeFlag"), ComparisonOp.EQ, const(1)) {
//                assign("potential", const(0))
//            }.elseBlock {
//                assign("potential", field("potential"))
//            }
//        }
//    }
//
//    val builder = TrAstBuilder()
//    val initial = builder.buildInitial(spikeTx, neuronTx)
//    val phased = builder.attachPhaseRegions(initial)
//
//    val expandedPhases = phased.statements.map { node ->
//        val phaseBlock = node as AstPhaseBlock
//        when (phaseBlock.phase) {
//            AstPhase.SYNAPTIC -> builder.expandSynapticLoops(phaseBlock, arch)
//            AstPhase.SOMATIC -> builder.expandSomaticLoops(phaseBlock, arch)
//            AstPhase.EMISSION -> builder.expandEmissionLoops(phaseBlock, arch)
//            AstPhase.REFRACTORY -> builder.expandRefractoryLoops(phaseBlock, arch)
//        }
//    }
//
//    val expandedRoot = AstBlock(origin = null, statements = expandedPhases)
//    builder.normalize(expandedRoot)
//    println(builder.dump(expandedRoot))
//}
