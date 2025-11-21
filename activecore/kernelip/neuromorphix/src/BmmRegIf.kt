package neuromorphix

import cyclix.Generic
import hwast.*

/** Описание одного интерфейсного регистра.
 *  Если count != null — генерится массив [count][width] (порт и регистр того же размера).
 */
data class RegDesc(
    val name: String,
    val width: Int,
    val init: String = "0",
    val count: Int? = null       // массив, если задано (например, таблица базовых адресов)
)

/** Конфиг банка регистров. prefix добавляется к именам портов/регистров при генерации (для изоляции пространств имён). */
data class RegBankCfg(
    val bankName: String,              // логическое имя банка (для читаемых идентификаторов)
    val regs: List<RegDesc>,
    val prefix: String = "cfg"         // префикс имён HDL-объектов
)

/** Возвращаемый интерфейс банка регистров:
 *  - portsIn: карта name -> uport (IN), то что видно снаружи
 *  - regsR:   карта name -> uglobal (латч внутри ядра)
 *  - helper get(name): быстрый доступ к зарегистрированному значению
 */
data class RegBankIF(
    val bankName: String,
    val portsIn: Map<String, hw_var>,
    val regsR: Map<String, hw_var>
) {
    operator fun get(name: String): hw_var =
        regsR[name] ?: error("RegBankIF($bankName): no such register '$name'")
}

/** Генератор интерфейсных регистров.
 *  Для каждого RegDesc создаётся:
 *    uport("<prefix>_<name>_i", IN, width[/count])  -- внешний вход
 *    uglobal("<prefix>_<name>_r", width[/count])    -- внутренняя зарегистрированная копия
 *    и назначение  _r := _i
 */
class RegBankGen(private val instName: String = "reg_bank") {

    fun emit(g: Generic, cfg: RegBankCfg): RegBankIF {
        val portsIn = LinkedHashMap<String, hw_var>()
        val regsR   = LinkedHashMap<String, hw_var>()

        for (d in cfg.regs) {
            val dim = hw_dim_static(d.width).apply {
                if (d.count != null) add(d.count, 0)     // массив [count][width]
            }

            val pName = "${cfg.prefix}_${d.name}_i"
            val rName = "${cfg.prefix}_${d.name}_r"

            val portIn = g.uport(pName, PORT_DIR.IN, dim, d.init)
            val regR   = g.uglobal(rName, dim, d.init)

            // зарегистрированная копия доступна ядру,
            // наружу видно только входной порт
            regR.assign(portIn)

            portsIn[d.name] = portIn
            regsR[d.name]   = regR
        }

        return RegBankIF(
            bankName = cfg.bankName,
            portsIn = portsIn,
            regsR = regsR
        )
    }
}