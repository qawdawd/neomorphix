package bnmm.queue

import cyclix.Generic
import hwast.PORT_DIR
import hwast.hw_dim_static
import hwast.hw_var

/**
 * Configuration for a single FIFO instance.
 *
 * @param name logical name used in generated signal identifiers
 * @param dataWidth width of each data word in bits
 * @param depth number of entries in the queue (must be power-of-two friendly)
 * @param creditWidth width of credit counters used for observability/backpressure
 * @param useTickDoubleBuffer when true, write/read credits are double-buffered and swap on tick
 */
data class FifoConfig(
    val name: String,
    val dataWidth: Int,
    val depth: Int,
    val creditWidth: Int = 8,
    val useTickDoubleBuffer: Boolean = true
) {
    init {
        require(name.isNotBlank()) { "FIFO name must not be blank" }
        require(dataWidth > 0) { "Data width must be positive" }
        require(depth > 0) { "Depth must be positive" }
        require(creditWidth > 0) { "Credit width must be positive" }
    }
}

/**
 * External and internal signals for an input FIFO (ingress).
 */
data class FifoInIF(
    // External write interface
    val wr_i: hw_var,
    val wr_data_i: hw_var,
    val full_o: hw_var,

    // Core-side read interface
    val rd_o: hw_var,
    val rd_data_o: hw_var,
    val empty_o: hw_var,

    // Diagnostics/credits
    val wr_credit_o: hw_var,
    val rd_credit_o: hw_var
)

/**
 * External and internal signals for an output FIFO (egress).
 */
data class FifoOutIF(
    // External read interface
    val rd_i: hw_var,
    val rd_data_o: hw_var,
    val empty_o: hw_var,

    // Core-side write interface
    val we_i: hw_var,
    val wr_data_i: hw_var,
    val full_o: hw_var,

    // Diagnostics/credits
    val rd_credit_o: hw_var,
    val wr_credit_o: hw_var
)

/**
 * Simple math helpers used locally by queue generators.
 */
private object QueueMath {
    fun log2ceil(value: Int): Int {
        require(value > 0) { "log2ceil: value must be > 0" }
        var v = value - 1
        var r = 0
        while (v > 0) {
            v = v shr 1
            r++
        }
        return maxOf(r, 1)
    }
}

/**
 * Ingress FIFO with optional tick-driven double buffering of credits.
 * Uses a straightforward pointer-based implementation with simultaneous read/write support.
 */
class FifoInput(private val instName: String = "in_fifo") {

