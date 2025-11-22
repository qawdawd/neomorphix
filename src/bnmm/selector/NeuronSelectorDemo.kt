package bnmm.selector

import cyclix.Generic
import hwast.DEBUG_LEVEL
import hwast.hw_dim_static

/**
 * Minimal demo that instantiates the neuron selector for manual inspection.
 * It uses a simple grouped traversal over a configurable number of neurons.
 */
fun main() {
    val g = Generic("neuron_selector_demo")

    val cfg = NeuronSelectorConfig(
        name = "demo_neu",
        indexWidth = 8,
        plan = NeuronSelectorPlan(groupSize = 4, totalGroups = 8, activeGroups = 8, remainder = 0),
        stepByTick = false
    )

    val totalNeurons = g.uglobal("total_neurons", hw_dim_static(cfg.indexWidth), "0")
    val baseIndex = g.uglobal("base_index", hw_dim_static(cfg.indexWidth), "0")

    val selector = NeuronSelector("demo")
    selector.emit(
        g = g,
        cfg = cfg,
        runtime = NeuronSelectorRuntime(
            totalNeurons = totalNeurons,
            baseIndex = baseIndex,
            tick = null
        )
    )

    val rtl = g.export_to_rtl(DEBUG_LEVEL.FULL)
    rtl.export_to_sv("out/${g.name}", DEBUG_LEVEL.FULL)
}
