package bnmm.queue

import cyclix.Generic
import hwast.DEBUG_LEVEL
import hwast.PORT_DIR
import hwast.hw_dim_static

/**
* Minimal demo that instantiates input and output FIFOs for manual inspection or
* hand-built neuro-core experiments. No stimulus is generated here; the goal is
* to ensure the queues can be emitted standalone via Cyclix.
*/
fun main() {
    val g = Generic("fifo_demo")

    val tick = g.uport("tick", PORT_DIR.IN, hw_dim_static(1), "0")

    val inCfg = FifoConfig(name = "in", dataWidth = 16, depth = 8)
    val outCfg = FifoConfig(name = "out", dataWidth = 16, depth = 8)

    val inFifo = FifoInput().emit(g, inCfg, tick)
    val outFifo = FifoOutput().emit(g, outCfg, tick)

    // Simple pass-through wiring to illustrate usage in a standalone design.
    outFifo.we_i.assign(inFifo.rd_o)
    outFifo.wr_data_i.assign(inFifo.rd_data_o)
    inFifo.rd_o.assign(outFifo.rd_i)

    val rtl = g.export_to_rtl(DEBUG_LEVEL.FULL)
    rtl.export_to_sv("out/${g.name}", DEBUG_LEVEL.FULL)
}
