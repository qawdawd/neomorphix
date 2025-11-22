package bnmm.phase

import bnmm.selector.NeuronSelectorPorts
import cyclix.Generic
import hwast.hw_dim_static
import hwast.hw_imm
import hwast.hw_param
import hwast.hw_var
import ir.IrAssignment
import ir.IrBlock
import ir.IrConditional
import ir.IrLoop
import ir.IrOperation
import ir.IrValue
import ir.IrValue.Constant
import ir.IrValue.Symbol
import symbols.SymbolEntry
import symbols.SymbolTable
import transaction.ComparisonOp
import transaction.OpCode

/**
 * Конфигурация обработчика рефрактерной фазы.
 * - name: суффикс для имён сигналов
 * - enableCustomLogic: если true, используется пользовательский builder
 * - enableIrLogic: разрешает автоматическую сборку логики из IR
 * - stepByTick: выполнять действия только при tick=1 (если передан tick)
 */
data class RefractoryPhaseConfig(
    val name: String = "ref_phase",
    val enableCustomLogic: Boolean = true,
    val enableIrLogic: Boolean = true,
    val stepByTick: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Phase name must not be blank" }
    }
}

/**
 * Рантайм-параметры обработчика.
 * - tick: опциональный тик для пошагового запуска (если stepByTick=true)
 */
data class RefractoryPhaseRuntime(
    val tick: hw_var? = null
)

/** Интерфейсы фазы для подключения к FSM и внешней логике. */
data class RefractoryPhasePorts(
    val start: hw_var,
    val busy: hw_var,
    val done: hw_var
)

/**
 * Контекст, передаваемый в пользовательский builder для описания арифметики
 * на каждом шаге обхода постсинаптических нейронов.
 */
class RefractoryPhaseContext(
    val g: Generic,
    val selector: NeuronSelectorPorts,
    val runStep: hw_var
)

/**
 * Обработчик рефрактерной фазы. Он:
 * 1. Стартует селектор нейронов и отслеживает его busy/done.
 * 2. Предоставляет hook для пользовательской логики на каждом валидном шаге.
 * 3. Может автоматически собирать логику из IR-блока, если customLogic не задан.
 */
class RefractoryPhaseUnit(private val instName: String = "ref_phase") {

    fun emit(
        g: Generic,
        cfg: RefractoryPhaseConfig,
        runtime: RefractoryPhaseRuntime,
        selector: NeuronSelectorPorts,
        customLogic: ((RefractoryPhaseContext) -> Unit)? = null,
        irLogic: RefractoryPhaseIrLogic? = null,
        bindings: Map<String, hw_var> = emptyMap()
    ): RefractoryPhasePorts {
        val name = cfg.name

        // Управляющие сигналы от FSM/верхнего уровня
        val start_i = g.uglobal("start_$name", hw_dim_static(1), "0")
        val busy_o = g.uglobal("busy_$name", hw_dim_static(1), "0")
        val done_o = g.uglobal("done_$name", hw_dim_static(1), "0")

        // Локальное состояние
        val S_IDLE = 0
        val S_RUN = 1
        val state = g.uglobal("state_$name", hw_dim_static(1), "0")
        val stateNext = g.uglobal("state_n_$name", hw_dim_static(1), "0")
        state.assign(stateNext)

        // step_en: разрешение шага по tick (если включено) или постоянно 1
        val stepEn = g.uglobal("step_en_$name", hw_dim_static(1), "0")
        stepEn.assign(if (cfg.stepByTick) runtime.tick?.let { g.eq2(it, 1) } ?: hw_imm(0) else hw_imm(1))

        // Запуск селектора
        selector.start.assign(g.land(start_i, g.eq2(state, S_IDLE)))

        // Значения busy/done по умолчанию
        busy_o.assign(0)
        done_o.assign(0)

        // FSM: IDLE
        g.begif(g.eq2(state, S_IDLE)); run {
            g.begif(g.eq2(start_i, 1)); run {
            busy_o.assign(1)
            stateNext.assign(S_RUN)
        }; g.endif()
        }; g.endif()

        // FSM: RUN — пока селектор не закончит обход
        g.begif(g.eq2(state, S_RUN)); run {
            busy_o.assign(1)

            // Пользовательская/автоматическая логика на валидных шагах
            val runStep = g.land(stepEn, g.land(selector.busy, selector.laneValid))
            val chosenLogic =
                if (cfg.enableCustomLogic && customLogic != null) {
                    customLogic
                } else if (cfg.enableIrLogic && irLogic != null) {
                    buildIrLogic(
                        g = g,
                        irLogic = irLogic,
                        bindings = bindings,
                        namePrefix = name
                    )
                } else null

            if (chosenLogic != null) {
                chosenLogic(
                    RefractoryPhaseContext(
                        g = g,
                        selector = selector,
                        runStep = runStep
                    )
                )
            }

            // Завершение по done селектора
            g.begif(g.eq2(selector.done, 1)); run {
            busy_o.assign(0)
            done_o.assign(1)
            stateNext.assign(S_IDLE)
        }; g.endif()
        }; g.endif()

        return RefractoryPhasePorts(
            start = start_i,
            busy = busy_o,
            done = done_o
        )
    }

