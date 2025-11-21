package neuromorphix

import cyclix.Generic
import hwast.hw_imm
import hwast.hw_var
import hwast.*

// ===== топология связности =====
enum class TopologyKind {
    FULLY_CONNECTED,
    RECURRENT,
    SPARSE,
    CONV
}

// Можно расширять полями под конкретные топологии (stride, kernel, списки смежности и т.п.)
data class TopologySpec(
    val kind: TopologyKind = TopologyKind.FULLY_CONNECTED
)

// ===== рантайм-регистры/параметры, приходящие "снаружи" =====
// Здесь мы НЕ создаём регистры — просто принимаем ссылки на уже созданные hw_var
data class RegIf(
    val postsynCount: hw_var,     // реальное число постсинаптических нейронов для обхода
    val baseAddr:     hw_var? = null // опционально: базовый адрес в памяти весов
)

// ===== интерфейс селектора наружу =====
data class SynSelIF(
    // вход управления
    val start_i:   hw_var,     // импульс "начать обход для указанного preIdx"
    val preIdx_i:  hw_var,     // индекс пресинапс. нейрона

    // выходы статуса
    val busy_o:    hw_var,     // селектор занят (идёт обход)
    val done_o:    hw_var,     // единичный импульс по завершению обхода

    // отладка/наблюдение
    val postIdx_o: hw_var      // текущий пост-индекс
)

// ===== конфигурация селектора =====
data class SynSelCfg(
    val name:          String,
    val addrWidth:     Int,        // ширина адреса в StaticMem
    val preWidth:      Int,        // ширина preIdx
    val postWidth:     Int,        // максимальная ширина postIdx-счётчика
    val stepByTick:    Boolean = false, // инкрементировать по tick (true) или каждый такт (false)
    val useLinearAddr: Boolean = true   // адресация линейная (pre*postsyn+post) или конкатенацией
)

// ===== селектор синапсов =====
class SynapseSelector(private val instName: String = "syn_sel") {

    // было: private fun addrConcat(...): hw_expr = g.cnct(...)
    private fun addrConcat(g: Generic, preIdx: hw_var, postIdx: hw_var) =
        g.cnct(preIdx, postIdx)

    // было: private fun addrLinear(...): hw_expr = g.add(g.mul(...), postIdx)
    private fun addrLinear(g: Generic, preIdx: hw_var, postIdx: hw_var, postsynCount: hw_var) =
        g.add(g.mul(preIdx, postsynCount), postIdx)

    /**
     * Генерация селектора:
     * - принимает TopologySpec, RegIf, tick (опц.), интерфейс статической памяти (StaticMemIf)
     * - создаёт простую FSM: IDLE→RUN→(DONE)
     * - в RUN перебирает postIdx от 0 до (postsynCount-1), выдаёт адрес в memIf.adr_r и ставит memIf.en_r=1
     */
    fun emit(
        g: Generic,
        cfg: SynSelCfg,
        topo: TopologySpec,
        rt: RegIf,
        tick: hw_var?,                     // если cfg.stepByTick=true — используем этот тик
        mem: StaticMemIF                   // интерфейс к памяти (адрес/вкл/данные)
    ): SynSelIF {

        val name = cfg.name

        // ===== управляющие входы (локальные/globals, чтобы подключить их извне нейроморфика)
        val start_i  = g.uglobal("start_$name", hw_dim_static(1), "0")
        val preIdx_i = g.uglobal("preidx_$name", hw_dim_static(cfg.preWidth), "0")

        // ===== статус/наблюдение
        val busy_o   = g.uglobal("busy_$name", hw_dim_static(1), "0")
        val done_o   = g.uglobal("done_$name", hw_dim_static(1), "0")
        val postIdx  = g.uglobal("postidx_$name", hw_dim_static(cfg.postWidth), "0")
        val preLatched = g.uglobal("prelatched_$name", hw_dim_static(cfg.preWidth), "0")

        // внутренний enable шага
        val step_en = g.uglobal("step_en_$name", hw_dim_static(1), "0")
        step_en.assign(
            if (cfg.stepByTick) {
                if (tick != null) g.eq2(tick, 1) else hw_imm(0)   // защита от null
            } else {
                hw_imm(1)
            }
        )

        // == FSM ==
        val S_IDLE = 0
        val S_RUN  = 1
        val state  = g.uglobal("state_$name", hw_dim_static(1), "0")
        val state_n= g.uglobal("state_n_$name", hw_dim_static(1), "0")

        // дефолты
        state.assign(state_n)
        done_o.assign(0)

        // mem.en_r: по умолчанию 0, включаем только на акт. шаг
        mem.en_r?.assign(0)

        // Локальный сигнал «делаем шаг»
        val doStep = g.land(step_en, busy_o)

        // ===== IDLE: захват preIdx, обнуление postIdx =====
        g.begif(g.eq2(state, S_IDLE)); run {
            busy_o.assign(0)

            g.begif(g.eq2(start_i, 1)); run {
            preLatched.assign(preIdx_i)
            postIdx.assign(0)
            busy_o.assign(1)
            state_n.assign(S_RUN)
        }; g.endif()
        }; g.endif()

        // ===== RUN: перебор пост-индексов, генерация адреса =====
        g.begif(g.eq2(state, S_RUN)); run {
            busy_o.assign(1)

            // вычисляем адрес для текущей пары (preLatched, postIdx)
            val addrExpr = when (topo.kind) {
                TopologyKind.FULLY_CONNECTED -> {
                    if (cfg.useLinearAddr) {
                        val adrLin = addrLinear(g, preLatched, postIdx, rt.postsynCount)
                        // прибавляем baseAddr, если она задана
                        if (rt.baseAddr != null) g.add(adrLin, rt.baseAddr) else adrLin
                    } else {
                        // конкатенация (корректно, если глубины — степени двойки)
                        val adrCat = addrConcat(g, preLatched, postIdx)
                        if (rt.baseAddr != null) g.add(adrCat, rt.baseAddr) else adrCat
                    }
                }
                // заглушки на будущее: здесь ты подставишь свою адресацию
                TopologyKind.RECURRENT,
                TopologyKind.SPARSE,
                TopologyKind.CONV -> {
                    // пока просто линейная (временная заглушка)
                    val adrLin = addrLinear(g, preLatched, postIdx, rt.postsynCount)
                    if (rt.baseAddr != null) g.add(adrLin, rt.baseAddr) else adrLin
                }
            }

            // выдаём адрес и импульс чтения
            mem.adr_r.assign(addrExpr)
            mem.en_r?.assign(1)

            // шаг по postIdx по разрешению
            g.begif(g.eq2(doStep, 1)); run {
            val last = g.eq2(postIdx, g.sub(rt.postsynCount, hw_imm(1)))
            g.begif(last); run {
            // завершаем обход
            done_o.assign(1)
            busy_o.assign(0)
            state_n.assign(S_IDLE)
        }; g.endif()
            g.begelse(); run {
            postIdx.assign(postIdx.plus(1))
        }; g.endif()
        }; g.endif()
        }; g.endif()

        return SynSelIF(
            start_i   = start_i,
            preIdx_i  = preIdx_i,
            busy_o    = busy_o,
            done_o    = done_o,
            postIdx_o = postIdx
        )
    }
}
