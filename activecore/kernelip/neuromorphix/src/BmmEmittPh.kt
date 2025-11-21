package neuromorphix

import cyclix.Generic
import hwast.*

enum class CmpKind { GT, LT, GE, LE }

/** Конфиг эмиттера спайков */
data class SpikeEmitCfg(
    val name: String,
    val idxWidth: Int,          // ширина счётчика пост-нейронов
    val cmp: CmpKind,           // тип сравнения (>, <, >=, <=)
    val cmpRegKey: String,      // ключ регистра для сравнения (например, "threshold")
    val refractory: Boolean = false,
    val resetRegKey: String? = null // обязателен, если refractory=true
)

/** Интерфейс статусов/управления эмиттера */
data class SpikeEmitIF(
    val start_i: hw_var,  // импульс старта
    val busy_o:  hw_var,  // занят
    val done_o:  hw_var,  // завершил проход
    val idx_o:   hw_var,  // текущий индекс
    val fired_o: hw_var   // импульс "был сгенерирован спайк" (для отладки)
)

/**
 * Эмиттер спайков:
 *  для idx=0..postsynCount-1:
 *    v := dyn[idx]
 *    if (cmp(v, regs[cmpRegKey])) {
 *       outFifo.we_i := 1; outFifo.wr_data_i := idx;
 *       if (refractory) dyn[idx] := regs[resetRegKey]
 *    }
 */
class SpikeEmitter(private val instName: String = "spike_emit") {

    fun emit(
        g: Generic,
        cfg: SpikeEmitCfg,

        // выходной FIFO (ядро -> внешний мир)
        out: FifoOutIF,

        // динамическая память (например, Vmemb)
        dyn: DynParamIF,

        // банк регистров
        regs: RegBankIF,

        // сколько пост-нейронов обрабатывать
        postsynCount: hw_var
    ): SpikeEmitIF {

        require(!cfg.refractory || cfg.resetRegKey != null) {
            "${cfg.name}: refractory=true требует resetRegKey"
        }

        val name = cfg.name

        // вход/статусы
        val start_i = g.uglobal("${name}_start", hw_dim_static(1), "0")
        val busy    = g.uglobal("${name}_busy",  hw_dim_static(1), "0")
        val done    = g.uglobal("${name}_done",  hw_dim_static(1), "0")
        val idx     = g.uglobal("${name}_idx",   hw_dim_static(cfg.idxWidth), "0")
        val fired   = g.uglobal("${name}_fired", hw_dim_static(1), "0")

        // наружу
        val busy_o  = busy
        val done_o  = done
        val idx_o   = idx
        val fired_o = fired

        // локальные ссылки на регистры
        val thrVal  = regs[cfg.cmpRegKey]   // значение порога/операнда сравнения
        val rstVal  = if (cfg.refractory) regs[cfg.resetRegKey!!] else null

        // FSM
        val S_IDLE  = 0
        val S_LOAD  = 1   // подать rd_idx, получить dyn.rd_data
        val S_EVAL  = 2   // сравнить, при необходимости записать в FIFO и (опционально) reset в dyn
        val state   = g.uglobal("${name}_st",  hw_dim_static(2), "0")
        val stateN  = g.uglobal("${name}_stn", hw_dim_static(2), "0")
        state.assign(stateN)

        // дефолты на такт
        done.assign(0)
        fired.assign(0)
        out.we_i.assign(0)
        dyn.we.assign(0)

        // === IDLE ===
        g.begif(g.eq2(state, S_IDLE)); run {
            busy.assign(0)
            g.begif(g.eq2(start_i, 1)); run {
            idx.assign(0)
            busy.assign(1)
            stateN.assign(S_LOAD)
        }; g.endif()
        }; g.endif()

        // === LOAD: инициировать чтение dyn[idx] ===
        g.begif(g.eq2(state, S_LOAD)); run {
            busy.assign(1)
            dyn.rd_idx.assign(idx)
            stateN.assign(S_EVAL)
        }; g.endif()

        // === EVAL: сравнить и, если нужно, сгенерировать спайк ===
        g.begif(g.eq2(state, S_EVAL)); run {
            busy.assign(1)

            // выражение сравнения: cmp(dyn.rd_data, thrVal)
            val cmpOk = when (cfg.cmp) {
                CmpKind.GT -> g.gr(dyn.rd_data, thrVal)          // >
                CmpKind.LT -> g.less(dyn.rd_data, thrVal)        // <
                CmpKind.GE -> g.geq(g.gr(dyn.rd_data, thrVal), g.eq2(dyn.rd_data, thrVal)) // >=
                CmpKind.LE -> g.leq(g.less(dyn.rd_data, thrVal), g.eq2(dyn.rd_data, thrVal)) // <=
            }

            // если условие истинно и FIFO не полон — пишем спайк
            val canWrite = g.land(cmpOk, g.bnot(out.full_o))

            g.begif(g.eq2(canWrite, 1)); run {
            out.we_i.assign(1)
            out.wr_data_i.assign(idx)     // номер нейрона как пэйлоад
            fired.assign(1)

            // рефрактер: сбросить динамический параметр
            if (cfg.refractory) {
                dyn.wr_idx.assign(idx)
                dyn.wr_data.assign(rstVal!!)
                dyn.we.assign(1)
            }
        }; g.endif()

            // если условие истинно, но FIFO полон — ждём
            val needButFull = g.land(cmpOk, out.full_o)
            g.begif(g.eq2(needButFull, 1)); run {
            // остаёмся в S_EVAL, пока FIFO не освободится
            stateN.assign(S_EVAL)
        }; g.endif()

            // иначе — двигаем индекс
            val proceed = g.bnot(needButFull)
            g.begif(g.eq2(proceed, 1)); run {
            val last = g.eq2(idx, g.sub(postsynCount, hw_imm(1)))
            g.begif(last); run {
            done.assign(1)
            busy.assign(0)
            stateN.assign(S_IDLE)
        }; g.endif()
            g.begelse(); run {
            idx.assign(idx.plus(1))
            stateN.assign(S_LOAD)
        }; g.endif()
        }; g.endif()
        }; g.endif()

        return SpikeEmitIF(
            start_i = start_i,
            busy_o  = busy_o,
            done_o  = done_o,
            idx_o   = idx_o,
            fired_o = fired_o
        )
    }
}