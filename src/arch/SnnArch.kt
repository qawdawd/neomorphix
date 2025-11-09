package arch

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Descriptor for a static parameter used by neuron transactions.
 */
data class StaticParamDescriptor(
    val name: String,
    val bitWidth: Int
) {
    init {
        require(name.isNotBlank()) { "Static parameter name must not be blank" }
        require(bitWidth > 0) { "Static parameter width must be positive" }
    }
}

/**
 * Connectivity type descriptor. It stores an identifier that can be extended later with
 * additional metadata without breaking the existing API.
 */
data class ConnectivityType(val id: String) {
    init {
        require(id.isNotBlank()) { "Connectivity type id must not be blank" }
    }

    companion object {
        val FULLY_CONNECTED = ConnectivityType("fully_connected")

        fun fromId(value: String?): ConnectivityType =
            when {
                value.isNullOrBlank() -> FULLY_CONNECTED
                value == FULLY_CONNECTED.id -> FULLY_CONNECTED
                else -> ConnectivityType(value)
            }
    }
}

/**
 * Derived architectural widths and counts calculated from the base configuration.
 */
data class ArchWidths(
    val layerIndexWidth: Int,
    val neuronIndexWidths: List<Int>,
    val totalNeuronCount: Int,
    val neuronGlobalIdWidth: Int,
    val synapseAddressWidth: Int,
    val totalSynapseCount: Int
)

/**
 * Representation of a spiking neural network architecture used by the CAD flow.
 */
