package neuromorphix

import cyclix.Generic
import hwast.hw_imm
import hwast.hw_var
import hwast.*
import cyclix.*
import neuromorphix.NmMath


// === конфиг интерфейса статической (синаптической) памяти ===
data class StaticMemCfg(
    val name: String,         // базовое имя, напр. "wmem_l1"
    val wordWidth: Int,       // разрядность слова (weightBitWidth)
    val depth: Int,           // число слов (presyn * postsyn)
    // адресация:
//    val addrMode: AddrMode = AddrMode.CONCAT,
    val preIdxWidth: Int? = null,   // для CONCAT: ширина пресинапт. индекса
    val postIdxWidth: Int? = null,  // для CONCAT: ширина постсинапт. индекса
    val postsynCount: Int? = null,  // для LINEAR: множитель
    // сигнал enable на чтение
    val useEn: Boolean = true
)

// === интерфейс, который вернёт компонент ===
// наружу (порты): adr_o, en_o?, dat_i
// внутрь (регистры): adr_r, en_r, dat_r
data class StaticMemIF(
    // внутренние регистры, которыми управляет ядро
    val adr_r: hw_var,
    val en_r: hw_var?,
    val dat_r: hw_var,
    // внешние порты для подключения к контроллеру памяти
    val adr_o: hw_var,
    val en_o: hw_var?,
    val dat_i: hw_var,
    // сервис: готовый addrWidth
    val addrWidth: Int
)


// === генератор интерфейса к статической памяти (read-only) ===
class StaticMemIfGen(private val instName: String = "wmem_if") {

    fun emit(g: Generic, cfg: StaticMemCfg): StaticMemIF {
        val name = cfg.name

        // --- адресная ширина
        val addrW = NmMath.log2ceil(cfg.depth)

        // --- внешние порты: адрес и данные
        val adr_o = g.uport("adr_${name}", PORT_DIR.OUT, hw_dim_static(addrW), "0")
        val dat_i = g.uport("dat_${name}", PORT_DIR.IN,  hw_dim_static(cfg.wordWidth), "0")

        // --- опционально EN (наружу)
        val en_o  = if (cfg.useEn)
            g.uport("en_${name}",  PORT_DIR.OUT, hw_dim_static(1), "0")
        else null

        // --- внутренние регистры (ядро пишет адрес и EN; ядро читает dat_r)
        val adr_r = g.uglobal("adr_${name}_r", hw_dim_static(addrW), "0")
        val dat_r = g.uglobal("dat_${name}_r", hw_dim_static(cfg.wordWidth), "0")
        val en_r  = if (cfg.useEn) g.uglobal("en_${name}_r",  hw_dim_static(1), "0") else null

        // --- регистрируем входные данные (при желании можно сделать опц.)
        dat_r.assign(dat_i)

        // --- выводим наружу адрес/enable
        adr_o.assign(adr_r)
        if (en_o != null && en_r != null) en_o.assign(en_r)

        return StaticMemIF(
            adr_r = adr_r,
            en_r = en_r,
            dat_r = dat_r,
            adr_o = adr_o,
            en_o = en_o,
            dat_i = dat_i,
            addrWidth = addrW
        )
    }

}
