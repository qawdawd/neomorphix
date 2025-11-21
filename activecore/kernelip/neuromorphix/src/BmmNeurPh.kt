package neuromorphix

import cyclix.Generic
import hwast.*

enum class NeurOpKind { ADD, SUB, SHL, SHR }

/** Описание одной операции: аргумент берём из регистра с ключом regKey в RegBankIF */
data class NeurOpSpec(
    val kind: NeurOpKind,
    val regKey: String
)

/** Конфиг нейронной фазы */
data class NeurPhaseCfg(
    val name: String,
    val idxWidth: Int,         // ширина счётчика постсинаптических нейронов
    val dataWidth: Int,        // ширина динамического параметра (например, Vmemb.bitWidth)
    val ops: List<NeurOpSpec>  // последовательность операций
)

/** Интерфейс статусов нейронной фазы */
data class NeurPhaseIF(
    val start_i: hw_var,
    val busy_o:  hw_var,
    val done_o:  hw_var,
    val idx_o:   hw_var
)

class NeuronalPhase(private val instName: String = "neur_phase") {

    fun emit(
        g: Generic,
        cfg: NeurPhaseCfg,
        dyn: DynParamIF,     // динамическая память (Vmemb и т.п.)
        regs: RegBankIF,     // банк регистров (аргументы операций)
        postsynCount: hw_var // сколько элементов обрабатывать
    ): NeurPhaseIF {

        val name = cfg.name

        // вход «старт»
        val start_i = g.uglobal("${name}_start", hw_dim_static(1), "0")

        // статусы
        val busy = g.uglobal("${name}_busy", hw_dim_static(1), "0")
        val done = g.uglobal("${name}_done", hw_dim_static(1), "0")
        val idx  = g.uglobal("${name}_idx",  hw_dim_static(cfg.idxWidth), "0")

        // FSM
        val S_IDLE  = 0
        val S_LOAD  = 1
        val S_APPLY = 2
        val state  = g.uglobal("${name}_st",  hw_dim_static(2), "0")
        val stateN = g.uglobal("${name}_stn", hw_dim_static(2), "0")
        state.assign(stateN)

        // аккумулятор (локальный регистр ширины dataWidth)
        val acc = g.ulocal("${name}_acc", hw_dim_static(cfg.dataWidth), "0")

        // дефолты
        done.assign(0)
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

        // === LOAD: подготовить чтение dyn[idx] ===
        g.begif(g.eq2(state, S_LOAD)); run {
            busy.assign(1)
            dyn.rd_idx.assign(idx)      // комбин.: dyn.rd_data := mem[idx]
            stateN.assign(S_APPLY)
        }; g.endif()

        // === APPLY: acc = fold(ops, dyn.rd_data); запись обратно ===
        g.begif(g.eq2(state, S_APPLY)); run {
            busy.assign(1)

            // стартовое значение аккумулятора
            acc.assign(dyn.rd_data)

            // последовательно применяем операции
            for (op in cfg.ops) {
                val src = regs[op.regKey]  // аргумент операции из банка регистров
                when (op.kind) {
                    NeurOpKind.ADD -> acc.assign(acc.plus(src))
                    NeurOpKind.SUB -> acc.assign(acc.minus(src))
                    NeurOpKind.SHL -> acc.assign(g.sll(acc, src))  // лог. сдвиг влево
                    NeurOpKind.SHR -> acc.assign(g.srl(acc, src))  // лог. сдвиг вправо
                }
            }

            // запись результата
            dyn.wr_idx.assign(idx)
            dyn.wr_data.assign(acc)
            dyn.we.assign(1)

            // завершили элемент?
            val last = g.eq2(idx, g.sub(postsynCount, hw_imm(1)))
            g.begif(last); run {
            dyn.we.assign(0)
            done.assign(1)
            busy.assign(0)
            stateN.assign(S_IDLE)
        }; g.endif()
            g.begelse(); run {
            dyn.we.assign(0)
            idx.assign(idx.plus(1))
            stateN.assign(S_LOAD)
        }; g.endif()
        }; g.endif()

        return NeurPhaseIF(
            start_i = start_i,
            busy_o  = busy,
            done_o  = done,
            idx_o   = idx
        )
    }
}