    fun emit(g: Generic, cfg: FifoConfig, tick: hw_var? = null): FifoInIF {
        require(!cfg.useTickDoubleBuffer || tick != null) {
            "Tick signal must be provided when useTickDoubleBuffer=true"
        }

        val name = cfg.name
        val ptrW = QueueMath.log2ceil(cfg.depth)

        // External write ports
        val wr_i = g.uport("wr_$name", PORT_DIR.IN, hw_dim_static(1), "0")
        val wr_data_i = g.uport("wr_data_$name", PORT_DIR.IN, hw_dim_static(cfg.dataWidth), "0")
        val full_o = g.uport("full_$name", PORT_DIR.OUT, hw_dim_static(1), "0")

        // Core-side read interface
        val rd_o = g.uglobal("rd_$name", hw_dim_static(1), "0")
        val rd_data_o = g.uglobal("rd_data_$name", hw_dim_static(cfg.dataWidth), "0")
        val empty_o = g.uglobal("empty_$name", hw_dim_static(1), "1")

        // Memory storage [depth][dataWidth]
        val memDim = hw_dim_static(cfg.dataWidth).apply { add(cfg.depth, 0) }
        val mem = g.uglobal("mem_$name", memDim, "0")

        // Pointers and state flags
        val wptr = g.uglobal("wptr_$name", hw_dim_static(ptrW), "0")
        val rptr = g.uglobal("rptr_$name", hw_dim_static(ptrW), "0")
        val wptrNext = g.uglobal("wptr_n_$name", hw_dim_static(ptrW), "0")
        val rptrNext = g.uglobal("rptr_n_$name", hw_dim_static(ptrW), "0")
        val fullReg = g.uglobal("full_r_$name", hw_dim_static(1), "0")
        val fullNext = g.uglobal("full_n_$name", hw_dim_static(1), "0")
        val emptyNext = g.uglobal("empty_n_$name", hw_dim_static(1), "1")
        full_o.assign(fullReg)

        // Credit banks (two banks allow tick-driven swap between write/read domains)
        val creditDim = hw_dim_static(cfg.creditWidth).apply { add(2, 0) }
        val credit = g.uglobal("credit_$name", creditDim, "0")
        val act = g.uglobal("act_$name", hw_dim_static(1), "0") // active bank for writes
        val readBank = if (cfg.useTickDoubleBuffer) g.bnot(act) else act

        if (cfg.useTickDoubleBuffer) {
            g.begif(g.eq2(tick!!, 1)); run {
                act.assign(g.bnot(act))
            }; g.endif()
        }

        val wr_en = g.uglobal("wr_en_$name", hw_dim_static(1), "0")
        wr_en.assign(g.land(wr_i, g.bnot(fullReg)))

        // Default next-state assignments to hold values when idle
        wptrNext.assign(wptr)
        rptrNext.assign(rptr)
        fullNext.assign(fullReg)
        emptyNext.assign(empty_o)

        // Write path
        g.begif(g.eq2(wr_en, 1)); run {
            mem[wptr].assign(wr_data_i)
            credit[act].assign(credit[act].plus(1))
        }; g.endif()

        val wr_credit_o = g.uglobal("wr_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        val rd_credit_o = g.ulocal("rd_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        wr_credit_o.assign(credit[act])
        rd_credit_o.assign(credit[readBank])

        // Current read data
        rd_data_o.assign(mem[rptr])

        // Combined read/write resolution (00 = idle, 01 = read, 10 = write, 11 = both)
        g.begcase(g.cnct(wr_i, rd_o)); run {
            // 2'b01 — read only
            g.begbranch(1); run {
            g.begif(g.bnot(empty_o)); run {
            rptrNext.assign(rptr.plus(1))
            fullNext.assign(0)

            // Decrement credits on the passive/read bank
            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[readBank].assign(credit[readBank].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[readBank].assign(0)
        }; g.endif()

            g.begif(g.eq2(rptr.plus(1), wptr)); run {
            emptyNext.assign(1)
        }; g.endif()
        }; g.endif()
            g.begelse(); run {
            // If empty, credits for read bank are zeroed
            credit[readBank].assign(0)
        }; g.endif()
        }; g.endbranch()

            // 2'b10 — write only
            g.begbranch(2); run {
            g.begif(g.bnot(fullReg)); run {
            wptrNext.assign(wptr.plus(1))
            emptyNext.assign(0)
            g.begif(g.eq2(wptr.plus(1), rptr)); run {
            fullNext.assign(1)
        }; g.endif()
        }; g.endif()
        }; g.endbranch()

            // 2'b11 — simultaneous write and read
            g.begbranch(3); run {
            wptrNext.assign(wptr.plus(1))
            rptrNext.assign(rptr.plus(1))

            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[readBank].assign(credit[readBank].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[readBank].assign(0)
        }; g.endif()
        }; g.endbranch()
        }; g.endcase()

        // Update pointers and flags after resolving the current cycle intent
        wptr.assign(wptrNext)
        rptr.assign(rptrNext)
        empty_o.assign(emptyNext)
        fullReg.assign(fullNext)

        return FifoInIF(
            wr_i = wr_i,
            wr_data_i = wr_data_i,
            full_o = full_o,
            rd_o = rd_o,
            rd_data_o = rd_data_o,
            empty_o = empty_o,
            wr_credit_o = wr_credit_o,
            rd_credit_o = rd_credit_o
        )
    }
}

/**
 * Egress FIFO mirroring the ingress structure but with external read interface.
 */
class FifoOutput(private val instName: String = "out_fifo") {

