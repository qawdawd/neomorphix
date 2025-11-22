package bnmm.description

import bnmm.queue.FifoConfig
import semantics.PhaseParallelPlan
import semantics.SynapticPackingPlan

/**
 * Унифицированные настройки для селекторов, фаз и периферии BNMM.
 * Эти data-классы используются фабриками для перевода семантических
 * планов в конкретные параметры генераторов оборудования.
 */
data class SelectorConfig(
    val name: String = "selector",
    val indexWidth: Int,
    val plan: PhaseParallelPlan? = null,
    val stepByTick: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Selector name must not be blank" }
        require(indexWidth > 0) { "Selector index width must be positive" }
    }

    companion object {
        fun fromPlan(name: String, indexWidth: Int, plan: PhaseParallelPlan?, stepByTick: Boolean = false) =
            SelectorConfig(name = name, indexWidth = indexWidth, plan = plan, stepByTick = stepByTick)
    }
}

/**
 * Общая конфигурация для фазовых обработчиков.
 */
data class PhaseUnitConfig(
    val name: String = "phase",
    val enableCustomLogic: Boolean = true,
    val enableIrLogic: Boolean = true,
    val stepByTick: Boolean = false,
    val plan: PhaseParallelPlan? = null
) {
    init {
        require(name.isNotBlank()) { "Phase name must not be blank" }
    }

    companion object {
        fun fromPlan(name: String, plan: PhaseParallelPlan?, stepByTick: Boolean = false) =
            PhaseUnitConfig(
                name = name,
                enableCustomLogic = true,
                enableIrLogic = true,
                stepByTick = stepByTick,
                plan = plan
            )
    }
}

/**
 * Параметры банка памяти, пригодные как для статических, так и для динамических реализаций.
 */
data class MemoryBankConfig(
    val name: String,
    val addrWidth: Int,
    val dataWidth: Int,
    val depth: Int,
    val ports: Int = 1,
    val writable: Boolean = false,
    val registerAdapter: Boolean = false,
    val notes: List<String> = emptyList()
) {
    init {
        require(name.isNotBlank()) { "Memory bank name must not be blank" }
        require(addrWidth > 0) { "Address width must be positive" }
        require(dataWidth > 0) { "Data width must be positive" }
        require(depth > 0) { "Depth must be positive" }
        require(ports > 0) { "Number of ports must be positive" }
    }

    companion object {
        fun fromPackingPlan(name: String, plan: SynapticPackingPlan, depth: Int): MemoryBankConfig {
            val notes = buildList {
                addAll(plan.notes)
                add("packing=${plan.mode} words=${plan.assignments.size}")
            }
            val addrWidth = log2ceil(depth)
            return MemoryBankConfig(
                name = name,
                addrWidth = addrWidth,
                dataWidth = plan.wordWidth,
                depth = depth,
                ports = plan.memoryPorts,
                writable = plan.enabled,
                notes = notes
            )
        }

        fun log2ceil(value: Int): Int {
            require(value > 0) { "Depth must be positive" }
            var v = value - 1
            var r = 0
            while (v > 0) {
                v = v shr 1
                r++
            }
            return maxOf(r, 1)
        }
    }
}

/**
 * Описание очереди (FIFO) для внешних или внутренних соединений.
 */
data class QueueConfig(
    val name: String,
    val dataWidth: Int,
    val depth: Int,
    val creditWidth: Int = 8,
    val useTickDoubleBuffer: Boolean = true
) {
    init {
        require(name.isNotBlank()) { "Queue name must not be blank" }
        require(dataWidth > 0) { "Queue data width must be positive" }
        require(depth > 0) { "Queue depth must be positive" }
        require(creditWidth > 0) { "Queue credit width must be positive" }
    }

    fun toFifoConfig() = FifoConfig(
        name = name,
        dataWidth = dataWidth,
        depth = depth,
        creditWidth = creditWidth,
        useTickDoubleBuffer = useTickDoubleBuffer
    )
}

/**
 * Конфигурация контроллера, агрегирующая подмодули.
 */
data class ControllerConfig(
    val name: String = "controller",
    val selectors: List<SelectorConfig> = emptyList(),
    val phases: List<PhaseUnitConfig> = emptyList(),
    val queues: List<QueueConfig> = emptyList(),
    val memoryBanks: List<MemoryBankConfig> = emptyList(),
    val tickGen: TickGenConfig? = null,
    val notes: List<String> = emptyList()
) {
    init {
        require(name.isNotBlank()) { "Controller name must not be blank" }
    }
}

/**
 * Настройки генератора тиков.
 */
data class TickGenConfig(
    val name: String = "tickgen",
    val periodCycles: Int = 1,
    val pulseWidthCycles: Int = 1
) {
    init {
        require(name.isNotBlank()) { "Tick generator name must not be blank" }
        require(periodCycles > 0) { "Period must be positive" }
        require(pulseWidthCycles > 0) { "Pulse width must be positive" }
        require(pulseWidthCycles <= periodCycles) { "Pulse width must not exceed period" }
    }
}

/**
 * Фабрики для перевода семантических планов в конфигурации генераторов.
 */
object DescriptionFactory {

    fun selectorFor(plan: PhaseParallelPlan, indexWidth: Int, name: String = "selector_${plan.phase.name}"):
        SelectorConfig {
        return SelectorConfig(
            name = name,
            indexWidth = indexWidth,
            plan = plan,
            stepByTick = plan.enabled
        )
    }

    fun phaseFor(plan: PhaseParallelPlan, name: String = plan.phase.name): PhaseUnitConfig {
        return PhaseUnitConfig(
            name = name,
            enableCustomLogic = true,
            enableIrLogic = plan.enabled,
            stepByTick = plan.enabled,
            plan = plan
        )
    }

    fun memoryForPacking(
        plan: SynapticPackingPlan,
        depth: Int,
        name: String = "mem_${plan.mode.name.lowercase()}"
    ): MemoryBankConfig {
        val addrWidth = MemoryBankConfig.log2ceil(depth)
        return MemoryBankConfig(
            name = name,
            addrWidth = addrWidth,
            dataWidth = plan.wordWidth,
            depth = depth,
            ports = plan.memoryPorts,
            writable = plan.enabled,
            notes = plan.notes
        )
    }

    fun queue(name: String, dataWidth: Int, depth: Int, creditWidth: Int = 8, useTickDoubleBuffer: Boolean = true) =
        QueueConfig(
            name = name,
            dataWidth = dataWidth,
            depth = depth,
            creditWidth = creditWidth,
            useTickDoubleBuffer = useTickDoubleBuffer
        )

    fun controller(
        name: String = "controller",
        selectors: List<SelectorConfig> = emptyList(),
        phases: List<PhaseUnitConfig> = emptyList(),
        queues: List<QueueConfig> = emptyList(),
        memories: List<MemoryBankConfig> = emptyList(),
        tickGen: TickGenConfig? = null,
        notes: List<String> = emptyList()
    ) = ControllerConfig(
        name = name,
        selectors = selectors,
        phases = phases,
        queues = queues,
        memoryBanks = memories,
        tickGen = tickGen,
        notes = notes
    )

    fun tickGen(name: String = "tickgen", periodCycles: Int = 1, pulseWidthCycles: Int = 1) =
        TickGenConfig(name = name, periodCycles = periodCycles, pulseWidthCycles = pulseWidthCycles)
}
