package bnmm.selector

import cyclix.Generic
import hwast.PORT_DIR
import hwast.hw_dim_static
import hwast.hw_imm
import hwast.hw_var

/**
 * Describes how synaptic parameters are packed inside a memory word.
 * The selector supports classic layouts where 1/2/4/8 weights are stored in a word.
 */
data class SynapticPackingConfig(
    val wordWidth: Int,
    val weightWidth: Int,
    val weightsPerWord: Int = 1
) {
    val packShift: Int
    val laneMask: Int

    init {
        require(wordWidth > 0) { "Memory word width must be positive" }
        require(weightWidth > 0) { "Weight width must be positive" }
        require(weightsPerWord in listOf(1, 2, 4, 8)) {
            "Supported packing factors are 1, 2, 4 or 8 weights per word"
        }
        require(weightWidth * weightsPerWord == wordWidth) {
            "wordWidth must equal weightWidth * weightsPerWord"
        }

        packShift = when (weightsPerWord) {
            1 -> 0
            2 -> 1
            4 -> 2
            8 -> 3
            else -> error("Unexpected packing factor")
        }
        laneMask = if (packShift == 0) 0 else (1 shl packShift) - 1
    }

    fun describe(): String =
        "wordWidth=${wordWidth}b weightWidth=${weightWidth}b weightsPerWord=$weightsPerWord packShift=$packShift laneMask=0x${laneMask.toString(16)}"
}

/**
 * Static configuration parameters for the selector instance.
 */
data class SynapseSelectorConfig(
    val name: String = "syn_sel",
    val addrWidth: Int,
    val preIndexWidth: Int,
    val postIndexWidth: Int,
    val packing: SynapticPackingConfig,
    /**
     * Address stride between adjacent packed words, in bytes. Defaults to the byte size of
     * [packing.wordWidth], which matches the common case where memories are byte-addressed and each
     * word is contiguous.
     */
    val wordByteStride: Int = packing.wordWidth / 8,
    val useLinearAddress: Boolean = true,
    val stepByTick: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Selector name must not be blank" }
        require(addrWidth > 0) { "Address width must be positive" }
        require(preIndexWidth > 0) { "Pre-synaptic index width must be positive" }
        require(postIndexWidth > 0) { "Post-synaptic index width must be positive" }
        require(wordByteStride > 0) { "Word byte stride must be positive" }
    }

    fun describe(): String =
        "name=$name addrWidth=${addrWidth}b preIndexWidth=${preIndexWidth}b postIndexWidth=${postIndexWidth}b wordByteStride=${wordByteStride} stepByTick=$stepByTick"
}

/**
 * Design-time values passed from the parent design (e.g., from architectural plans).
 * postsynCount and baseAddress are usually provided as external registers.
 */
data class SynapseSelectorRuntime(
    val postsynCount: hw_var,
    val baseAddress: hw_var? = null
)

/**
 * Simple read-only memory port used by the selector. It intentionally mirrors a
 * classic BRAM interface: address/enables are driven by the selector, data is
 * driven by the parent design.
 */
data class ReadPort(
    val addr: hw_var,
    val en: hw_var?,
    val data: hw_var,
    val we: hw_var? = null
)

/**
 * Ports exported by the selector. They are created as globals so the caller can
 * bind them to the rest of the design or to a top-level test harness.
 */
data class SynapseSelectorPorts(
    val start: hw_var,
    val preIndex: hw_var,
    val busy: hw_var,
    val done: hw_var,
    val postIndex: hw_var,
    val weight: hw_var
)

/**
 * Helper factory to allocate a simple BRAM-like read port.
 */
object ReadPortFactory {
    fun create(g: Generic, name: String, addrWidth: Int, dataWidth: Int, useEnable: Boolean = true): ReadPort {
        val addr = g.uport("${name}_addr", PORT_DIR.OUT, hw_dim_static(addrWidth), "0")
        val data = g.uport("${name}_data", PORT_DIR.IN, hw_dim_static(dataWidth), "0")
        val en = if (useEnable) g.uport("${name}_en", PORT_DIR.OUT, hw_dim_static(1), "0") else null
        return ReadPort(addr = addr, en = en, data = data)
    }
}

