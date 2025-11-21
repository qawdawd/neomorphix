package neuromorphix

import org.json.JSONObject
import java.io.File
import kotlin.math.max


/* ===== утилиты ===== */

//fun NmMath.log2ceil(v: Int): Int {
//    require(v > 0) { "NmMath.log2ceil: v must be > 0" }
//    var x = v - 1
//    var r = 0
//    while (x > 0) { x = x shr 1; r++ }
//    return max(1, r)           // не даём ширине упасть до 0
//}

fun clamp(v: Int, lo: Int, hi: Int) = when {
    v < lo -> lo
    v > hi -> hi
    else   -> v
}

/* ===== enumы верхнего уровня ===== */

enum class NeuralNetworkType { SFNN, SCNN }

//enum class TopologyKind { FULLY_CONNECTED, RECURRENT, SPARSE, CONV }

/* ===== первичные конфиги (то, что задаёт пользователь/JSON) ===== */

// габариты сети (один слой «для примера», можно расширить списком слоёв позже)
data class NnDims(
    val presynCount : Int,
    val postsynCount: Int,
    val topology    : TopologyKind = TopologyKind.FULLY_CONNECTED
)

// числовые параметры нейрона LIF/Ижикевича и т.п.
// сейчас только LIF-подмножество, при необходимости можно добавить поля a,b,c,d
data class NeuronParams(
    val threshold : Int = 1,
    val reset     : Int = 0,
    val leakage   : Int = 1
)

// «сырой» формат представления чисел (бит-ширины слов и т.п.)
data class NumericLayout(
    val weightWidth    : Int = 16,
    val potentialWidth : Int = 16
)

/* ===== производные (вычисляются автоматически) ===== */

data class DerivedWidths(
    val presynIdxW   : Int,
    val postsynIdxW  : Int,
    val weightW      : Int,
    val potentialW   : Int,
    val thresholdW   : Int,
    val resetW       : Int,
    val leakageW     : Int,
    // полезно иметь: ширина для идентификатора спайка
    val spikeIdW     : Int,
    // адресная ширина памяти весов (presyn*postsyn)
    val weightAddrW  : Int
)

/* ===== корневой класс арх-конфига ===== */

class SnnArch(
    val modelName   : String = "Default",
    val nnType      : NeuralNetworkType = NeuralNetworkType.SFNN,
    val dims        : NnDims = NnDims(presynCount = 28*28, postsynCount = 128), // я поправил 28*25 -> 28*28
    val neuron      : NeuronParams = NeuronParams(),
    val numeric     : NumericLayout = NumericLayout()
) {

    // производные величины (лениво, чтобы всегда были консистентны)
    val d: DerivedWidths by lazy { derive() }

    private fun derive(): DerivedWidths {
        require(dims.presynCount  > 0) { "presynCount must be > 0" }
        require(dims.postsynCount > 0) { "postsynCount must be > 0" }
        require(numeric.weightWidth    > 0) { "weightWidth must be > 0" }
        require(numeric.potentialWidth > 0) { "potentialWidth must be > 0" }

        val presynIdxW  = NmMath.log2ceil(dims.presynCount)
        val postsynIdxW = NmMath.log2ceil(dims.postsynCount)

        // максимально допустимые значения — для оценки ширин конфиг-регистров
        val thresholdW  = NmMath.log2ceil(max(1, neuron.threshold))
        val resetW      = NmMath.log2ceil(max(1, neuron.reset + 1))     // +1 чтобы кодировать 0..reset
        val leakageW    = NmMath.log2ceil(max(1, neuron.leakage))
        val spikeIdW    = presynIdxW  // часто «id спайка» кодируют индексом пресинапт. нейрона

        val weightDepth = dims.presynCount * dims.postsynCount
        val weightAddrW = NmMath.log2ceil(weightDepth)

        return DerivedWidths(
            presynIdxW   = presynIdxW,
            postsynIdxW  = postsynIdxW,
            weightW      = numeric.weightWidth,
            potentialW   = numeric.potentialWidth,
            thresholdW   = thresholdW,
            resetW       = resetW,
            leakageW     = leakageW,
            spikeIdW     = spikeIdW,
            weightAddrW  = weightAddrW
        )
    }

    fun info(): String = buildString {
        appendLine("SnnArch[$modelName]: $nnType, topology=${dims.topology}")
        appendLine("  presyn=${dims.presynCount} (w=${d.presynIdxW}), postsyn=${dims.postsynCount} (w=${d.postsynIdxW})")
        appendLine("  weightW=${d.weightW}, potentialW=${d.potentialW}")
        appendLine("  thr=${neuron.threshold} (w=${d.thresholdW}), rst=${neuron.reset} (w=${d.resetW}), leak=${neuron.leakage} (w=${d.leakageW})")
        appendLine("  spikeIdW=${d.spikeIdW}, weightAddrW=${d.weightAddrW}")
    }

    /* ===== загрузка из JSON файла (минимально-инвазивно) =====
       Ожидаем примерно такую структуру:
       {
         "nn_type": "SFNN",
         "model_topology": { "PresynNeuronsCount": 784, "PostsynNeuronsCount": 128, "topology": "FULLY_CONNECTED" },
         "numeric_layout": { "weightWidth": 16, "potentialWidth": 16 },
         "LIF_neurons": { "lif1": { "threshold": 1, "reset": 0, "leakage": 1 } },
         "model_name": "foo"
       }
    */
    companion object {
        fun fromJsonFile(path: String): SnnArch {
            val jo = JSONObject(File(path).readText())

            val modelName = jo.optString("model_name", "Default")
            val nnType    = jo.optString("nn_type", "SFNN").let { NeuralNetworkType.valueOf(it) }

            val topo = jo.optJSONObject("model_topology")
            val presyn = topo?.optInt("PresynNeuronsCount", 28*28) ?: 28*28
            val postsyn = topo?.optInt("PostsynNeuronsCount", 128) ?: 128
            val topoKind = topo?.optString("topology", "FULLY_CONNECTED") ?: "FULLY_CONNECTED"
            val kind = TopologyKind.valueOf(topoKind)

            val dims = NnDims(presynCount = presyn, postsynCount = postsyn, topology = kind)

            val num = jo.optJSONObject("numeric_layout")
            val weightW    = num?.optInt("weightWidth", 16) ?: 16
            val potentialW = num?.optInt("potentialWidth", 16) ?: 16
            val numeric = NumericLayout(weightW, potentialW)

            val lif = jo.optJSONObject("LIF_neurons")?.optJSONObject("lif1")
            val thr = lif?.optInt("threshold", 1) ?: 1
            val rst = lif?.optInt("reset", 0) ?: 0
            val lkg = lif?.optInt("leakage", 1) ?: 1
            val neuron = NeuronParams(threshold = thr, reset = rst, leakage = lkg)

            return SnnArch(
                modelName = modelName,
                nnType    = nnType,
                dims      = dims,
                neuron    = neuron,
                numeric   = numeric
            )
        }
    }
}