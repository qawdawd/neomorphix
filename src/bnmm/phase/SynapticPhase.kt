package bnmm.phase

import bnmm.selector.SynapseSelectorPorts
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

/**
 * Конфигурация обработчика синаптической фазы.
 * - name: суффикс для имён сигналов
 * - enableCustomLogic: если true, используется пользовательский builder
 * - enableIrLogic: разрешает автоматическую сборку логики из IR
 * - stepByTick: выполнять действия только при tick=1 (если передан tick)
 */
data class SynapticPhaseConfig(
    val name: String = "syn_phase",
    val enableCustomLogic: Boolean = true,
    val enableIrLogic: Boolean = true,
    val stepByTick: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Phase name must not be blank" }
    }
}

/**
 * Рантайм-параметры обработчика:
 * - preIndex: внешний индекс пресинаптического нейрона
 * - tick: опциональный тик для пошагового запуска (если stepByTick=true)
 */
data class SynapticPhaseRuntime(
    val preIndex: hw_var,
    val tick: hw_var? = null
)

/**
 * Интерфейсы фазы для подключения к FSM и внешней логике.
 */
data class SynapticPhasePorts(
    val start: hw_var,
    val busy: hw_var,
    val done: hw_var
)

/**
 * Контекст, передаваемый в пользовательский builder для описания арифметики
 * на каждом шаге обхода постсинаптических нейронов.
 */
class SynapticPhaseContext(
    val g: Generic,
    val selector: SynapseSelectorPorts,
    val runStep: hw_var
)

/**
 * Минимальный обработчик синаптической фазы. Он:
 * 1. Латчит preIndex и запускает селектор.
 * 2. Мониторит busy/done селектора, формируя собственные busy/done.
 * 3. Предоставляет hook для пользовательской логики на каждом шаге обработки
 *    постсинаптического нейрона (например, обновление аккумулятора).
 *
 * Каркас управления строится автоматически; пользовательский builder описывает
 * только вычислительную часть, используя weight/postIndex селектора.
 */
class SynapticPhaseUnit(private val instName: String = "syn_phase") {

    /**
     * @param g         экземпляр Cyclix.Generic
     * @param cfg       статическая конфигурация обработчика
     * @param runtime   рантайм-параметры (preIndex, опциональный tick)
     * @param selector  ранее созданный селектор синапсов
     * @param customLogic пользовательский builder, вызываемый на каждом шаге;
     *                    если null, используется автоматическая логика из IR,
     *                    если она передана
     * @param irLogic    автоматическая логика, построенная из IR-блока. Если
     *                    передан и включён в конфигурации, используется при
     *                    отсутствии кастомного builder'а
     * @param bindings   опциональные внешние сигналы, сопоставленные именам
     *                    символов (например, weight из селектора). Если имя
     *                    встречается в IR, вместо внутреннего регистра будет
     *                    использован предоставленный сигнал
     */
    fun emit(
        g: Generic,
        cfg: SynapticPhaseConfig,
        runtime: SynapticPhaseRuntime,
        selector: SynapseSelectorPorts,
        customLogic: ((SynapticPhaseContext) -> Unit)? = null,
        irLogic: SynapticPhaseIrLogic? = null,
        bindings: Map<String, hw_var> = emptyMap()
    ): SynapticPhasePorts {
        val name = cfg.name

        // Управляющие сигналы от FSM/верхнего уровня
        val start_i = g.uglobal("start_$name", hw_dim_static(1), "0")
        val busy_o = g.uglobal("busy_$name", hw_dim_static(1), "0")
        val done_o = g.uglobal("done_$name", hw_dim_static(1), "0")

        // Локальные сигналы
        val S_IDLE = 0
        val S_RUN = 1
        val state = g.uglobal("state_$name", hw_dim_static(1), "0")
        val stateNext = g.uglobal("state_n_$name", hw_dim_static(1), "0")
        state.assign(stateNext)

        // step_en: разрешение шага по tick (если включено) или постоянно 1
        val stepEn = g.uglobal("step_en_$name", hw_dim_static(1), "0")
        stepEn.assign(
            if (cfg.stepByTick) {
                runtime.tick?.let { g.eq2(it, 1) } ?: hw_imm(0)
            } else hw_imm(1)
        )

        // Управление селектором: стартуем один такт, передаем preIndex
        selector.start.assign(g.land(start_i, g.eq2(state, S_IDLE)))
        selector.preIndex.assign(runtime.preIndex)

        // Значения busy/done сбрасываются по умолчанию
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

            // Пользовательская логика вызывается на каждом валидном шаге
            val runStep = g.land(stepEn, selector.busy)
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
                    SynapticPhaseContext(
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

        return SynapticPhasePorts(
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
        irLogic: SynapticPhaseIrLogic,
        bindings: Map<String, hw_var>,
        namePrefix: String
    ): (SynapticPhaseContext) -> Unit {
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

                    is IrLoop -> error("IrLoop is not supported in synaptic phase auto-logic yet")

                    is ir.IrEmit -> {
                        // Заглушка: синхронизация emit будет добавлена вместе с выходными очередями
                    }
                }
            }
        }

        return { ctx ->
            // исполняем IR только на валидных шагах селектора
            g.begif(g.eq2(ctx.runStep, 1)); run {
            emitBlock(irLogic.block)
        }; g.endif()
        }
    }

    private fun applyOpcode(g: Generic, opcode: transaction.OpCode, operands: List<hw_param>): hw_param {
        require(operands.isNotEmpty()) { "Opcode '$opcode' received no operands" }
        return when (opcode) {
            transaction.OpCode.ADD -> {
                require(operands.size >= 2) { "ADD expects two operands" }
                g.add(operands[0], operands[1])
            }
            transaction.OpCode.SUB -> {
                require(operands.size >= 2) { "SUB expects two operands" }
                g.sub(operands[0], operands[1])
            }
            transaction.OpCode.MUL -> {
                require(operands.size >= 2) { "MUL expects two operands" }
                g.mul(operands[0], operands[1])
            }
            transaction.OpCode.LOGICAL_AND -> {
                require(operands.size >= 2) { "LOGICAL_AND expects two operands" }
                g.land(operands[0], operands[1])
            }
            transaction.OpCode.LOGICAL_OR -> {
                require(operands.size >= 2) { "LOGICAL_OR expects two operands" }
                g.lor(operands[0], operands[1])
            }
            transaction.OpCode.LOGICAL_NOT -> {
                require(operands.size == 1) { "LOGICAL_NOT expects a single operand" }
                g.bnot(operands[0])
            }
            transaction.OpCode.POW -> error("POW opcode is not supported in synaptic phase auto-logic")
        }
    }

    private fun emitConditional(
        conditional: IrConditional,
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
            transaction.ComparisonOp.EQ -> g.eq2(left, right)
            transaction.ComparisonOp.NEQ -> g.neq2(left, right)
            transaction.ComparisonOp.LT -> error("LT is not supported in synaptic phase auto-logic yet")
            transaction.ComparisonOp.LTE -> error("LTE is not supported in synaptic phase auto-logic yet")
            transaction.ComparisonOp.GT -> error("GT is not supported in synaptic phase auto-logic yet")
            transaction.ComparisonOp.GTE -> error("GTE is not supported in synaptic phase auto-logic yet")
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
data class SynapticPhaseIrLogic(
    val block: IrBlock,
    val symbols: SymbolTable
)
