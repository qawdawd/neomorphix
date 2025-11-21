package neuromorphix

/**
 * Корневой контейнер контекста биндинга:
 *  - хранит архитектуру сети (arch),
 *  - промежуточные представления транзакционного описания (ir/ast),
 *  - планы и соответствия для компоновки (symbols/layout/bind/fsm),
 *  - результаты и/или правила проверок (checks),
 *  - соглашения по именованию (naming).
 *
 * Заполняется поэтапно билдерами: сначала arch, затем symbols/layout/bind/fsm и т.п.
 * Поля, которые еще не рассчитаны, допускаются как null.
 */
data class BindingCtx(
    val arch: SnnArch,           // обязательный снимок архитектуры
    val ir: TxIR? = null,        // плоский IR (опционально на этапе планирования)
    val ast: AstModule? = null,  // структурированный AST (опционально)

    val symbols: Symbols? = null,   // таблицы полей/операндов (заполняется билдером символов)
    val layout: LayoutPlan? = null, // планы инстансов компонентов БММ
    val bind: BindPlan? = null,     // правила сопоставления IR → API компонентов
    val fsm: FsmPlan? = null,       // план управляющего автомата

    val checks: List<Check> = emptyList(), // результаты/правила проверок совместимости
    val naming: Naming = Naming()          // единые соглашения по именованию
)

/* ===== Ниже — минимальные заглушки типов плана/символов/проверок/нейминга.
 * Они будут детализированы последующими шагами и могут быть вынесены в отдельные файлы.
 * Добавлены только для того, чтобы BindingCtx можно было ссылочно собирать и компилировать.
 */

// Таблицы символов (поля SPIKE/NEURON, резолверы операндов и т.д.)
//interface Symbols





/* ================================================================
 * Примечание по твоему SnnArch:
 *  - в init() у тебя: ResetBitWidth = log2(ResetBitWidth) — вероятно опечатка.
 *    Должно быть: ResetBitWidth = log2(ResetMaxValue)
 *  - SpikeBitWidth лучше вычислять из PresynNeuronsCount (как ты и сделал ниже).
 * ================================================================ */


// План размещения/конфигурации компонентов БММ (Tick/FIFOs/StaticMem/DynamicMem/Selector/Phases/RegBank)
//interface LayoutPlan

// Правила биндинга IR → интерфейсы компонентов (маршрутизация операций по фазам/компонентам)
//interface BindPlan

// План FSM: список состояний, сигналы стартов/гейтов/ожиданий
//interface FsmPlan

// Единый формат проверок (валидация ширин, глубин, адресаций, наличия регистров и т.п.)
data class Check(
    val kind: String,           // логический тип проверки (e.g. "widths", "addrWidth", "depth", "irSupport")
    val ok: Boolean,            // результат проверки
    val message: String? = null // диагностическая строка, если ok=false/предупреждение
)

// Соглашения по именованию инстансов/сигналов для стабильной генерации
//data class Naming(
//    val fifoInName: String = "spike_in",
//    val fifoOutName: String = "spike_out",
//    val wmemName: String = "w_l1",
//    val vmemName: String = "Vmemb",
//    val regPrefix: String = "cfg",
//    val fsmName: String = "core_fsm",
//    val selectorName: String = "sel0",
//    val synName: String = "syn",
//    val neurName: String = "neur",
//    val emitName: String = "emit",
//    val tickName: String = "tick"
//)