class SnnArch(
    val layerCount: Int,
    val neuronsPerLayer: List<Int>,
    val connectivity: ConnectivityType,
    val staticParameters: List<StaticParamDescriptor>
) {

    init {
        require(layerCount > 0) { "Layer count must be positive" }
        require(neuronsPerLayer.size == layerCount) {
            "Neuron list size (${neuronsPerLayer.size}) must match layer count ($layerCount)"
        }
        require(neuronsPerLayer.all { it > 0 }) { "Neuron counts must be positive" }
    }

    private var cachedWidths: ArchWidths? = null

    fun deriveWidths(): ArchWidths {
        val layerIndexWidth = bitWidthForCount(layerCount)
        val neuronIndexWidths = neuronsPerLayer.map { bitWidthForCount(it) }
        val totalNeuronCount = neuronsPerLayer.fold(0L) { acc, value -> acc + value }
        require(totalNeuronCount <= Int.MAX_VALUE) {
            "Total neuron count exceeds supported range: $totalNeuronCount"
        }
        val totalNeuronCountInt = totalNeuronCount.toInt()
        val neuronGlobalIdWidth = bitWidthForCount(max(totalNeuronCountInt, 1))
        val totalSynapseCount = computeSynapseCount()
        val synapseAddressWidth = bitWidthForCount(max(totalSynapseCount, 1))

        val widths = ArchWidths(
            layerIndexWidth = layerIndexWidth,
            neuronIndexWidths = neuronIndexWidths,
            totalNeuronCount = totalNeuronCountInt,
            neuronGlobalIdWidth = neuronGlobalIdWidth,
            synapseAddressWidth = synapseAddressWidth,
            totalSynapseCount = totalSynapseCount
        )

        cachedWidths = widths
        return widths
    }

    fun getDerivedWidths(): ArchWidths = cachedWidths ?: deriveWidths()

    fun staticParameterDescriptors(): List<StaticParamDescriptor> = staticParameters

    fun getStaticParameter(name: String): StaticParamDescriptor? =
        staticParameters.firstOrNull { it.name == name }

    fun describe(): String {
        val widths = getDerivedWidths()
        val builder = StringBuilder()
        builder.appendLine("SNN Architecture description")
        builder.appendLine("Layers: $layerCount")
        neuronsPerLayer.forEachIndexed { index, count ->
            val indexWidth = widths.neuronIndexWidths.getOrNull(index) ?: 1
            builder.appendLine("  Layer ${index + 1}: neurons=$count, indexWidth=${indexWidth}b")
        }
        builder.appendLine("Connectivity: ${connectivity.id}")
        builder.appendLine()
        builder.appendLine("Static parameters:")
        if (staticParameters.isEmpty()) {
            builder.appendLine("  (none)")
        } else {
            staticParameters.forEach {
                builder.appendLine("  - ${it.name}: width=${it.bitWidth}b")
            }
        }
        builder.appendLine()
        builder.appendLine("Derived widths:")
        builder.appendLine("  Layer index width: ${widths.layerIndexWidth}b")
        builder.appendLine("  Global neuron id width: ${widths.neuronGlobalIdWidth}b")
        builder.appendLine("  Synapse address width: ${widths.synapseAddressWidth}b")
        builder.appendLine("  Total neurons: ${widths.totalNeuronCount}")
        builder.appendLine("  Total synapses: ${widths.totalSynapseCount}")
        return builder.toString()
    }

    private fun computeSynapseCount(): Int {
        return when (connectivity) {
            ConnectivityType.FULLY_CONNECTED -> {
                if (neuronsPerLayer.size < 2) {
                    0
                } else {
                    var total = 0L
                    for (i in 0 until neuronsPerLayer.lastIndex) {
                        total += neuronsPerLayer[i].toLong() * neuronsPerLayer[i + 1].toLong()
                    }
                    require(total <= Int.MAX_VALUE) {
                        "Synapse count exceeds supported range: $total"
                    }
                    total.toInt()
                }
            }
            else -> 0
        }
    }

    private fun bitWidthForCount(count: Int): Int {
        require(count >= 0) { "Count must not be negative" }
        if (count <= 1) return 1
        var value = count - 1
        var width = 0
        while (value > 0) {
            width += 1
            value = value ushr 1
        }
        return max(width, 1)
    }

    companion object {
        fun fromConfig(jsonText: String): SnnArch {
            val root = JSONObject(jsonText)

            if (!root.has(KEY_LAYER_COUNT)) {
                error("'layerCount' must be provided as a number")
            }
            if (!root.has(KEY_NEURONS_PER_LAYER)) {
                error("'neuronsPerLayer' must be provided as an array of numbers")
            }

            val layerCount = root.getInt(KEY_LAYER_COUNT)
            val neuronsPerLayer = jsonArrayToIntList(root.getJSONArray(KEY_NEURONS_PER_LAYER))

            val connectivityId = if (root.has(KEY_CONNECTIVITY)) {
                root.getString(KEY_CONNECTIVITY)
            } else {
                null
            }

            val staticParameters = if (root.has(KEY_STATIC_PARAMETERS)) {
                parseStaticParameters(root.getJSONArray(KEY_STATIC_PARAMETERS))
            } else {
                emptyList()
            }

            return SnnArch(
                layerCount = layerCount,
                neuronsPerLayer = neuronsPerLayer,
                connectivity = ConnectivityType.fromId(connectivityId),
                staticParameters = staticParameters
            )
        }

        private fun jsonArrayToIntList(array: JSONArray): List<Int> {
            val result = ArrayList<Int>(array.length())
            for (index in 0 until array.length()) {
                val value = array.get(index)
                val number = value as? Number
                    ?: error("Neuron counts must be numbers (found '${value::class.simpleName}')")
                result += number.toInt()
            }
            return result
        }

        private fun parseStaticParameters(array: JSONArray): List<StaticParamDescriptor> {
            val result = ArrayList<StaticParamDescriptor>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                val name = entry.optString(KEY_NAME)
                if (name.isBlank()) {
                    error("Static parameter at index $index must have a non-blank 'name'")
                }
                if (!entry.has(KEY_WIDTH)) {
                    error("Static parameter '$name' must have a numeric width")
                }
                val width = entry.getNumber(KEY_WIDTH).toInt()
                result += StaticParamDescriptor(name, width)
            }
            return result
        }

        private fun JSONObject.getNumber(key: String): Number {
            val value = get(key)
            return value as? Number
                ?: error("Field '$key' must be numeric (found '${value::class.simpleName}')")
        }

        private const val KEY_LAYER_COUNT = "layerCount"
        private const val KEY_NEURONS_PER_LAYER = "neuronsPerLayer"
        private const val KEY_CONNECTIVITY = "connectivity"
        private const val KEY_STATIC_PARAMETERS = "staticParameters"
        private const val KEY_NAME = "name"
        private const val KEY_WIDTH = "width"
    }
}


