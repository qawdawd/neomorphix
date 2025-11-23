package bnmm.controller

import cyclix.Generic
import hwast.DEBUG_LEVEL
import hwast.hw_dim_static
import hwast.hw_imm

private object DemoMath {
    fun log2ceil(value: Int): Int {
        require(value > 0) { "log2ceil expects positive value" }
        var v = value - 1
        var r = 0
        while (v > 0) { v = v shr 1; r++ }
        return if (r == 0) 1 else r
    }
}

private fun createDummyPhase(
    g: Generic,
    name: String,
    duration: Int
): PhaseControlPorts {
    require(duration > 0) { "duration must be positive" }

    val start = g.uglobal("start_$name", hw_dim_static(1), "0")
    val busy = g.uglobal("busy_$name", hw_dim_static(1), "0")
    val done = g.uglobal("done_$name", hw_dim_static(1), "0")

    val active = g.uglobal("active_$name", hw_dim_static(1), "0")
    val counter = g.uglobal("cnt_$name", hw_dim_static(DemoMath.log2ceil(duration)), "0")

    busy.assign(0)
    done.assign(0)

    // Latch start into active flag
    g.begif(g.eq2(start, 1)); run {
        active.assign(1)
        counter.assign(0)
        busy.assign(1)
        done.assign(0)
    }; g.endif()

    // Simple duration-based completion
    g.begif(g.eq2(active, 1)); run {
        busy.assign(1)
        g.begif(g.eq2(counter, hw_imm(duration - 1))); run {
        active.assign(0)
        done.assign(1)
    }; g.endif()
        g.begelse(); run {
        counter.assign(g.add(counter, hw_imm(1)))
    }; g.endif()
    }; g.endif()

    return PhaseControlPorts(start = start, busy = busy, done = done)
}

/**
 * Minimal standalone demo that wires two dummy phases into the controller FSM
 * for manual RTL export.
 */
fun main() {
    val g = Generic("controller_demo")

    val phaseA = createDummyPhase(g, name = "phaseA", duration = 2)
    val phaseB = createDummyPhase(g, name = "phaseB", duration = 3)

    val plan = ControlPlan(listOf(ControlPhase("phaseA"), ControlPhase("phaseB")))

    val controller = Controller("demo_ctrl")
    controller.emit(
        g = g,
        cfg = ControllerConfig(name = "demo_ctrl", loop = true, stepByTick = false),
        plan = plan,
        phasePorts = mapOf(
            "phaseA" to phaseA,
            "phaseB" to phaseB
        ),
        tick = null
    )

    val rtl = g.export_to_rtl(DEBUG_LEVEL.FULL)
    rtl.export_to_sv("out/${g.name}", DEBUG_LEVEL.FULL)
}
