module wrapper #(
    parameter ADDR_WIDTH_WORD = 13,        
    parameter DATA_WIDTH      = 32,
    parameter WE_WIDTH        = DATA_WIDTH/8
) (
    input  logic                       clk,
    input  logic                       rst,

    // Порт к BRAM (порт B) - адрес в словах
    output logic [ADDR_WIDTH_WORD-1:0] bram_addr_word,
    output logic                       bram_en,
    output logic [WE_WIDTH-1:0]        bram_we,
    output logic [DATA_WIDTH-1:0]      bram_din,
    input  logic [DATA_WIDTH-1:0]      bram_dout,

    // просто чтобы куда-то вывести данные (на ILA/LED и т.п.)
    output logic [DATA_WIDTH-1:0]      last_read_data
);

//    // ============================================================
//    // Логика чтения порта B: адрес 0..1000 по кругу
//    // ============================================================
//    always_ff @(posedge clk) begin
//        if (rst) begin
//            bram_addr_word <= '0;
//            last_read_data <= '0;
//        end else begin
//            // сохраняем то, что прочитали из BRAM
//            last_read_data <= bram_dout;

//            // адресный счётчик: 0..1000, потом снова 0
//            if (bram_addr_word < ADDR_WIDTH_WORD'(13'd1000))
//                bram_addr_word <= bram_addr_word + 1'b1;
//            else
//                bram_addr_word <= '0;
//        end
//    end

