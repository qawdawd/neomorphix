// Naming.kt
package neuromorphix

/** Базовые соглашения по именам. Все низкоуровневые имена строим из них. */
data class Naming(
    val tickName: String = "tick",
    val fifoInName:  String = "spike_in",
    val fifoOutName: String = "spike_out",
    val wmemName:    String = "w_l1",
    val vmemName:    String = "Vmemb",
    val regPrefix:   String = "cfg",
    val fsmName:     String = "core_fsm",
    val selectorName:String = "sel0",
    val synName:     String = "syn",
    val neurName:    String = "neur",
    val emitName:    String = "emit"
)

// NameScope.kt

/** Следит за уникальностью идентификаторов на уровне модуля. */
class NameScope {
    private val used = HashSet<String>()

    /** Зарезервировать имя как есть (бросит, если уже занято). */
    fun reserveExact(name: String): String {
        require(name !in used) { "Name collision: '$name' already used" }
        used += name
        return name
    }

    /** Выдать уникализированное имя на основе base (+_N при необходимости). */
    fun alloc(base: String): String {
        if (base !in used) {
            used += base
            return base
        }
        var i = 1
        while (true) {
            val cand = "${base}_$i"
            if (cand !in used) {
                used += cand
                return cand
            }
            i++
        }
    }
}

// NamingPlanner.kt

/** Результат назначений имён (инстансы и ключевые сигналы/регистры). */
data class AssignedNames(
    // инстансы
    val naming: Naming,                     // ← добавь это поле
    val tickInst: String,
    val fifoInInst: String,
    val fifoOutInst: String,
    val selectorInst: String,
    val regBankInst: String,
    val synInst: String,
    val neurInst: String,
    val emitInst: String,
    // память весов: packed — одна, separate — по ключам
    val wmemInsts: Map<String, String>,   // key=SPIKE field ("w","tag"), val=inst name
    // динамика: главный + допы
    val dynMainInst: String,
    val dynExtraInsts: Map<String, String>,
    // FSM
    val fsmInst: String,
    // «стабильные» имена портов фаз (чтобы генератор HDL не гадал)
    val synPorts: Map<String, String>,    // start/gate/done/busy/etc если нужно
    val neurPorts: Map<String, String>,
    val emitPorts: Map<String, String>,
    // карта имён регистров bank’а (API ключ -> RTL имя)
    val regApiToRtl: Map<String, String>
)

object NamingPlanner {

    /** Строит AssignedNames на базе Layout/Bind и правил Naming. */
    fun assign(layout: LayoutPlan, bind: BindPlan, naming: Naming = Naming()): AssignedNames {
        val ns = NameScope()

        // — инстансы базовых блоков
        val tickInst    = ns.alloc(layout.tick.signalName)   // исправлено: используем имя из LayoutPlan
        val fifoInInst  = ns.alloc(naming.fifoInName)
        val fifoOutInst = ns.alloc(naming.fifoOutName)
        val selectorInst= ns.alloc(naming.selectorName)
        val regBankInst = ns.alloc("${naming.regPrefix}_bank")
        val synInst     = ns.alloc(naming.synName)
        val neurInst    = ns.alloc(naming.neurName)
        val emitInst    = ns.alloc(naming.emitName)
        val fsmInst     = ns.alloc(naming.fsmName)

        // — wmem инстансы
        val wmemInsts: Map<String, String> =
            if (layout.wmems.values.map { it.cfg.name }.toSet().size == 1) {
                // packed: один инстанс, но ключей много → всем дать один и тот же RTL name
                val single = ns.alloc(layout.wmems.values.first().cfg.name)
                layout.wmems.keys.associateWith { single }
            } else {
                // separate: каждый план имеет своё имя
                layout.wmems.mapValues { (_, plan) -> ns.alloc(plan.cfg.name) }
            }

        // — динамика
        val dynMainInst = ns.alloc("dyn_${layout.dyn.main.field}")
        val dynExtraInsts = layout.dyn.extra.associate { e ->
            e.field to ns.alloc("dyn_${e.field}")
        }

        // — имена портов (минимум — можно расширять)
        val synPorts  = mapOf(
            "start_i" to "${synInst}_start",
            "gate_i"  to "${synInst}_gate",
            "done_o"  to "${synInst}_done",
            "busy_o"  to "${synInst}_busy"
        )
        val neurPorts = mapOf(
            "start_i" to "${neurInst}_start",
            "done_o"  to "${neurInst}_done",
            "busy_o"  to "${neurInst}_busy"
        )
        val emitPorts = mapOf(
            "start_i" to "${emitInst}_start",
            "done_o"  to "${emitInst}_done",
            "busy_o"  to "${emitInst}_busy"
        )

        // — имена регистров: делаем стабильные RTL-идентификаторы из API-ключей
        val regApiToRtl = layout.regBank.regs.associate { rd ->
            val rtl = ns.alloc("${naming.regPrefix}_${rd.name}")
            rd.name to rtl
        }

        return AssignedNames(
            naming = naming,   // ← сохраняем исходный Naming
            tickInst = tickInst,
            fifoInInst = fifoInInst,
            fifoOutInst = fifoOutInst,
            selectorInst = selectorInst,
            regBankInst = regBankInst,
            synInst = synInst,
            neurInst = neurInst,
            emitInst = emitInst,
            wmemInsts = wmemInsts,
            dynMainInst = dynMainInst,
            dynExtraInsts = dynExtraInsts,
            fsmInst = fsmInst,
            synPorts = synPorts,
            neurPorts = neurPorts,
            emitPorts = emitPorts,
            regApiToRtl = regApiToRtl
        )
    }
}

fun dumpNamingNames(n: AssignedNames) {
    println("instances:")
    println("  tick   = ${n.tickInst}")
    println("  fifoIn = ${n.fifoInInst}")
    println("  fifoOut= ${n.fifoOutInst}")
    println("  selector= ${n.selectorInst}")
    println("  regBank = ${n.regBankInst}")
    println("  syn     = ${n.synInst}")
    println("  neur    = ${n.neurInst}")
    println("  emit    = ${n.emitInst}")
    println("  fsm     = ${n.fsmInst}")

    println("wmem insts:")
    n.wmemInsts.forEach { (k, v) -> println("  [$k] -> $v") }

    println("dyn:")
    println("  main  -> ${n.dynMainInst}")
    if (n.dynExtraInsts.isNotEmpty()) {
        n.dynExtraInsts.forEach { (k, v) -> println("  extra[$k] -> $v") }
    }

    println("ports:")
    println("  syn : ${n.synPorts}")
    println("  neur: ${n.neurPorts}")
    println("  emit: ${n.emitPorts}")

    println("regApiToRtl:")
    n.regApiToRtl.forEach { (api, rtl) -> println("  $api -> $rtl") }
}