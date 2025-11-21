package neuromorphix

import cyclix.Generic
import hwast.hw_imm
import hwast.hw_var


// ===== TickGen: конфиг + компонент =====
// TickGen.kt

enum class TimeUnit { NS, US, MS, S }

data class TickGenCfg(
    val timeslot: Long,   // период «тика» в выбранных единицах
    val unit: TimeUnit,   // NS/US/MS/S
    val clkPeriodNs: Long // период такта в наносекундах (например, 10нс для 100МГц)
)

class TickGen(private val name: String = "tickgen") {

    private fun unitToNs(u: TimeUnit): Long = when (u) {
        TimeUnit.NS -> 1L
        TimeUnit.US -> 1_000L
        TimeUnit.MS -> 1_000_000L
        TimeUnit.S  -> 1_000_000_000L
    }

    /**
     * Генерит однотактный импульс tick каждые N тактов.
     * - tick = 1 на один такт при совпадении счётчика.
     * - Иначе tick = 0 и счётчик инкрементится.
     */
    fun emit(
        g: Generic,
        cfg: TickGenCfg,
        tickSignal: hw_var
    ) {
        require(cfg.timeslot > 0) { "$name: timeslot must be > 0" }
        require(cfg.clkPeriodNs > 0) { "$name: clkPeriodNs must be > 0" }

        val timeslotNs = cfg.timeslot * unitToNs(cfg.unit)
        var tickCycles = timeslotNs / cfg.clkPeriodNs
        if (tickCycles <= 0L) tickCycles = 1L

        val period    = g.uglobal("${name}_period",  hw_imm(tickCycles.toInt()))
        val counter   = g.uglobal("${name}_counter", "0")
        val lastCount = g.uglobal("${name}_last",    hw_imm((tickCycles - 1).toInt()))
        val nextCnt   = g.uglobal("${name}_next",    "0")

        // tick по умолчанию 0 на каждом такте
        tickSignal.assign(0)

        // if (counter == lastCount) { tick=1; counter=0; } else { counter++; }
        g.begif(g.eq2(counter, lastCount)); run {
            tickSignal.assign(1)
            counter.assign(0)
        }; g.endif()

        g.begelse(); run {
            nextCnt.assign(counter.plus(1))
            counter.assign(nextCnt)
        }; g.endif()
    }
}
