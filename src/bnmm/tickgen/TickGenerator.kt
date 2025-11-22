package bnmm.tickgen

import bnmm.description.TickGenConfig
import cyclix.Generic
import hwast.PORT_DIR
import hwast.hw_dim_static
import hwast.hw_var

/**
 * Простое ядро генератора тиков на основе счётчика.
 */
data class TickGenPorts(
    val tick: hw_var,
    val counter: hw_var,
    val enable: hw_var,
    val reset: hw_var
)

private object TickMath {
    fun log2ceil(value: Int): Int {
        require(value > 0) { "log2ceil: value must be > 0" }
        var v = value - 1
        var r = 0
        while (v > 0) {
            v = v shr 1
            r++
        }
        return maxOf(r, 1)
    }
}

class TickGenerator(private val instName: String = "tickgen") {

    fun emit(g: Generic, cfg: TickGenConfig): TickGenPorts {
        val ctrWidth = TickMath.log2ceil(cfg.periodCycles)
        val name = cfg.name

        val enable = g.uport("en_$name", PORT_DIR.IN, hw_dim_static(1), "1")
        val reset = g.uport("rst_$name", PORT_DIR.IN, hw_dim_static(1), "0")
        val tick = g.uglobal("tick_$name", hw_dim_static(1), "0")
        val counter = g.uglobal("ctr_$name", hw_dim_static(ctrWidth), "0")

        val running = g.land(enable, g.bnot(reset))

        g.begif(g.eq2(reset, 1)); run {
            counter.assign(0)
            tick.assign(0)
        }; g.endif()

        g.begif(g.eq2(running, 1)); run {
            val next = counter.plus(1)
            val wrap = g.eq2(next, cfg.periodCycles)
            g.begif(wrap); run {
                counter.assign(0)
                tick.assign(1)
            }; g.endif()
            g.begelse(); run {
                counter.assign(next)
                val active = g.less(counter, cfg.pulseWidthCycles)
                tick.assign(active)
            }; g.endif()
        }; g.endif()

        return TickGenPorts(
            tick = tick,
            counter = counter,
            enable = enable,
            reset = reset
        )
    }
}
