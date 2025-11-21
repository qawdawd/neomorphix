package neuromorphix

import cyclix.Generic
import hwast.hw_imm
import hwast.hw_var
import hwast.*
import cyclix.*
import neuromorphix.NmMath



// === Конфиг компонента динамических параметров ===
data class DynParamCfg(
    val name: String,      // логическое имя массива
    val bitWidth: Int,     // разрядность слова
    val count: Int,        // число слов (обычно = числу постсинаптических нейронов)
    val initZero: Boolean = true  // инициализировать нулём
)

// === Интерфейс к памяти динамических параметров ===
// Внешние сущности (обработчики фаз/ФСМ) будут:
//   - ставить rd_idx -> читать rd_data (комбин.)
//   - при we=1 писать wr_data в mem[wr_idx] (синхронно)
data class DynParamIF(
    val mem: hw_var,     // сам регистровый массив [count][bitWidth]
    val rd_idx: hw_var,  // индекс чтения
    val rd_data: hw_var, // данные на чтение (mem[rd_idx])
    val wr_idx: hw_var,  // индекс записи
    val wr_data: hw_var, // данные на запись
    val we: hw_var       // write enable
)

// === Генератор памяти динамических параметров ===
class DynamicParamMem(private val instName: String = "dynp") {

    fun emit(g: Generic, cfg: DynParamCfg): DynParamIF {
        val name = cfg.name

        // --- Регистровый массив: mem_<name> : [count][bitWidth]
        val memDim = hw_dim_static(cfg.bitWidth).apply { add(cfg.count, 0) }
        val mem    = g.uglobal("mem_$name", memDim, if (cfg.initZero) "0" else "undef")

        // --- Интерфейсные сигналы
        val rd_idx   = g.uglobal("rd_idx_$name",   hw_dim_static(NmMath.log2ceil(cfg.count)), "0")
        val rd_data  = g.uglobal("rd_data_$name",  hw_dim_static(cfg.bitWidth),        "0")

        val wr_idx   = g.uglobal("wr_idx_$name",   hw_dim_static(NmMath.log2ceil(cfg.count)), "0")
        val wr_data  = g.uglobal("wr_data_$name",  hw_dim_static(cfg.bitWidth),        "0")
        val we       = g.uglobal("we_$name",       hw_dim_static(1),                   "0")

        // --- Комбин. чтение
        rd_data.assign(mem[rd_idx])

        // --- Синхронная запись (регистровая, под we)
        g.begif(g.eq2(we, 1)); run {
            mem[wr_idx].assign(wr_data)
        }; g.endif()

        return DynParamIF(
            mem = mem,
            rd_idx = rd_idx,
            rd_data = rd_data,
            wr_idx = wr_idx,
            wr_data = wr_data,
            we = we
        )
    }
}

