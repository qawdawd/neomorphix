package transaction

import arch.ConnectivityType
import arch.SnnArch
import arch.StaticParamDescriptor

/**
 * Izhikevich neuron demo:
 * SpikeTx: i_syn += w
 * NeuronTx (per tick):
 *   let f = v^2 + 5*v + 140 - u + i_syn
 *   v = v + f
 *   u = u + a*(b*v - u)
 *   if v >= vth then emit(spike) ; v = c ; u = u + d
 */
//fun main() {
//    // 1) Архитектура без JSON: статпараметры и базовая SNN-конфигурация
//    val staticParams = listOf(
//        StaticParamDescriptor(name = "a", bitWidth = 8),
//        StaticParamDescriptor(name = "b", bitWidth = 8),
//        StaticParamDescriptor(name = "c", bitWidth = 12),   // reset v
//        StaticParamDescriptor(name = "d", bitWidth = 12),   // increment u
//        StaticParamDescriptor(name = "vth", bitWidth = 12)  // threshold
//    )
//
//    val arch = SnnArch(
//        layerCount = 2,
//        neuronsPerLayer = listOf(4, 3),
//        connectivity = ConnectivityType.FULLY_CONNECTED,
//        staticParameters = staticParams
//    )
//
//    // 2) Спайковая транзакция: аккумулируем синаптический ток/сумму в i_syn
//    val spikeTx = SpikeTx("izh_spike_phase")
//    spikeTx.addField("w",     8,  TxFieldType.SYNAPTIC_PARAM) // вес синапса
//    spikeTx.addField("i_syn", 16, TxFieldType.DYNAMIC)         // сумматор входов на пост-нейроне
//    spikeTx.addField("acc",   16, TxFieldType.LOCAL)          // временный аккумулятор
//
//    spikeTx.build {
//        // acc := i_syn + w ; i_syn := acc
//        assign("acc", field("i_syn"))
//        add("acc", field("acc"), field("w"))
//        assign("i_syn", field("acc"))
//    }
//
//    // 3) Нейронная транзакция: дискретный шаг уравнений Ижикевича (целочисленно)
//    val neuronTx = NeuronTx("izh_neuron_phase")
//
//    // динамические поля
//    neuronTx.addField("v",     16, TxFieldType.DYNAMIC) // мембранный потенциал
//    neuronTx.addField("u",     16, TxFieldType.DYNAMIC) // восстановительная переменная
//    neuronTx.addField("i_syn", 16, TxFieldType.DYNAMIC) // суммарный вход от SpikeTx
//    neuronTx.addField("spike", 1,  TxFieldType.LOCAL)   // флаг спайка (локальный)
//
//    // статические параметры (подтягиваются из арх-модели)
//    neuronTx.addStaticFieldFromArch(arch, "a")
//    neuronTx.addStaticFieldFromArch(arch, "b")
//    neuronTx.addStaticFieldFromArch(arch, "c")
//    neuronTx.addStaticFieldFromArch(arch, "d")
//    neuronTx.addStaticFieldFromArch(arch, "vth")
//
//    // вспомогательные временные локальные регистры для арифметики
//    neuronTx.addField("v2",    24, TxFieldType.LOCAL)  // v^2
//    neuronTx.addField("tmp1",  24, TxFieldType.LOCAL)  // рабочие tmp
//    neuronTx.addField("tmp2",  24, TxFieldType.LOCAL)
//    neuronTx.addField("tmp3",  24, TxFieldType.LOCAL)
//
//    neuronTx.withNeuron {
//        // v2 := v * v
//        mul("v2", field("v"), field("v"))
//
//        // tmp1 := 5 * v
//        mul("tmp1", field("v"), const(5))
//
//        // tmp2 := v2 + tmp1
//        add("tmp2", field("v2"), field("tmp1"))
//
//        // tmp2 := tmp2 + 140
//        add("tmp2", field("tmp2"), const(140))
//
//        // tmp2 := tmp2 - u
//        sub("tmp2", field("tmp2"), field("u"))
//
//        // tmp2 := tmp2 + i_syn
//        add("tmp2", field("tmp2"), field("i_syn"))
//
//        // v := v + tmp2
//        add("v", field("v"), field("tmp2"))
//
//        // u update: u := u + a*(b*v - u)
//        // tmp3 := b * v
//        mul("tmp3", field("b"), field("v"))
//        // tmp3 := tmp3 - u
//        sub("tmp3", field("tmp3"), field("u"))
//        // tmp3 := a * tmp3
//        mul("tmp3", field("a"), field("tmp3"))
//        // u := u + tmp3
//        add("u", field("u"), field("tmp3"))
//
//        // Проверка порога и спайк
//        ifCondition(field("v"), ComparisonOp.GTE, field("vth")) {
//            // spike := 1 ; emit(spike) ; v := c ; u := u + d
//            assign("spike", const(1))
//            emit("spike")
//            assign("v", field("c"))
//            add("u", field("u"), field("d"))
//        }.elseBlock {
//            assign("spike", const(0))
//        }
//
//        // После шага обнулим сумматор входов
//        assign("i_syn", const(0))
//    }
//
//    // 4) Печать «плоского» AST и древовидного лог-дампа
//    println("Spike transaction AST:\n${spikeTx.toAst()}")
//    println()
//    println("Neuron transaction AST:\n${neuronTx.toAst()}")
//    println()
//    println("Spike transaction (tree):\n${spikeTx.dumpTree()}")
//    println()
//    println("Neuron transaction (tree):\n${neuronTx.dumpTree()}")
//}

