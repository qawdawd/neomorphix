module core_tb #(
    parameter SPIKE_WIDTH     = 32,
    parameter ADDR_WIDTH_WORD = 32,
    parameter DATA_WIDTH      = 32, 

    // Сколько 32-битных слов в модели памяти весов
    parameter int WEIGHTS_MEM_WORDS = 4096
);

    logic clk = 0;
    logic rst;
    logic en_lif;
    logic en_tick_manual;
    logic rst_tick_manual;
    logic tick_o_tick_manual;
    logic wr_fifo_in;
    logic [SPIKE_WIDTH-1:0] wr_data_fifo_in;
    logic full_fifo_in;
    logic rd_fifo_out;
    logic [SPIKE_WIDTH-1:0] rd_data_fifo_out;
    logic empty_fifo_out;
    logic [ADDR_WIDTH_WORD-1:0] bram_addr_weights;
    logic bram_en_weights;
    logic [3:0]             bram_we_weights;
    logic [DATA_WIDTH-1:0]  bram_dout_weights;
    logic wr_leakage;
    logic [15:0]            wd_leakage;
    logic wr_threshold;
    logic [15:0]            wd_threshold;
    logic wr_vreset;
    logic [15:0]            wd_vreset;
    logic wr_weight_base;
    logic [15:0]            wd_weight_base;
    logic wr_neuron_base;
    logic [15:0]            wd_neuron_base;
    logic wr_postsyn_count;
    logic [15:0]            wd_postsyn_count;
    logic wr_emit_tag;
    logic [15:0]            wd_emit_tag;
    logic emission_done;

    always #5 clk = ~clk;

    bnmm_manual_demo u_core (
        .clk_i            (clk),
        .rst_i            (rst),
        .en_lif           (en_lif),
        .en_tick_manual   (en_tick_manual),
        .rst_tick_manual  (rst_tick_manual),
        .tick_o_tick_manual (tick_o_tick_manual),
        .wr_fifo_in       (wr_fifo_in),
        .wr_data_fifo_in  (wr_data_fifo_in),
        .full_fifo_in     (full_fifo_in),
        .rd_fifo_out      (rd_fifo_out),
        .rd_data_fifo_out (rd_data_fifo_out),
        .empty_fifo_out   (empty_fifo_out),
        .bram_addr_weights(bram_addr_weights),
        .bram_en_weights  (bram_en_weights),
        .bram_we_weights  (bram_we_weights),
        .bram_dout_weights(bram_dout_weights),
        .wr_leakage       (wr_leakage),
        .wd_leakage       (wd_leakage),
        .wr_threshold     (wr_threshold),
        .wd_threshold     (wd_threshold),
        .wr_vreset        (wr_vreset),
        .wd_vreset        (wd_vreset),
        .wr_weight_base   (wr_weight_base),
        .wd_weight_base   (wd_weight_base),
        .wr_neuron_base   (wr_neuron_base),
        .wd_neuron_base   (wd_neuron_base),
        .wr_postsyn_count (wr_postsyn_count),
        .wd_postsyn_count (wd_postsyn_count),
        .wr_emit_tag      (wr_emit_tag),
        .wd_emit_tag      (wd_emit_tag),
        .emission_done    (emission_done) 
    );

    // --- Waveform dump ---
    initial begin
        $dumpfile("build/core_tb.vcd"); // или .vcd
        $dumpvars(0, core_tb);
    end

    integer i;

    task write_reg(
        output logic wr,
        output logic [15:0] wd,
        input  [15:0] value
    );
    begin
        @(posedge clk);
        wr <= 1'b1;
        wd <= value;
        @(posedge clk);
        wr <= 1'b0;
    end
    endtask

    // ============================================================
    // 1-cycle latency BRAM weights model (read-only for now)
    // ============================================================

    logic [DATA_WIDTH-1:0] weights_mem [0:WEIGHTS_MEM_WORDS-1];

    // защита от выхода за диапазон: если адрес > глубины, отдадим 0
    function automatic logic [DATA_WIDTH-1:0] weights_read(input logic [ADDR_WIDTH_WORD-1:0] addr);
        if (addr < WEIGHTS_MEM_WORDS)
            return weights_mem[addr];
        else
            return '0;
    endfunction

    // зарегистрированные сигналы чтения (адрес/enable) -> 1 такт латентности
    logic [ADDR_WIDTH_WORD-1:0] rd_addr_q;
    logic                       rd_en_q;

    always_ff @(posedge clk) begin
        if (rst) begin
            rd_addr_q         <= '0;
            rd_en_q           <= 1'b0;
            bram_dout_weights <= '0;
        end else begin
            // latch request
            rd_addr_q <= bram_addr_weights;
            rd_en_q   <= bram_en_weights;

            // serve previous cycle request (1-cycle latency)
            if (rd_en_q) bram_dout_weights <= weights_read(rd_addr_q);
            else         bram_dout_weights <= '0;
        end
    end

    // (опционально) быстрый init памяти — сейчас детерминированный паттерн,
    // потом заменим на загрузку реальных весов / из файла.
    initial begin : init_weights_mem
        for (int k = 0; k < WEIGHTS_MEM_WORDS; k++) begin
            // пример: lower16=k, upper16=k+1
            weights_mem[k] = {16'(k+1), 16'(k)};
        end
    end

    // ============================================================
    // Ingress spikes driver (task + optional file load)
    // ============================================================

    // Максимум спайков, которые мы готовы загрузить из файла в буфер TB
    parameter int SPIKES_MAX = 4096;

    logic [SPIKE_WIDTH-1:0] spikes_buf [0:SPIKES_MAX-1];
    int spikes_buf_len;

    // Простая запись одного слова в ingress FIFO (ждёт full=0, даёт импульс we на 1 такт)
    task automatic fifo_in_push(input logic [SPIKE_WIDTH-1:0] spike);
    begin
        // дождаться возможности записи
        while (full_fifo_in) @(posedge clk);

        @(posedge clk);
        wr_fifo_in      <= 1'b1;
        wr_data_fifo_in <= spike;

        @(posedge clk);
        wr_fifo_in      <= 1'b0;
        wr_data_fifo_in <= '0;
    end
    endtask

    // Записать N спайков из массива (спайк = индекс нейрона)
    // Icarus: не тащит "open array" для статических массивов как spikes_buf.
    // Поэтому делаем явную отправку из spikes_buf.
    task automatic fifo_in_push_buf(input int unsigned n);
    begin
        for (int unsigned j = 0; j < n; j++) begin
            fifo_in_push(spikes_buf[j]);
        end
    end
    endtask


    // Загрузить спайки из файла (по одному числу в строке, можно hex/dec)
    // Пример файла:
    //   0
    //   5
    //   12
    //   0x1A
    task automatic load_spikes_from_file(
        input string fname,
        output int unsigned n_loaded
    );
        int fd;
        int r;
        int unsigned idx;
        logic [SPIKE_WIDTH-1:0] v;
    begin
        n_loaded = 0;
        fd = $fopen(fname, "r");
        if (fd == 0) begin
            $fatal(1, "Cannot open spikes file: %s", fname);
        end

        idx = 0;
        // %i читает и dec, и 0x...
        while (!$feof(fd) && (idx < SPIKES_MAX)) begin
            r = $fscanf(fd, "%i\n", v);
            if (r == 1) begin
                spikes_buf[idx] = v;
                idx++;
            end
        end
        $fclose(fd);

        spikes_buf_len = idx;
        n_loaded = idx;

        $display("[TB] loaded %0d spikes from %s", n_loaded, fname);
    end
    endtask

    // Записать в FIFO все загруженные из файла спайки
    task automatic fifo_in_push_loaded;
    begin
        fifo_in_push_buf(spikes_buf_len);
    end
    endtask

    // ============================================================
    // Egress (output FIFO) reader: start on emission_done
    // ============================================================

    // куда складываем считанные выходные спайки (по желанию)
    parameter int OUT_MAX = 4096;
    logic [SPIKE_WIDTH-1:0] out_buf [0:OUT_MAX-1];
    int out_cnt;

    // прочитать ОДИН элемент из выходного FIFO (если есть)
    // Важно: rd_fifo_out держим 1 такт, данные берем на следующем такте.
    task automatic fifo_out_pop_one(output logic [SPIKE_WIDTH-1:0] spike);
    begin
        while (empty_fifo_out) @(posedge clk);

        @(posedge clk);
        rd_fifo_out <= 1'b1;

        @(posedge clk);
        rd_fifo_out <= 1'b0;

        // взять данные на следующем такте после чтения
        @(posedge clk);
        spike = rd_data_fifo_out;
    end
    endtask

    // прочитать все элементы, пока FIFO не опустеет (с safety limit)
    task automatic fifo_out_drain(output int unsigned n_read);
        logic [SPIKE_WIDTH-1:0] s;
        int unsigned k;
    begin
        n_read = 0;
        k = 0;

        // читаем пока не пусто, но не больше OUT_MAX (защита)
        while (!empty_fifo_out && (k < OUT_MAX)) begin
            fifo_out_pop_one(s);
            out_buf[k] = s;
            $display("[TB] OUT[%0d] = 0x%08x (%0d) @t=%0t", k, s, s, $time);
            k++;
        end

        n_read = k;
        out_cnt = k;
    end
    endtask

    // Автоматический дренаж после завершения обработки
    initial begin : auto_read_out_on_done
        int unsigned nread;

        // дождаться выхода из ресета
        wait (!rst);

        // ждать фронт done (если он уже 1 - сначала дождемся падения, потом фронта)
        if (emission_done === 1'b1) @(negedge emission_done);

        fork
            begin : wait_done
                @(posedge emission_done);
                @(posedge clk); // 1 такт паузы для "последней записи" в fifo_out
                $display("[TB] emission_done posedge -> start draining output FIFO @t=%0t", $time);
                fifo_out_drain(nread);
                $display("[TB] output FIFO drained: n_read=%0d @t=%0t", nread, $time);
            end
            begin : timeout
                // таймаут, чтобы TB не висел вечно
                repeat (50000) @(posedge clk); // подстрой по нужде
                $fatal(1, "[TB] TIMEOUT waiting emission_done @t=%0t", $time);
            end
        join_any
        disable fork;
    end

    initial begin
        // Инициализация сигналов
        rst = 1;
        en_lif = 0;
        en_tick_manual = 0;
        rst_tick_manual = 1;
        wr_fifo_in = 0;
        wr_data_fifo_in = 0;
        rd_fifo_out = 0;
        // bram_addr_weights = 0;
        // bram_en_weights = 0;
        // bram_we_weights = 0;
        wr_leakage = 0;
        wd_leakage = 0;
        wr_threshold = 0;
        wd_threshold = 0;
        wr_vreset = 0;
        wd_vreset = 0;
        wr_weight_base = 0;
        wd_weight_base = 0;
        wr_neuron_base = 0;
        wd_neuron_base = 0;
        wr_postsyn_count = 0;
        wd_postsyn_count = 0;
        wr_emit_tag = 0;
        wd_emit_tag = 0;

        // Сброс
        #20 rst = 0;

        // дать тик-гену старт: короткий reset + enable
        rst_tick_manual <= 1'b1;
        en_tick_manual  <= 1'b0;
        repeat (2) @(posedge clk);
        rst_tick_manual <= 1'b0;
        en_tick_manual  <= 1'b1;

        write_reg(wr_leakage,       wd_leakage,       1);
        write_reg(wr_threshold,     wd_threshold,     2);
        write_reg(wr_vreset,        wd_vreset,        3);
        write_reg(wr_weight_base,   wd_weight_base,   0);
        write_reg(wr_neuron_base,   wd_neuron_base,   0);   
        write_reg(wr_postsyn_count, wd_postsyn_count, 16);  
        write_reg(wr_emit_tag,      wd_emit_tag,      0);   

        @(posedge clk);
        en_lif <= 1'b1;

        // int unsigned nspk;
        // load_spikes_from_file("spikes.txt", nspk); // или пропусти и заполни spikes_buf вручную
        // fifo_in_push_loaded();
        // или без файла:
        for (i=0; i<16; i++) fifo_in_push(i);

        // for (i = 0; i < 10; i++) begin
        //     // ждём пока можно писать
        //     while (full_fifo_in) @(posedge clk);

        //     @(posedge clk);
        //     wr_fifo_in      <= 1'b1;
        //     wr_data_fifo_in <= i;

        //     @(posedge clk);
        //     wr_fifo_in <= 1'b0;
        // end

        // rd_fifo_out <= 1'b1;

        // Конфиг ядра
        // #10 en_lif = 1;



        // Здесь можно добавить дополнительные тестовые сценарии
        #200000;
        $finish;

    end


endmodule
