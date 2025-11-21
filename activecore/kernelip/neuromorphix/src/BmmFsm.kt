package neuromorphix

import cyclix.Generic
import hwast.*

/** Список состояний верхнеуровневого автомата ядра */
private enum class CoreState(val code: Int) {
    IDLE(0),            // ожидание en_core
    WAIT_TICK_SYN(1),   // ждать тик для старта синфазы
    SYN(2),             // синаптическая фаза (SynapticPhase)
    WAIT_TICK_NEUR(3),  // ждать следующий тик для старта нейронной фазы
    NEUR(4),            // нейронная фаза (NeuronalPhase)
    EMIT(5)             // эмиссия спайков (SpikeEmitter)
}

/** Интерфейс FSM наружу (можно расширять при необходимости) */
data class CoreFsmIF(
    val en_core_i: hw_var,   // IN порт enable (внешний)
    val state_o:  hw_var,    // текущий код состояния (для отладки)
    // однотактовые старты фаз (генерятся FSM):
    val neur_start_o: hw_var,
    val emit_start_o: hw_var,
    // опциональный хук для синфазы: можно завести gate во внутрь SynapticPhase
    val syn_gate_o:  hw_var
)

/** Верхнеуровневый FSM вычислительного ядра. */
class CoreFSM(private val instName: String = "core_fsm") {

    fun emit(
        g: Generic,

        // системные сигналы
        tick: hw_var,

        // интерфейсы фаз
        synIf:  SynPhaseIF,     // у SynapticPhase нет стартового входа — она сама кушает FIFO; используем done_o
        neurIf: NeurPhaseIF,    // есть start_i / done_o
        emitIf: SpikeEmitIF     // есть start_i / done_o
    ): CoreFsmIF {

        val name = instName

        // ===== внешний enable, читаем через регистр =====
        val en_core_i = g.uport("en_core", PORT_DIR.IN, hw_dim_static(1), "0")
        val en_core_r = g.uglobal("reg_en_core", hw_dim_static(1), "0")
        en_core_r.assign(en_core_i)

        // ===== состояние =====
        val st    = g.uglobal("${name}_state",  hw_dim_static(3), "0")    // 3 бита хватит на 6 состояний
        val st_n  = g.uglobal("${name}_stateN", hw_dim_static(3), "0")
        st.assign(st_n)

        // для отладки наружу
        val state_o = st

        // ===== однотактовые старты фаз =====
        val neur_start = g.uglobal("${name}_neur_start", hw_dim_static(1), "0")
        val emit_start = g.uglobal("${name}_emit_start", hw_dim_static(1), "0")
        val syn_gate   = g.uglobal("${name}_syn_gate",   hw_dim_static(1), "0")

        // дефолты (снимаем импульсы)
        neur_start.assign(0)
        emit_start.assign(0)
        syn_gate.assign(0)

        // Проброс стартов в соответствующие фазы
        neurIf.start_i.assign(neur_start)
        emitIf.start_i.assign(emit_start)

        // ====== IDLE ======
        g.begif(g.eq2(st, CoreState.IDLE.code)); run {
            // ждём enable
            g.begif(g.eq2(en_core_r, 1)); run {
            st_n.assign(CoreState.WAIT_TICK_SYN.code)
        }; g.endif()
        }; g.endif()

        // ====== WAIT_TICK_SYN: дождаться тика и разрешить синфазу ======
        g.begif(g.eq2(st, CoreState.WAIT_TICK_SYN.code)); run {
            g.begif(g.eq2(tick, 1)); run {
            // однократно даём gate (если используется внутри SynapticPhase)
            syn_gate.assign(1)
            st_n.assign(CoreState.SYN.code)
        }; g.endif()
        }; g.endif()

        // ====== SYN: ждём завершения синаптической фазы ======
        g.begif(g.eq2(st, CoreState.SYN.code)); run {
            // Синфаза работает сама по себе; мы просто ждём её done
            g.begif(g.eq2(synIf.done_o, 1)); run {
            st_n.assign(CoreState.WAIT_TICK_NEUR.code)
        }; g.endif()
        }; g.endif()

        // ====== WAIT_TICK_NEUR: по следующему тику стартуем нейронную фазу ======
        g.begif(g.eq2(st, CoreState.WAIT_TICK_NEUR.code)); run {
            g.begif(g.eq2(tick, 1)); run {
            neur_start.assign(1)                         // однотактный старт
            st_n.assign(CoreState.NEUR.code)
        }; g.endif()
        }; g.endif()

        // ====== NEUR: ждём завершения нейронной фазы ======
        g.begif(g.eq2(st, CoreState.NEUR.code)); run {
            g.begif(g.eq2(neurIf.done_o, 1)); run {
            // сразу запускаем EMIT (без ожидания тика)
            emit_start.assign(1)
            st_n.assign(CoreState.EMIT.code)
        }; g.endif()
        }; g.endif()

        // ====== EMIT: ждём завершения эмиссии, затем цикл на ожидание тика под следующую синфазу ======
        g.begif(g.eq2(st, CoreState.EMIT.code)); run {
            g.begif(g.eq2(emitIf.done_o, 1)); run {
            st_n.assign(CoreState.WAIT_TICK_SYN.code)
        }; g.endif()
        }; g.endif()

        return CoreFsmIF(
            en_core_i   = en_core_i,
            state_o     = state_o,
            neur_start_o= neur_start,
            emit_start_o= emit_start,
            syn_gate_o  = syn_gate
        )
    }
}
