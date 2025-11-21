package neuromorphix

// 4) FSM-план: что за состояния и какие сигналы кому дёргать/ждать
data class FsmPlan(
    val states: List<String> = listOf("IDLE", "WAIT_TICK_SYN", "SYN", "WAIT_TICK_NEUR", "NEUR", "EMIT"),
    // логические -> физические сигналы
    val gates: Map<String, String>,   // напр. "syn_gate_o" -> "tick"
    val starts: Map<String, String>,  // напр. "neur_start_o" -> "neur_phase.start_i"
    val waits: Map<String, String>    // напр. "SYN" -> "synIf.done_o"
)

/** Построение FSM-плана из layout/bind. */
object FsmPlanner {
    fun build(layout: LayoutPlan, bind: BindPlan): FsmPlan {
        // gate для синфазы: если LayoutPlan сказал gateByTick=true — гейтим по tick
        val gates = buildMap {
            if (layout.phases.syn.gateByTick) {
                put("syn_gate_o", layout.tick.signalName) // кто является «воротами» для синфазы
            }
        }

        // старты: нейронная и эмит-фазы стартуют явным импульсом от FSM
        val starts = mapOf(
            "neur_start_o" to "neur_phase.start_i",
            "emit_start_o" to "emit_phase.start_i"
        )

        // ожидания завершения фаз (done_o интерфейсов фаз)
        val waits = mapOf(
            "SYN"  to "synIf.done_o",
            "NEUR" to "neurIf.done_o",
            "EMIT" to "emitIf.done_o"
        )

        return FsmPlan(
            states = listOf("IDLE", "WAIT_TICK_SYN", "SYN", "WAIT_TICK_NEUR", "NEUR", "EMIT"),
            gates  = gates,
            starts = starts,
            waits  = waits
        )
    }
}