//package demo
//
//import arch.ConnectivityType
//import arch.SnnArch
//import arch.StaticParamDescriptor
//import transaction.*

fun main() {
    val staticParams = listOf(
        StaticParamDescriptor(name = "threshold", bitWidth = 8), // vtrsh
        StaticParamDescriptor(name = "reset", bitWidth = 8),
        StaticParamDescriptor(name = "leak", bitWidth = 6)
    )

    val arch = SnnArch(
        layerCount = 2,
        neuronsPerLayer = listOf(4, 3),
        connectivity = ConnectivityType.FULLY_CONNECTED,
        staticParameters = staticParams
    )

    // Спайковая транзакция: vm := vm + w
    val spikeTx = SpikeTx("spike_phase")
    spikeTx.addField("w", 8, TxFieldType.SYNAPTIC_PARAM)  // вес
    spikeTx.addField("vm", 12, TxFieldType.DYNAMIC)        // мембранный потенциал пост-нейрона

    spikeTx.build {
        // классический вклад: vm += w
        add("vm", field("vm"), field("w"))
    }

    // 3) Нейронная транзакция: LIF
    //   vm := vm - leak
    //   if (vm >= threshold) { emit(spike_flag); vm := reset }
    val neuronTx = NeuronTx("neuron_phase")
    neuronTx.addField("vm", 12, TxFieldType.DYNAMIC)                  // динамическое состояние
    neuronTx.addField("spike_flag", 1, TxFieldType.LOCAL)             // локальный флаг для emit
    neuronTx.addStaticFieldFromArch(arch, "threshold")                // порог (vtrsh)
    neuronTx.addStaticFieldFromArch(arch, "reset")                    // значение сброса
    neuronTx.addStaticFieldFromArch(arch, "leak")                     // утечка

    neuronTx.withNeuron {
        // утечка
        sub("vm", field("vm"), field("leak"))

        // порог + спайк + сброс
        ifCondition(field("vm"), ComparisonOp.GTE, field("threshold")) {
            assign("spike_flag", const(1))
            emit("spike_flag")           // событие
            assign("vm", field("reset")) // сброс мембранного потенциала
        }.elseBlock {
            assign("spike_flag", const(0))
            assign("vm", field("vm"))
        }
    }

    // Вывод: плоский AST + древовидный дамп
    println("Spike transaction AST:\n${spikeTx.toAst()}\n")
    println("Neuron transaction AST:\n${neuronTx.toAst()}\n")

    println("Spike transaction (tree):")
    println(spikeTx.dumpTree())
    println()
    println("Neuron transaction (tree):")
    println(neuronTx.dumpTree())
}

//package transaction
//
//import arch.SnnArch
//
///**
// * Demonstrates the basic transaction building API by constructing both spike and neuron
// * transactions and printing their AST representations.
// */
//fun main() {
//    val archConfig = """
//        {
//          "layerCount": 2,
//          "neuronsPerLayer": [3, 3],
//          "staticParameters": [
//            { "name": "threshold", "width": 8 },
//            { "name": "reset", "width": 6 }
//          ]
//        }
//    """.trimIndent()
//    val arch = SnnArch.fromConfig(archConfig)
//
//    val spikeTx = SpikeTx("spike_phase")
//    spikeTx.addField("weight", 8, TxFieldType.SYNAPTIC_PARAM)
//    spikeTx.addField("acc", 12, TxFieldType.LOCAL)
//    spikeTx.addField("post_state", 12, TxFieldType.NEURON)
//
//    spikeTx.build {
//        assign("acc", const(0))
//        add("acc", field("acc"), field("weight"))
//        ifCondition(
//            field("acc"),
//            ComparisonOp.GTE,
//            externalField("vm", TxFieldType.DYNAMIC, 12)
//        ) {
//            sub("acc", field("acc"), externalField("vm", TxFieldType.DYNAMIC, 12))
//        }.elseBlock {
//            add("acc", field("acc"), field("weight"))
//        }
//        assign("post_state", field("acc"))
//    }
//
//    val neuronTx = NeuronTx("neuron_phase")
//    neuronTx.addField("vm", 12, TxFieldType.DYNAMIC)
//    neuronTx.addField("spike_flag", 1, TxFieldType.LOCAL)
//    neuronTx.addStaticFieldFromArch(arch, "threshold")
//    neuronTx.addStaticFieldFromArch(arch, "reset")
//
//    neuronTx.withNeuron {
//        ifCondition(field("vm"), ComparisonOp.GTE, field("threshold")) {
//            assign("vm", field("reset"))
//            assign("spike_flag", const(1))
//        }.elseBlock {
//            sub("vm", field("vm"), const(1))
//            assign("spike_flag", const(0))
//        }
//
//        val emitResult = emit("spike_flag")
//        ifCondition(emitResult, ComparisonOp.EQ, const(1)) {
//            assign("spike_flag", const(0))
//        }
//    }
//
//    println("Spike transaction AST:\n${spikeTx.toAst()}")
//    println()
//    println("Neuron transaction AST:\n${neuronTx.toAst()}")
//
//    println("Spike transaction (tree):\n${spikeTx.dumpTree()}")
//    println()
//    println("Neuron transaction (tree):\n${neuronTx.dumpTree()}")
//}