    fun emit(g: Generic, cfg: FifoConfig, tick: hw_var? = null): FifoOutIF {
        require(!cfg.useTickDoubleBuffer || tick != null) {
            "Tick signal must be provided when useTickDoubleBuffer=true"
        }

        val name = cfg.name
        val ptrW = QueueMath.log2ceil(cfg.depth)

        // External read interface
        val rd_i = g.uport("rd_$name", PORT_DIR.IN, hw_dim_static(1), "0")
        val rd_data_o = g.uport("rd_data_$name", PORT_DIR.OUT, hw_dim_static(cfg.dataWidth), "0")
        val empty_o = g.uport("empty_$name", PORT_DIR.OUT, hw_dim_static(1), "1")

        // Core-side write interface
        val we_i = g.uglobal("we_$name", hw_dim_static(1), "0")
        val wr_data_i = g.uglobal("wr_data_$name", hw_dim_static(cfg.dataWidth), "0")
        val full_o = g.uglobal("full_$name", hw_dim_static(1), "0")

        // Memory storage [depth][dataWidth]
        val memDim = hw_dim_static(cfg.dataWidth).apply { add(cfg.depth, 0) }
        val mem = g.uglobal("mem_$name", memDim, "0")

        // Pointers and flags
        val wptr = g.uglobal("wptr_$name", hw_dim_static(ptrW), "0")
        val rptr = g.uglobal("rptr_$name", hw_dim_static(ptrW), "0")
        val wptrNext = g.uglobal("wptr_n_$name", hw_dim_static(ptrW), "0")
        val rptrNext = g.uglobal("rptr_n_$name", hw_dim_static(ptrW), "0")
        val fullNext = g.uglobal("full_n_$name", hw_dim_static(1), "0")
        val emptyReg = g.uglobal("empty_r_$name", hw_dim_static(1), "1")
        val emptyNext = g.uglobal("empty_n_$name", hw_dim_static(1), "1")

        // Registered empty flag is exported
        empty_o.assign(emptyReg)

        // Credit banks
        val creditDim = hw_dim_static(cfg.creditWidth).apply { add(2, 0) }
        val credit = g.uglobal("credit_$name", creditDim, "0")
        val act = g.uglobal("act_$name", hw_dim_static(1), "0") // active bank for writes
        val readBank = if (cfg.useTickDoubleBuffer) g.bnot(act) else act

        if (cfg.useTickDoubleBuffer) {
            g.begif(g.eq2(tick!!, 1)); run {
                act.assign(g.bnot(act))
            }; g.endif()
        }

        val wr_en = g.uglobal("wr_en_$name", hw_dim_static(1), "0")
        wr_en.assign(g.land(we_i, g.bnot(full_o)))

        // Default next-state values
        wptrNext.assign(wptr)
        rptrNext.assign(rptr)
        emptyNext.assign(emptyReg)
        fullNext.assign(full_o)

        // Write path from core into FIFO
        g.begif(g.eq2(wr_en, 1)); run {
            mem[wptr].assign(wr_data_i)
            credit[act].assign(credit[act].plus(1))
        }; g.endif()

        val wr_credit_o = g.uglobal("wr_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        val rd_credit_o = g.ulocal("rd_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        wr_credit_o.assign(credit[act])
        rd_credit_o.assign(credit[readBank])

        // Data visible to external consumer
        rd_data_o.assign(mem[rptr])

        // Combined resolution of write/read events
        g.begcase(g.cnct(we_i, rd_i)); run {
            // 2'b01 — external read
            g.begbranch(1); run {
            g.begif(g.bnot(emptyReg)); run {
            rptrNext.assign(rptr.plus(1))
            fullNext.assign(0)

            val other = readBank
            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[other].assign(credit[other].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[other].assign(0)
        }; g.endif()

            g.begif(g.eq2(rptr.plus(1), wptr)); run {
            emptyNext.assign(1)
        }; g.endif()
        }; g.endif()
            g.begelse(); run {
            credit[readBank].assign(0)
        }; g.endif()
        }; g.endbranch()

            // 2'b10 — write from core
            g.begbranch(2); run {
            g.begif(g.bnot(full_o)); run {
            wptrNext.assign(wptr.plus(1))
            emptyNext.assign(0)
            g.begif(g.eq2(wptr.plus(1), rptr)); run {
            fullNext.assign(1)
        }; g.endif()
        }; g.endif()
        }; g.endbranch()

            // 2'b11 — write and read in same cycle
            g.begbranch(3); run {
            wptrNext.assign(wptr.plus(1))
            rptrNext.assign(rptr.plus(1))

            val other = readBank
            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[other].assign(credit[other].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[other].assign(0)
        }; g.endif()
        }; g.endbranch()
        }; g.endcase()

        // Update pointers and flags after resolving the current cycle intent
        wptr.assign(wptrNext)
        rptr.assign(rptrNext)
        emptyReg.assign(emptyNext)
        full_o.assign(fullNext)

        return FifoOutIF(
            rd_i = rd_i,
            rd_data_o = rd_data_o,
            empty_o = empty_o,
            we_i = we_i,
            wr_data_i = wr_data_i,
            full_o = full_o,
            rd_credit_o = rd_credit_o,
            wr_credit_o = wr_credit_o
        )
    }
}