//    // Только чтение
//    assign bram_en  = 1'b1;
//    assign bram_we  = '0;             
//    assign bram_din = '0;         

    // ----------------------------------------------------------------
    // КАРТА ПАМЯТИ BRAM (в словах, word address)
    // ----------------------------------------------------------------
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_BASE         = 'd0;
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_EN_CORE_ADDR = CFG_BASE + 0;
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_LEAKAGE_ADDR = CFG_BASE + (1<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_VRST_ADDR    = CFG_BASE + (2<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_VTHRSH_ADDR  = CFG_BASE + (3<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_PRESYN_ADDR  = CFG_BASE + (4<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_POSTSYN_ADDR = CFG_BASE + (5<<2);
    localparam int                         CFG_WORDS        = 6;

    // область весов
    localparam logic [ADDR_WIDTH_WORD-1:0] WEIGHTS_BASE  = CFG_BASE + (10<<2);

    localparam logic [ADDR_WIDTH_WORD-1:0] PRESYN_NUMS         = 'd16;
    localparam logic [ADDR_WIDTH_WORD-1:0] POSTSYN_NUMS        = 'd16;

    localparam WEIGHTS_MEM_SIZE           = PRESYN_NUMS * POSTSYN_NUMS;

    // область входящих спайков
    localparam logic [ADDR_WIDTH_WORD-1:0] IN_SPIKES_BASE = WEIGHTS_BASE + ( WEIGHTS_MEM_SIZE<<2);
    // [IN_SPIKES_BASE + 1 ..] : данные спайков (по одному слову на спайк)

    localparam logic [ADDR_WIDTH_WORD-1:0] IN_SPIKES_COUNT = IN_SPIKES_BASE;
    // [IN_SPIKES_BASE + 1 ..] : данные спайков (по одному слову на спайк)

    // область исходящих спайков
    localparam logic [ADDR_WIDTH_WORD-1:0] OUT_SPIKES_BASE  = IN_SPIKES_BASE + (PRESYN_NUMS<<2); //'d2048;

    // ----------------------------------------------------------------
    // РЕГИСТРЫ КОНФИГА ДЛЯ ЯДРА
    // ----------------------------------------------------------------
    logic        en_cu;
    logic        en_core;
    logic [7:0]  leakage_reg;
    logic [7:0]  Vrst_reg;
    logic [7:0]  Vthrsh_reg;
    logic [9:0]  presyn_neurons_reg;
    logic [9:0]  postsyn_neurons_reg;

    // ----------------------------------------------------------------
    // Интерфейс к lif_core
    // ----------------------------------------------------------------
    logic        tick_o;
    logic [16:0] adr_ram_i_l1;        // адрес веса от ядра
    logic [15:0] dat_ram_o_l1;        // вес, поданный ядру

    logic        wr_input_queue;
    logic [9:0]  wr_data_input_queue;
    logic        full_input_queue;

    logic        rd_p_output_queue;
    logic [9:0]  rd_data_p_output_queue;
    logic        empty_p_output_queue;

    // регистр для веса (учитываем латентность BRAM)
    logic [15:0] weight_reg;

    logic       START;

    logic spk_cnt_set;
    // ----------------------------------------------------------------
    // Состояния враппера: сначала грузим конфиг, потом запускаем ядро
    // ----------------------------------------------------------------
    typedef enum logic [6:0] {
        ST_IDLE,
        ST_CFG_LOAD,    // читаем CFG_WORDS из BRAM и заполняем регистры
        ST_SET_IN_QUEUE_CNT,
        ST_WR_INPUT_QUEUE,
        ST_RD_INPUT_QUEUE,
        ST_RUN          // нормальная работа ядра
    } state_t;

    state_t state, state_next;

    // индекс при загрузке конфигурации
    // logic [$clog2(CFG_WORDS)-1:0] 
    logic [7:0] cfg_idx;

    logic [9:0] spikes_num, spk_cnt;

    // внутренние сигналы управления BRAM
    logic [ADDR_WIDTH_WORD-1:0] bram_addr_next;
    logic                       bram_en_next;
    logic [WE_WIDTH-1:0]        bram_we_next;
    logic [DATA_WIDTH-1:0]      bram_din_next;

// =================================================================
// ИНСТАНС ЯДРА lif_core
// =================================================================
bnmm_manual_demo u_lif_core (
    .clk_i                  (clk),
    .rst_i                  (rst),
    .en_core                (en_cu),               // включаем после CFG_LOAD

    .tick_o                 (tick_o),

    .adr_ram_i_l1           (adr_ram_i_l1),
    .dat_ram_o_l1           (dat_ram_o_l1),

    .wr_input_queue         (wr_input_queue),
    .wr_data_input_queue    (wr_data_input_queue),
    .full_input_queue       (full_input_queue),

    .rd_p_output_queue      (rd_p_output_queue),
    .rd_data_p_output_queue (rd_data_p_output_queue),
    .empty_p_output_queue   (empty_p_output_queue),

    // --- дополнительные конфигурационные входы ---
    .leakage                (leakage_reg),
    .Vrst                   (Vrst_reg),
    .Vthrsh                 (Vthrsh_reg),
    .presyn_neurons         (presyn_neurons_reg),
    .postsyn_neurons        (postsyn_neurons_reg)
);

    // подаём ядру вес с учётом латентности (регистром)
    assign dat_ram_o_l1 = weight_reg;


    // =================================================================
    // FSM: последовательность работы и управление BRAM
    // =================================================================

    always_ff @(posedge clk) begin
        if (rst) begin
            state             <= ST_IDLE;
            cfg_idx           <= '0;

            en_cu       <= 1'b0;
            leakage_reg       <= '0;
            Vrst_reg          <= '0;
            Vthrsh_reg        <= '0;
            presyn_neurons_reg<= '0;
            postsyn_neurons_reg<= '0;

            weight_reg        <= '0;
            last_read_data    <= '0;

            bram_addr_word    <= '0;
            bram_en           <= 1'b0;
            bram_we           <= '0;
            bram_din          <= '0;
            START             <= '0;

            spikes_num        <= '0;
            spk_cnt <= '0;
        end else begin
            state          <= state_next;

            bram_addr_word <= bram_addr_next;
            bram_en        <= bram_en_next;
            bram_we        <= bram_we_next;
            bram_din       <= bram_din_next;


            if (state == ST_IDLE) begin 
                START <= bram_dout[0];
            end 

            // загрузка конфигурации по завершению чтения нужного слова
            if (state == ST_CFG_LOAD) begin
                case (cfg_idx)
                    // 0: en_core            <= bram_dout[0];
                    // 1: 
                    2: leakage_reg        <= bram_dout[7:0]; 
                    3: Vrst_reg           <= bram_dout[7:0]; 
                    4: Vthrsh_reg         <= bram_dout[7:0]; 
                    5: presyn_neurons_reg <= bram_dout[9:0]; 
                    6: postsyn_neurons_reg<= bram_dout[9:0]; 
                    default: /* nothing */;
                endcase

                if (cfg_idx != CFG_WORDS)
                    cfg_idx <= cfg_idx + 1'b1;
                else if (cfg_idx == CFG_WORDS)
                    cfg_idx <= 0; // дальше перейдём в ST_RUN
            end

            if (state == ST_SET_IN_QUEUE_CNT) begin 
                spikes_num <= bram_dout[9:0]; 
            end 

            if (state == ST_RD_INPUT_QUEUE) begin 
                // if (~full_input_queue) begin 
                    wr_input_queue <= 1;
                    wr_data_input_queue <= bram_dout[7:0];
                // end 
                /*else добавить обработку обратного давления от FIFO */ 
                if (spk_cnt != spikes_num)
                    spk_cnt <= spk_cnt + 1'b1;
                else if (spk_cnt == spikes_num) begin 
                    spk_cnt <= 0; // дальше перейдём в ST_RUN
                    wr_input_queue <= 0;
                end 
            end

            // простой пример обслуживания: читаем веса каждый такт
            // (в ST_RUN до weight_reg добираемся ниже)
            if (state == ST_RUN) begin
                // вес для ядра: предполагаем латентность 1 такт
                weight_reg <= bram_dout[15:0];
            end


        end
    end

    // Комбинционная часть: формирование bram_* и следующего состояния
    always_comb begin
        // значения по умолчанию
        state_next    = state;

        bram_addr_next = bram_addr_word;
        bram_en_next  = 1'b1;          // всегда активен
        bram_we_next  = '0;            // по умолчанию только чтение
        bram_din_next = '0;

        // по умолчанию: не трогаем очереди спайков
        // wr_input_queue      = 1'b0;
        // wr_data_input_queue = '0;

        rd_p_output_queue   = 1'b0;

        case (state)
            // --------------------------------------------------------
            // ST_IDLE: Ожидаение команды Старт
            // --------------------------------------------------------
            ST_IDLE: begin 
                // bram_addr_next = CFG_EN_CORE_ADDR;
                // bram_addr_next = CFG_BASE + cfg_idx;
                if (START) begin 
                    bram_addr_next = CFG_BASE + cfg_idx;
                    state_next  = ST_CFG_LOAD;
                end 
            end 
            // --------------------------------------------------------
            // ST_CFG_LOAD: читаем CFG_WORDS слов по адресам 0..5
            // --------------------------------------------------------
            ST_CFG_LOAD: begin
                bram_addr_next = CFG_BASE + (cfg_idx<<2);
                // как только дочитали все 6 слов — включаем ядро
                if (cfg_idx == CFG_WORDS) begin
                    $monitor("Configuration loaded: en_core=%0b, leakage=%0d, Vrst=%0d, Vthrsh=%0d, presyn=%0d, postsyn=%0d",
                             en_cu, leakage_reg, Vrst_reg, Vthrsh_reg,
                             presyn_neurons_reg, postsyn_neurons_reg);

                    state_next  = ST_SET_IN_QUEUE_CNT; // ST_RUN;
                    // en_cu уже загружен из памяти, начинаем работу
                end
            end

            // --------------------------------------------------------
            // ST_SET_IN_QUEUE_CNT
            // --------------------------------------------------------
            ST_SET_IN_QUEUE_CNT: begin 
                bram_addr_next = IN_SPIKES_COUNT;
                if (spikes_num != 0) begin 
                    $monitor("Soikes cnt loaded: spikes_num=%0b",
                             spikes_num);
                    state_next = ST_RD_INPUT_QUEUE;
                end 
            end 

            // --------------------------------------------------------
            // ST_RD_INPUT_QUEUE
            // --------------------------------------------------------
            ST_RD_INPUT_QUEUE: begin 
                bram_addr_next = IN_SPIKES_BASE +  ((1 + spk_cnt) << 2);
                if (spk_cnt == spikes_num) begin 
                    $monitor("Spikes was loaded: spikes_num=%0b",
                             spk_cnt);
                    state_next  = ST_RUN;
                end
            end 


            // --------------------------------------------------------
            // ST_RUN: нормальная работа ядра
            // --------------------------------------------------------
            ST_RUN: begin
                // 1) Обслуживание запросов к памяти весов:
                //    адрес ядра adr_ram_i_l1 → смещаем на WEIGHTS_BASE
                bram_addr_next = WEIGHTS_BASE + adr_ram_i_l1[ADDR_WIDTH_WORD-1:0];
                // bram_we_next  = 0; чтение веса
                // weight_reg обновится в секвенциальной части

                // 2) ЧТЕНИЕ ВХОДЯЩИХ СПАЙКОВ И ЗАПИСЬ В ВХОДНУЮ ОЧЕРЕДЬ ЯДРА
                // ---------------------------------------------------------
                // Тут нужен дополнительный управляющий автомат, который:
                //  - читает IN_SPIKES_COUNT из IN_SPIKES_BASE
                //  - затем по одному слову из IN_SPIKES_BASE+1..N
                //  - при !full_input_queue делает wr_input_queue=1,
                //    wr_data_input_queue <= bram_dout[9:0]
                //
                // Для простоты в этом скелете оставляем TODO:
                // TODO: добавить FSM для чтения входных спайков из BRAM
                //       и записи их в входной буфер ядра.

                // 3) ЗАПИСЬ ИСХОДЯЩИХ СПАЙКОВ В BRAM
                // ---------------------------------------------------------
                // Аналогично, нужен FSM, который:
                //  - при пустом/непустом empty_p_output_queue
                //    выставляет rd_p_output_queue=1, считывает rd_data_p_output_queue
                //  - формирует адрес в OUT_SPIKES_BASE + индекс
                //  - делает bram_we_next = 4'b1111 и записывает спайк
                //
                // TODO: добавить FSM для записи исходящих спайков в BRAM.
            end

            default: begin
                state_next = ST_CFG_LOAD;
            end
        endcase
    end

endmodule


// инстанцированый CU
// - чтением из bram-записью в во входящий фифо
// - чтением из исходящего фифо- записью в bram 
// - переходником из интерфейса чтения из памяти весов в область BRAM с весами 
// - переходники установки параметров:  
//     - количество пре и постсинаптических нейронов 
//     - потенциал утечки, сброса, порога
// - переходники старта и стопа 
// - переходник прокидывающий сигнал тика 
    // твоя логика доступа к памяти
