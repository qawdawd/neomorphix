package bnmm.memory

import bnmm.description.MemoryBankConfig
import cyclix.Generic
import hwast.PORT_DIR
import hwast.hw_dim_static
import hwast.hw_imm_zeroes
import hwast.hw_var

/**
 * Простой набор описателей портов памяти: раздельные списки чтения и записи.
 */
data class MemoryReadPort(
    val en: hw_var,
    val addr: hw_var,
    val data: hw_var
)

data class MemoryWritePort(
    val en: hw_var,
    val addr: hw_var,
    val data: hw_var
)

data class MemoryBankPorts(
    val readPorts: List<MemoryReadPort>,
    val writePorts: List<MemoryWritePort>
)

/**
 * Статический банк памяти с только чтением. Использует обобщённую
 * конфигурацию и экспортирует порты для подключения к фазам/контроллеру.
 */
class StaticMemoryBank(private val instName: String = "mem_static") {

    fun emit(g: Generic, cfg: MemoryBankConfig): MemoryBankPorts {
        require(cfg.ports >= 1) { "At least one read port is required" }
        val name = cfg.name

        val mem = if (!cfg.external) {
            val memDim = hw_dim_static(cfg.dataWidth).apply { add(cfg.depth, 0) }
            g.uglobal("${instName}_${name}", memDim, "0")
        } else {
            null
        }

        val readPorts = (0 until cfg.ports).map { idx ->
            if (cfg.external) {
                require(cfg.ports == 1) { "External static bank supports only one read port" }

                val bramAddr = g.uport("bram_addr_${name}", PORT_DIR.OUT, hw_dim_static(cfg.addrWidth), "0")
                val bramEn = g.uport("bram_en_${name}", PORT_DIR.OUT, hw_dim_static(1), "0")
                val weWidth = maxOf(1, cfg.dataWidth / 8)
                val bramWe = g.uport("bram_we_${name}", PORT_DIR.OUT, hw_dim_static(weWidth), "0")
                val bramDout = g.uport("bram_dout_${name}", PORT_DIR.IN, hw_dim_static(cfg.dataWidth), "0")

                bramWe.assign(hw_imm_zeroes(weWidth))
                MemoryReadPort(bramEn, bramAddr, bramDout)
            } else {
                val rd = g.uport("rd_${name}_$idx", PORT_DIR.IN, hw_dim_static(1), "0")
                val addr = g.uport("addr_${name}_$idx", PORT_DIR.IN, hw_dim_static(cfg.addrWidth), "0")
                val data = g.uport("data_${name}_$idx", PORT_DIR.OUT, hw_dim_static(cfg.dataWidth), "0")

                data.assign(mem!![addr])
                MemoryReadPort(rd, addr, data)
            }
        }

        return MemoryBankPorts(readPorts = readPorts, writePorts = emptyList())
    }
}

/**
 * Динамический банк памяти с единым портом записи и произвольным числом
 * портов чтения. Адреса и данные размечены по шаблону StaticMemoryBank.
 */
class DynamicMemoryBank(private val instName: String = "mem_dyn") {

    fun emit(g: Generic, cfg: MemoryBankConfig): MemoryBankPorts {
        require(cfg.writable) { "Dynamic bank requires writable=true" }
        val name = cfg.name

        val memDim = hw_dim_static(cfg.dataWidth).apply { add(cfg.depth, 0) }
        val mem = g.uglobal("${instName}_${name}", memDim, "0")

        val readPorts = (0 until cfg.ports).map { idx ->
            val rd = g.uglobal("rd_${name}_$idx", hw_dim_static(1), "0")
            val addr = g.uglobal("addr_${name}_$idx", hw_dim_static(cfg.addrWidth), "0")
            val data = g.uglobal("data_${name}_$idx", hw_dim_static(cfg.dataWidth), "0")
            g.begif(g.eq2(rd, 1)); run { data.assign(mem[addr]) }; g.endif()
            MemoryReadPort(rd, addr, data)
        }

        val wr = g.uglobal("we_${name}", hw_dim_static(1), "0")
        val wrAddr = g.uglobal("waddr_${name}", hw_dim_static(cfg.addrWidth), "0")
        val wrData = g.uglobal("wdata_${name}", hw_dim_static(cfg.dataWidth), "0")
        g.begif(g.eq2(wr, 1)); run { mem[wrAddr].assign(wrData) }; g.endif()

        return MemoryBankPorts(
            readPorts = readPorts,
            writePorts = listOf(MemoryWritePort(wr, wrAddr, wrData))
        )
    }
}

/**
 * Адаптер, строящий банк регистров из конфигурации памяти. Полезен для
 * подключения маленьких таблиц или состояния контроллера через единый API.
 */
class RegisterBankAdapter(private val instName: String = "reg_bank") {

    fun build(g: Generic, cfg: MemoryBankConfig, registerNames: List<String>): Map<String, hw_var> {
        require(cfg.registerAdapter) { "Register adapter requires registerAdapter=true in config" }
        require(registerNames.isNotEmpty()) { "At least one register name must be provided" }

        val regs = mutableMapOf<String, hw_var>()
        registerNames.forEachIndexed { idx, regName ->
            val reg = g.uglobal("${instName}_${cfg.name}_${regName}", hw_dim_static(cfg.dataWidth), "0")
            regs[regName] = reg
            if (cfg.writable) {
                val wr = g.uport("wr_${regName}", PORT_DIR.IN, hw_dim_static(1), "0")
                val data = g.uport("wd_${regName}", PORT_DIR.IN, hw_dim_static(cfg.dataWidth), "0")
                g.begif(g.eq2(wr, 1)); run { reg.assign(data) }; g.endif()
            }
        }
        return regs
    }
}