    /**
     * Собирает логику обработки фазы из IR-блока. Каркас управления остаётся
     * тем же, что и для пользовательской логики; операции исполняются только
     * когда runStep=1.
     */
    private fun buildIrLogic(
        g: Generic,
        irLogic: RefractoryPhaseIrLogic,
        bindings: Map<String, hw_var>,
        namePrefix: String
    ): (RefractoryPhaseContext) -> Unit {
        val storage = allocateSymbolStorage(g, irLogic.symbols, bindings, namePrefix)

        fun resolve(value: IrValue): hw_param = when (value) {
            is Constant -> resolveConst(value.value)
            is Symbol -> storage[value.entry.name]
                ?: error("No storage or binding for symbol '${value.entry.name}'")
        }

        fun emitBlock(block: IrBlock) {
            block.statements.forEach { stmt ->
                when (stmt) {
                    is IrAssignment -> {
                        val dst = storage[stmt.target.name]
                            ?: error("No storage for target '${stmt.target.name}'")
                        dst.assign(resolve(stmt.value))
                    }

                    is IrOperation -> {
                        val dst = storage[stmt.target.name]
                            ?: error("No storage for target '${stmt.target.name}'")
                        val operands = stmt.operands.map { resolve(it) }
                        val opResult = applyOpcode(g, stmt.opcode, operands)
                        dst.assign(opResult)
                    }

                    is IrConditional -> emitConditional(stmt, ::resolve, ::emitBlock, g)
                    is IrLoop -> error("IrLoop is not supported in refractory phase auto-logic yet")
                    is ir.IrEmit -> {
                        // No emit in refractory phase
                    }
                }
            }
        }

        return { ctx ->
            g.begif(g.eq2(ctx.runStep, 1)); run {
            emitBlock(irLogic.block)
        }; g.endif()
        }
    }

    private fun applyOpcode(g: Generic, opcode: OpCode, operands: List<hw_param>): hw_param {
        require(operands.isNotEmpty()) { "Opcode '$opcode' received no operands" }
        return when (opcode) {
            OpCode.ADD -> {
                require(operands.size >= 2) { "ADD expects two operands" }
                g.add(operands[0], operands[1])
            }

            OpCode.SUB -> {
                require(operands.size >= 2) { "SUB expects two operands" }
                g.sub(operands[0], operands[1])
            }

            OpCode.MUL -> {
                require(operands.size >= 2) { "MUL expects two operands" }
                g.mul(operands[0], operands[1])
            }

            OpCode.LOGICAL_AND -> {
                require(operands.size >= 2) { "LOGICAL_AND expects two operands" }
                g.land(operands[0], operands[1])
            }

            OpCode.LOGICAL_OR -> {
                require(operands.size >= 2) { "LOGICAL_OR expects two operands" }
                g.lor(operands[0], operands[1])
            }

            OpCode.LOGICAL_NOT -> {
                require(operands.size == 1) { "LOGICAL_NOT expects a single operand" }
                g.bnot(operands[0])
            }

            OpCode.POW -> error("POW opcode is not supported in refractory phase auto-logic")
        }
    }

    private fun emitConditional(
        conditional: ir.IrConditional,
        resolve: (IrValue) -> hw_param,
        emitBlock: (IrBlock) -> Unit,
        g: Generic
    ) {
        val condExpr = resolveCondition(conditional.condition, resolve, g)

        fun emitElseIfChain(idx: Int) {
            if (idx >= conditional.elseIfBranches.size) {
                conditional.elseBlock?.let { elseBlock -> emitBlock(elseBlock) }
                return
            }

            val branch = conditional.elseIfBranches[idx]
            val branchCond = resolveCondition(branch.condition, resolve, g)
            g.begif(branchCond); run {
                emitBlock(branch.body)
            }; g.begelse(); run {
                emitElseIfChain(idx + 1)
            }; g.endif()
        }

        g.begif(condExpr); run {
            emitBlock(conditional.thenBlock)
        }; g.begelse(); run {
            emitElseIfChain(0)
        }; g.endif()
    }

    private fun resolveCondition(
        condition: ir.IrCondition,
        resolve: (IrValue) -> hw_param,
        g: Generic
    ): hw_var {
        val left = resolve(condition.left)
        val right = resolve(condition.right)
        return when (condition.comparison) {
            ComparisonOp.EQ -> g.eq2(left, right)
            ComparisonOp.NEQ -> g.neq2(left, right)
            ComparisonOp.LT -> error("LT is not supported in refractory phase auto-logic yet")
            ComparisonOp.LTE -> error("LTE is not supported in refractory phase auto-logic yet")
            ComparisonOp.GT -> error("GT is not supported in refractory phase auto-logic yet")
            ComparisonOp.GTE -> error("GTE is not supported in refractory phase auto-logic yet")
        }
    }

    /**
     * Выделяет регистры для всех символов таблицы (поля и операнды). Если имя
     * встречается в bindings, вместо регистра будет использован внешний сигнал.
     */
    private fun allocateSymbolStorage(
        g: Generic,
        symbols: SymbolTable,
        bindings: Map<String, hw_var>,
        namePrefix: String
    ): Map<String, hw_var> {
        val storage = LinkedHashMap<String, hw_var>()

        fun addEntry(entry: SymbolEntry) {
            if (storage.containsKey(entry.name)) return
            val bound = bindings[entry.name]
            val target = bound ?: g.uglobal("${namePrefix}_${entry.name}", hw_dim_static(entry.bitWidth), "0")
            storage[entry.name] = target
        }

        symbols.allFields().forEach { addEntry(it) }
        symbols.allOperands().forEach { addEntry(it) }
        return storage
    }

    private fun resolveConst(num: Any): hw_param = when (num) {
        is Boolean -> hw_imm(num)
        is Number -> hw_imm(num.toInt())
        is hw_param -> num
        else -> error("Unsupported constant type: ${num::class.simpleName}")
    }
}

/**
 * Описание автоматической логики: IR-блок с ссылкой на таблицу символов,
 * необходимой для выделения регистров и разрешения разрядностей.
 */
data class RefractoryPhaseIrLogic(
    val block: IrBlock,
    val symbols: SymbolTable
)
