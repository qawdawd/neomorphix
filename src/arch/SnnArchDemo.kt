package arch

/**
 * Simple executable snippet that demonstrates how to construct and inspect an [SnnArch]
 * instance. This can be compiled or executed from a Kotlin REPL to verify the behaviour of
 * the architecture model.
 * The printed output reflects the internal state of the constructed SnnArch instance:
 * 1.	General architecture description
 * •	The architecture contains two layers.
 * •	The first layer has 4 neurons, and the second layer has 3 neurons.
 * •	The connectivity mode is fully_connected.
 * 2.	Static parameters
 * Three static parameter descriptors are listed:
 * •	threshold with width 8 bits
 * •	reset with width 8 bits
 * •	leak with width 6 bits
 * 3.	Derived widths and counts
 * •	Layer index width — the number of bits required to represent layer indices.
 * •	neuronIndexWidths — bit widths for indexing neurons within each layer.
 * •	totalNeuronCount — the sum of neurons across all layers.
 * •	neuronGlobalIdWidth — bit width for addressing all neurons globally.
 * •	totalSynapseCount — the number of synapses implied by the selected connectivity model.
 * •	synapseAddressWidth — bit width for addressing synapses.
 * 4.	Object representations
 * The ArchWidths data class is printed using its default toString() representation, displaying all derived width fields and counts.
 * The descriptor for the static parameter named "threshold" is printed in the same manner.
 */

fun main() {
//    val configJson = """
//        {
//          "layerCount": 2,
//          "neuronsPerLayer": [4, 3],
//          "connectivity": "fully_connected",
//          "staticParameters": [
//            { "name": "threshold", "width": 8 },
//            { "name": "reset", "width": 8 },
//            { "name": "leak", "width": 6 }
//          ]
//        }
//    """.trimIndent()
//
//    val arch = SnnArch.fromConfig(configJson)
//
//    println(arch.describe())
//    println("Derived widths object: ${arch.getDerivedWidths()}")
//    println("Threshold descriptor: ${arch.getStaticParameter("threshold")}")
//
    // Пример статических параметров нейронной модели
    val staticParams = listOf(
        StaticParamDescriptor(name = "threshold", bitWidth = 8),
        StaticParamDescriptor(name = "reset", bitWidth = 8),
        StaticParamDescriptor(name = "leak", bitWidth = 6)
    )

    // Прямая конструкция архитектуры
    val arch = SnnArch(
        layerCount = 2,
        neuronsPerLayer = listOf(4, 3),
        connectivity = ConnectivityType.FULLY_CONNECTED,
        staticParameters = staticParams
    )

    // Печатаем описание
    println(arch.describe())

    // Используем derived widths
    val widths = arch.getDerivedWidths()
    println("Derived widths: $widths")

    // Получаем конкретный статический параметр
    val threshold = arch.getStaticParameter("threshold")
    println("Threshold descriptor: $threshold")

}