/**
 * Configurable synapse selector with optional packing support. The generator is
 * intentionally minimalist: it produces a small FSM that iterates over all
 * post-synaptic indices for a latched pre-synaptic index and computes the
 * corresponding memory address. When packing is enabled, it also extracts the
 * selected weight lane from the incoming memory word.
 */
class SynapseSelector(private val instName: String = "syn_sel") {

    fun emit(
        g: Generic,
        cfg: SynapseSelectorConfig,
        runtime: SynapseSelectorRuntime,
        mem: ReadPort,
        tick: hw_var? = null
    ): SynapseSelectorPorts {
        val name = cfg.name

        // Temporary fixed profile requested by hardware integration:
        // - 2 parameters per memory word
        // - 16-bit memory word
        // - byte-addressed memory interface (address step = 1 byte)
        val fixedWeightsPerWord = 2
        val fixedWordWidth = 16
        val fixedWordByteStride = 2
        require(cfg.packing.weightsPerWord == fixedWeightsPerWord) {
            "Current selector profile requires exactly 2 parameters per word"
        }
        require(cfg.packing.wordWidth == fixedWordWidth) {
            "Current selector profile requires 16-bit memory words"
        }
        require(cfg.packing.weightWidth * cfg.packing.weightsPerWord == cfg.packing.wordWidth) {
            "Current selector profile expects packed parameters to exactly fill the 16-bit word"
        }
        require(cfg.wordByteStride == fixedWordByteStride) {
            "Current selector profile requires byte-addressed memory with 16-bit words (stride=2 bytes)"
        }

        // Control inputs (globals to allow external driving).
        val start_i = g.uglobal("start_$name", hw_dim_static(1), "0")
        val preIdx_i = g.uglobal("preidx_$name", hw_dim_static(cfg.preIndexWidth), "0")

        // Status and observation signals.
        val busy_o = g.uglobal("busy_$name", hw_dim_static(1), "0")
        val done_o = g.uglobal("done_$name", hw_dim_static(1), "0")
        val postIdx = g.uglobal("postidx_$name", hw_dim_static(cfg.postIndexWidth), "0")
        val preLatched = g.uglobal("prelatched_$name", hw_dim_static(cfg.preIndexWidth), "0")

        // Weight output after optional unpacking.
        val weight = g.uglobal("weight_$name", hw_dim_static(cfg.packing.weightWidth), "0")

        // Enable for a single iteration step (tick-controlled or free running).
        val stepEn = g.uglobal("step_en_$name", hw_dim_static(1), "0")
        stepEn.assign(
            if (cfg.stepByTick) {
                if (tick != null) g.eq2(tick, 1) else hw_imm(0)
            } else {
                hw_imm(1)
            }
        )

        // FSM state.
        val S_IDLE = 0
        val S_RUN = 1
        val S_LAST_RESP = 2
        val state = g.uglobal("state_$name", hw_dim_static(2), "0")
        val stateNext = g.uglobal("state_n_$name", hw_dim_static(2), "0")

        // Lane latched for final response extraction after the last request.
        val laneLatched = g.uglobal("lane_lat_$name", hw_dim_static(maxOf(1, cfg.packing.packShift)), "0")
        val laneLatchedNext = g.uglobal("lane_lat_n_$name", hw_dim_static(maxOf(1, cfg.packing.packShift)), "0")

        // Default assignments.
        state.assign(stateNext)
        stateNext.assign(state)
        laneLatched.assign(laneLatchedNext)
        laneLatchedNext.assign(laneLatched)
        done_o.assign(0)
        mem.en?.assign(0)
        mem.we?.assign(0)

        val doStep = g.land(stepEn, busy_o)

        // IDLE: latch preIndex when start is asserted.
        g.begif(g.eq2(state, S_IDLE)); run {
            busy_o.assign(0)
            g.begif(g.eq2(start_i, 1)); run {
                preLatched.assign(preIdx_i)
                postIdx.assign(0)
                rowBase.assign(0)
                preCnt.assign(preIdx_i)
                busy_o.assign(1)
                stateNext.assign(S_INIT)
            }; g.endif()
        }; g.endif()

        // INIT: compute row base index = preIdx * postsynCount using repeated addition.
        g.begif(g.eq2(state, S_INIT)); run {
            busy_o.assign(1)
            g.begif(g.eq2(preCnt, 0)); run {
                stateNext.assign(S_RUN)
            }; g.endif()
            g.begelse(); run {
                rowBase.assign(g.add(rowBase, runtime.postsynCount))
                preCnt.assign(g.sub(preCnt, hw_imm(1)))
            }; g.endif()
        }; g.endif()

//        g.begif(g.eq2(state, S_GET_VAL)); run {
////            busy_o.assign(0)
////            g.begif(g.eq2(start_i, 1)); run {
////            preLatched.assign(preIdx_i)
////            postIdx.assign(0)
////            busy_o.assign(1)
//            stateNext.assign(S_RUN)
////        }; g.endif()
//        }; g.endif()

        // RUN: iterate over post-synaptic indices and generate addresses.
        g.begif(g.eq2(state, S_RUN)); run {
            busy_o.assign(1)

            // Compute linear address = pre * postsynCount + post.
            val fullIndex = g.add(rowBase, postIdx)
            val rawAddr = if (cfg.useLinearAddress) fullIndex else g.cnct(preLatched, postIdx)
            val addrWithBase = if (runtime.baseAddress != null) g.add(rawAddr, runtime.baseAddress) else rawAddr

            // Fixed packing profile (2 params per 16-bit word):
            // word address = floor(index/2), lane = index % 2.
            // Byte-addressed memory with 16-bit words => byte addr = wordAddr << 1.
            val wordAddr = g.srl(addrWithBase, hw_imm(1))
            val lane = g.band(addrWithBase, hw_imm(1))
            val addrWithStride = g.sll(wordAddr, hw_imm(1))

            // Drive memory interface (request phase).
            mem.addr.assign(addrWithStride)
            mem.en?.assign(doStep)
            mem.we?.assign(0)

            // Step through post indices.
            g.begif(g.eq2(doStep, 1)); run {
            // Extract returned weight for previously requested address.
            if (cfg.packing.weightsPerWord == 1) {
                weight.assign(mem.data)
            } else {
                for (i in 0 until cfg.packing.weightsPerWord) {
                    val lsb = i * cfg.packing.weightWidth
                    val msb = lsb + cfg.packing.weightWidth - 1
                    g.begif(g.eq2(lane, hw_imm(i))); run {
                        weight.assign(mem.data[msb, lsb])
                    }; g.endif()
                }
            }
            laneLatchedNext.assign(lane)

            val last = g.eq2(postIdx, g.sub(runtime.postsynCount, hw_imm(1)))
            g.begif(last); run {
            // For synchronous BRAM the final data arrives one cycle later.
            // Move to LAST_RESP so the last weight can be latched.
            stateNext.assign(S_LAST_RESP)
        }; g.endif()

        // LAST_RESP: capture the final BRAM response and only then assert done.
        g.begif(g.eq2(state, S_LAST_RESP)); run {
            busy_o.assign(1)
            mem.en?.assign(0)
            mem.we?.assign(0)

            if (cfg.packing.weightsPerWord == 1) {
                weight.assign(mem.data)
            } else {
                for (i in 0 until cfg.packing.weightsPerWord) {
                    val lsb = i * cfg.packing.weightWidth
                    val msb = lsb + cfg.packing.weightWidth - 1
                    g.begif(g.eq2(laneLatched, hw_imm(i))); run {
                        weight.assign(mem.data[msb, lsb])
                    }; g.endif()
                }
            }

            done_o.assign(1)
            busy_o.assign(0)
            stateNext.assign(S_IDLE)
        }; g.endif()

        return SynapseSelectorPorts(
            start = start_i,
            preIndex = preIdx_i,
            busy = busy_o,
            done = done_o,
            postIndex = postIdx,
            weight = weight
        )
    }
}
