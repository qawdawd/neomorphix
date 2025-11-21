package neuromorphix

import cyclix.Generic
import hwast.*

enum class SynOpKind { ADD, SUB, REPLACE }

// стало:
data class SynPhaseCfg(
    val name: String,
    val op: SynOpKind = SynOpKind.ADD,
    val preIdxWidth: Int,
    // ↓ новые поля (опциональные)
    val synParamField: String? = null,         // "w", "tag", ... (когда слово упакованное)
    val packedSlices: SynPackPlan? = null      // карта срезов, если слово packed
)

data class SynPhaseIF(
    val busy_o: hw_var,
    val done_o: hw_var,
    val curPre_o: hw_var,
    val curPost_o: hw_var
)

class SynapticPhase(private val instName: String = "syn_phase") {

    fun emit(
        g: Generic,
        cfg: SynPhaseCfg,

        // входящие спайки (FIFO)
        inFifo: FifoInIF,

        // селектор синапсов (уже привязан к StaticMemIF)
        sel: SynSelIF,

        // статическая память весов (dat_r = зарегистрированный выход из контроллера)
        wmem: StaticMemIF,

        // динамические параметры пост-нейронов
        dyn: DynParamIF,

        // ⬇️ опциональный «шлюз» от FSM (например, тик-окно). Если null — игнорируем.
        gate: hw_var? = null
    ): SynPhaseIF {

        val name = cfg.name

        // === служебные регистры ===
        val busy   = g.uglobal("${name}_busy", hw_dim_static(1), "0")
        val done   = g.uglobal("${name}_done", hw_dim_static(1), "0")
        val preLat = g.uglobal("${name}_pre",  hw_dim_static(cfg.preIdxWidth), "0")
        val curPre = preLat
        val curPost= sel.postIdx_o

        val busy_o = busy
        val done_o = done
        val curPre_o  = curPre
        val curPost_o = curPost

        // === FSM ===
        val S_IDLE     = 0
        val S_FETCH    = 1
        val S_STARTSEL = 2
        val S_RUN      = 3

        val state  = g.uglobal("${name}_st",  hw_dim_static(2), "0")
        val stateN = g.uglobal("${name}_stn", hw_dim_static(2), "0")
        state.assign(stateN)

        // дефолты
        done.assign(0)
        inFifo.rd_o.assign(0)
        sel.start_i.assign(0)

        // локальные условия
        val fifoHasData = g.eq2(inFifo.empty_o, 0)
        val gateOk = if (gate != null) g.eq2(gate, 1) else hw_imm(1)
        val canStart = g.land(gateOk, fifoHasData)

        // === IDLE ===
        g.begif(g.eq2(state, S_IDLE)); run {
            busy.assign(0)

            g.begif(g.eq2(canStart, 1)); run {
            // запросить чтение на один такт
            inFifo.rd_o.assign(1)
            stateN.assign(S_FETCH)
        }; g.endif()

            g.begelse(); run {
            // очередь пуста (или gate закрыт) — сигнализируем завершение фазы
            done.assign(1)
        }; g.endif()
        }; g.endif()

        // === FETCH: зафиксировать пресинапт. индекс и старт селектора ===
        g.begif(g.eq2(state, S_FETCH)); run {
            busy.assign(1)
            inFifo.rd_o.assign(0)               // снять импульс чтения
            preLat.assign(inFifo.rd_data_o)

            sel.preIdx_i.assign(inFifo.rd_data_o)
            sel.start_i.assign(1)               // импульс старта
            stateN.assign(S_STARTSEL)
        }; g.endif()

        // === STARTSEL: обнулить start, перейти в RUN ===
        g.begif(g.eq2(state, S_STARTSEL)); run {
            busy.assign(1)
            sel.start_i.assign(0)
            stateN.assign(S_RUN)
        }; g.endif()

// === RUN: на каждом шаге берём слово из wmem и применяем операцию ===
        g.begif(g.eq2(state, S_RUN)); run {
            busy.assign(1)

            dyn.rd_idx.assign(sel.postIdx_o)

            // --- НОВОЕ: выбираем источник значения из памяти (упаковка/без упаковки)
            val valueForOp =
                if (cfg.packedSlices != null && cfg.synParamField != null) {
                    val sl = cfg.packedSlices.sliceOf(cfg.synParamField)
                    // срез [msb:lsb] из прочитанного слова памяти
                    wmem.dat_r[sl.msb, sl.lsb]   // <-- это hw_var.get(msb:Int, lsb:Int)
                } else {
                    wmem.dat_r
                }

            // прежняя логика операции над dyn.rd_data и значением из памяти
            val newVal = when (cfg.op) {
                SynOpKind.ADD     -> dyn.rd_data.plus(valueForOp)
                SynOpKind.SUB     -> dyn.rd_data.minus(valueForOp)
                SynOpKind.REPLACE -> valueForOp
            }

            dyn.wr_idx.assign(sel.postIdx_o)
            dyn.wr_data.assign(newVal)
            dyn.we.assign(1)

            g.begif(g.eq2(sel.done_o, 1)); run {
            dyn.we.assign(0)
            stateN.assign(S_IDLE)
        }; g.endif()
        }; g.endif()

        return SynPhaseIF(
            busy_o = busy_o,
            done_o = done_o,
            curPre_o = curPre_o,
            curPost_o = curPost_o
        )
    }
}
