package neuromorphix

import cyclix.Generic
import hwast.hw_var
import hwast.*


// FifoInput.kt

data class FifoCfg(
    val name: String,
    val dataWidth: Int,
    val depth: Int,
    val creditWidth: Int = 8,
    val useTickDoubleBuffer: Boolean = true   // если true — переключение «активного» банка по tick
)

data class FifoInIF(
    // внешний интерфейс (входящие спайки)
    val wr_i: hw_var,         // порт IN: запрос записи
    val wr_data_i: hw_var,    // порт IN: данные на запись
    val full_o: hw_var,       // порт OUT: FIFO полно

    // интерфейс чтения внутрь ядра
    val rd_o: hw_var,         // глобал/локал: запрос чтения (ядро ставит 1, чтобы вычитать слово)
    val rd_data_o: hw_var,    // глобал: данные на чтение
    val empty_o: hw_var,      // глобал: FIFO пуст

    // кредиты (удобно для наблюдения/управления)
    val wr_credit_o: hw_var,  // глобал: доступный размер записи «активного» банка
    val rd_credit_o: hw_var   // локал:  доступный размер чтения «пассивного» банка
)



class FifoInput(private val instName: String = "in_fifo") {

    fun emit(
        g: Generic,
        cfg: FifoCfg,
        tick: hw_var
    ): FifoInIF {
        val name = cfg.name

        // ширины
        val ptrW = NmMath.log2ceil(cfg.depth)

        // ===== Внешние порты записи =====
        val wr_i      = g.uport("wr_$name", PORT_DIR.IN,  hw_dim_static(1),        "0")
        val wr_data_i = g.uport("wr_data_$name", PORT_DIR.IN, hw_dim_static(cfg.dataWidth), "0")
        val full_o    = g.uport("full_$name", PORT_DIR.OUT, hw_dim_static(1),      "0")

        // ===== Внутренний интерфейс чтения =====
        val rd_o      = g.uglobal("rd_$name",       hw_dim_static(1),        "0")
        val rd_data_o = g.uglobal("rd_data_$name",  hw_dim_static(cfg.dataWidth), "0")
        val empty_o   = g.uglobal("empty_$name",    hw_dim_static(1),        "1")

        // ===== Регистровый массив FIFO =====
        val memDim = hw_dim_static(cfg.dataWidth).apply { add(cfg.depth, 0) }
        val mem    = g.uglobal("mem_$name", memDim, "0")

        // ===== Указатели и флаги =====
        val wptr      = g.uglobal("wptr_$name",       hw_dim_static(ptrW), "0")
        val rptr      = g.uglobal("rptr_$name",       hw_dim_static(ptrW), "0")
        val wptr_n    = g.uglobal("wptr_n_$name",     hw_dim_static(ptrW), "0")
        val rptr_n    = g.uglobal("rptr_n_$name",     hw_dim_static(ptrW), "0")
        val full_r    = g.uglobal("full_r_$name",     hw_dim_static(1),    "0")
        val full_n    = g.uglobal("full_n_$name",     hw_dim_static(1),    "0")
        val empty_n   = g.uglobal("empty_n_$name",    hw_dim_static(1),    "1")
        full_o.assign(full_r)

        // ===== Двойной банк кредитов (пишем в «активный», читаем из «пассивного») =====
        val crDim = hw_dim_static(cfg.creditWidth).apply { add(2, 0) } // [2][creditWidth]
        val credit = g.uglobal("credit_$name", crDim, "0")
        val act    = g.uglobal("act_$name",    hw_dim_static(1), "0")   // активный банк (0/1)

        if (cfg.useTickDoubleBuffer) {
            g.begif(g.eq2(tick, 1)); run {
                act.assign(g.bnot(act))
            }; g.endif()
        }

        val wr_en = g.uglobal("wr_en_$name", hw_dim_static(1), "0")
        wr_en.assign(g.land(wr_i, g.bnot(full_r)))

        // ===== Запись =====
        g.begif(g.eq2(wr_en, 1)); run {
            mem[wptr].assign(wr_data_i)
            credit[act].assign(credit[act].plus(1))
        }; g.endif()

        // ===== Доступные кредиты на запись/чтение =====
        val wr_credit_o = g.uglobal("wr_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        val rd_credit_o = g.ulocal ("rd_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        wr_credit_o.assign(credit[act])
        rd_credit_o.assign(credit[g.bnot(act)])

        // ===== Данные на чтение =====
        rd_data_o.assign(mem[rptr])

        // ===== Обновление указателей/флагов =====
        wptr.assign(wptr_n)
        rptr.assign(rptr_n)
        empty_o.assign(empty_n)
        full_r.assign(full_n)

        // ===== Алгоритм на комбинации {wr, rd} через case =====
        // cnct(wr, rd): 01=чтение, 10=запись, 11=оба
        g.begcase(g.cnct(wr_i, rd_o)); run {

            // 2'b01 — чтение
            g.begbranch(1); run {
            g.begif(g.bnot(empty_o)); run {
            rptr_n.assign(rptr.plus(1))
            full_n.assign(0)

            // кредиты в «пассивном» банке уменьшаем (но не уходим в минус)
            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[g.bnot(act)].assign(credit[g.bnot(act)].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[g.bnot(act)].assign(0)
        }; g.endif()

            g.begif(g.eq2(rptr.plus(1), wptr)); run {
            empty_n.assign(1)
        }; g.endif()
        }; g.endif()
            g.begelse(); run {
            credit[g.bnot(act)].assign(0)   // если реально пусто — кредиты на чтение = 0
        }; g.endif()
        }; g.endbranch()

            // 2'b10 — запись
            g.begbranch(2); run {
            g.begif(g.bnot(full_r)); run {
            wptr_n.assign(wptr.plus(1))
            empty_n.assign(0)
            g.begif(g.eq2(wptr.plus(1), rptr)); run {
            full_n.assign(1)
        }; g.endif()
        }; g.endif()
        }; g.endbranch()

            // 2'b11 — запись и чтение
            g.begbranch(3); run {
            wptr_n.assign(wptr.plus(1))
            rptr_n.assign(rptr.plus(1))

            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[g.bnot(act)].assign(credit[g.bnot(act)].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[g.bnot(act)].assign(0)
        }; g.endif()
        }; g.endbranch()
        }; g.endcase()

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

//// те же утилиты/конфиги, что и у входного FIFO
//data class FifoCfg(
//    val name: String,
//    val dataWidth: Int,
//    val depth: Int,
//    val creditWidth: Int = 8,
//    val useTickDoubleBuffer: Boolean = true
//)

// для симметрии с FifoInIF
data class FifoOutIF(
    // ВНЕШНИЙ read-интерфейс
    val rd_i: hw_var,          // IN  (порт): внешний строб чтения
    val rd_data_o: hw_var,     // OUT (порт): внешние данные
    val empty_o: hw_var,       // OUT (порт): пусто для внешнего мира

    // ВНУТРЕННИЙ write-интерфейс (ядро -> FIFO)
    val we_i: hw_var,          // IN  (глобал): строб записи от ядра
    val wr_data_i: hw_var,     // IN  (глобал): данные от ядра
    val full_o: hw_var,        // OUT (глобал): бэкпрешер ядру

    // диагностические/служебные кредиты (как и у входного)
    val rd_credit_o: hw_var,
    val wr_credit_o: hw_var
)

class FifoOutput(private val instName: String = "out_fifo") {

    // если у тебя уже есть NmMath.log2ceil — используй его
    private fun NmMath.log2ceil(x: Int): Int {
        require(x > 0) { "x must be > 0" }
        var v = x - 1
        var r = 0
        while (v > 0) { v = v shr 1; r++ }
        return maxOf(r, 1)
    }

    fun emit(
        g: Generic,
        cfg: FifoCfg,
        tick: hw_var
    ): FifoOutIF {
        val name = cfg.name
        val ptrW = NmMath.log2ceil(cfg.depth)

        // ===== ВНЕШНИЕ ПОРТЫ ЧТЕНИЯ =====
        val rd_i      = g.uport("rd_$name",       PORT_DIR.IN,  hw_dim_static(1),             "0")
        val rd_data_o = g.uport("rd_data_$name",  PORT_DIR.OUT, hw_dim_static(cfg.dataWidth), "0")
        val empty_o   = g.uport("empty_$name",    PORT_DIR.OUT, hw_dim_static(1),             "1")

        // ===== ВНУТРЕННИЙ ИНТЕРФЕЙС ЗАПИСИ (ядро -> FIFO) =====
        val we_i      = g.uglobal("we_$name",      hw_dim_static(1),             "0")
        val wr_data_i = g.uglobal("wr_data_$name", hw_dim_static(cfg.dataWidth), "0")
        val full_o    = g.uglobal("full_$name",    hw_dim_static(1),             "0")

        // ===== ПАМЯТЬ FIFO =====
        val memDim = hw_dim_static(cfg.dataWidth).apply { add(cfg.depth, 0) }
        val mem    = g.uglobal("mem_$name", memDim, "0")

        // ===== УКАЗАТЕЛИ/ФЛАГИ =====
        val wptr   = g.uglobal("wptr_$name",   hw_dim_static(ptrW), "0")
        val rptr   = g.uglobal("rptr_$name",   hw_dim_static(ptrW), "0")
        val wptr_n = g.uglobal("wptr_n_$name", hw_dim_static(ptrW), "0")
        val rptr_n = g.uglobal("rptr_n_$name", hw_dim_static(ptrW), "0")

        val full_r = full_o
        val full_n = g.uglobal("full_n_$name",  hw_dim_static(1), "0")
        val empty_r= g.uglobal("empty_r_$name", hw_dim_static(1), "1")
        val empty_n= g.uglobal("empty_n_$name", hw_dim_static(1), "1")

        // наружу — зарегистрированное empty
        empty_o.assign(empty_r)

        // ===== КРЕДИТ-БАНКИ (двойная буферизация по tick) =====
        val crDim  = hw_dim_static(cfg.creditWidth).apply { add(2, 0) }  // [2][creditWidth]
        val credit = g.uglobal("credit_$name", crDim, "0")
        val act    = g.uglobal("act_$name",    hw_dim_static(1), "0")    // активный банк (0/1) — для записей

        if (cfg.useTickDoubleBuffer) {
            g.begif(g.eq2(tick, 1)); run {
                act.assign(g.bnot(act))
            }; g.endif()
        }

        // ===== ЗАПИСЬ (ядро -> FIFO) =====
        val wr_en = g.uglobal("wr_en_$name", hw_dim_static(1), "0")
        wr_en.assign(g.land(we_i, g.bnot(full_r)))

        g.begif(g.eq2(wr_en, 1)); run {
            mem[wptr].assign(wr_data_i)
            credit[act].assign(credit[act].plus(1))
        }; g.endif()

        // ===== КРЕДИТЫ НА ВЫХОД =====
        val wr_credit_o = g.uglobal("wr_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        val rd_credit_o = g.ulocal ("rd_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        wr_credit_o.assign(credit[act])
        rd_credit_o.assign(credit[g.bnot(act)])

        // ===== ДАННЫЕ ДЛЯ НАРУЖНЕГО ЧТЕНИЯ =====
        rd_data_o.assign(mem[rptr])

        // ===== ОБНОВЛЕНИЕ УКАЗАТЕЛЕЙ/ФЛАГОВ =====
        wptr.assign(wptr_n)
        rptr.assign(rptr_n)
        empty_r.assign(empty_n)
        full_r.assign(full_n)

        // ===== КОМБИНАЦИЯ СОБЫТИЙ {we, rd} =====
        // 01 — только чтение; 10 — только запись; 11 — оба
        g.begcase(g.cnct(we_i, rd_i)); run {

            // 2'b01 чтение наружу
            g.begbranch(1); run {
            g.begif(g.bnot(empty_r)); run {
            rptr_n.assign(rptr.plus(1))
            full_n.assign(0)

            // кредиты «пассивного» банка уменьшаем (не уходим в минус)
            val other = g.bnot(act)
            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[other].assign(credit[other].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[other].assign(0)
        }; g.endif()

            // пусто, если догнали write pointer
            g.begif(g.eq2(rptr.plus(1), wptr)); run {
            empty_n.assign(1)
        }; g.endif()
        }; g.endif()
            g.begelse(); run {
            // реально пусто — кредитов на чтение нет
            credit[g.bnot(act)].assign(0)
        }; g.endif()
        }; g.endbranch()

            // 2'b10 запись из ядра
            g.begbranch(2); run {
            g.begif(g.bnot(full_r)); run {
            wptr_n.assign(wptr.plus(1))
            empty_n.assign(0)
            g.begif(g.eq2(wptr.plus(1), rptr)); run {
            full_n.assign(1)
        }; g.endif()
        }; g.endif()
        }; g.endbranch()

            // 2'b11 и запись, и чтение
            g.begbranch(3); run {
            wptr_n.assign(wptr.plus(1))
            rptr_n.assign(rptr.plus(1))

            val other = g.bnot(act)
            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[other].assign(credit[other].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[other].assign(0)
        }; g.endif()
        }; g.endbranch()
        }; g.endcase()

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
