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
    // Для новой версии ядра делаем простую "витрину" регистров:
    //   0 : управляющие биты (en_tick_manual, rst_tick_manual, rd/wr фифо и т.п.)
    //   1 : wr_data_fifo_in
    //   2 : addr_weights_0
    //   3 : addr_state_0
    //   4 : waddr_state
    //   5 : wdata_state
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_BASE      = 'd0;
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_CTRL      = CFG_BASE + (0<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_FIFO_DATA = CFG_BASE + (1<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_WADDR     = CFG_BASE + (2<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_SADDR     = CFG_BASE + (3<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_WSTATE    = CFG_BASE + (4<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_WSDATA    = CFG_BASE + (5<<2);

    // ----------------------------------------------------------------
    // Интерфейс новой версии bnmm_manual_demo
    // ----------------------------------------------------------------
    logic                 en_tick_manual;
    logic                 rst_tick_manual;
    logic                 wr_fifo_in;
    logic [15:0]          wr_data_fifo_in;
    logic                 full_fifo_in;
    logic                 rd_fifo_out;
    logic [7:0]           rd_data_fifo_out;
    logic                 empty_fifo_out;
    logic                 rd_weights_0;
    logic [3:0]           addr_weights_0;
    logic [15:0]          data_weights_0;
    logic                 rd_state_0;
    logic [3:0]           addr_state_0;
    logic [11:0]          data_state_0;
    logic                 we_state;
    logic [3:0]           waddr_state;
    logic [11:0]          wdata_state;

    // счётчик для обхода конфигурационных слов в BRAM
    logic [ADDR_WIDTH_WORD-1:0] bram_addr_next;

// =================================================================
// ИНСТАНС ЯДРА bnmm_manual_demo (новая версия портов)
// =================================================================
bnmm_manual_demo u_lif_core (
    .clk_i            (clk),
    .rst_i            (rst),
    .en_tick_manual   (en_tick_manual),
    .rst_tick_manual  (rst_tick_manual),
    .wr_fifo_in       (wr_fifo_in),
    .wr_data_fifo_in  (wr_data_fifo_in),
    .full_fifo_in     (full_fifo_in),
    .rd_fifo_out      (rd_fifo_out),
    .rd_data_fifo_out (rd_data_fifo_out),
    .empty_fifo_out   (empty_fifo_out),
    .rd_weights_0     (rd_weights_0),
    .addr_weights_0   (addr_weights_0),
    .data_weights_0   (data_weights_0),
    .rd_state_0       (rd_state_0),
    .addr_state_0     (addr_state_0),
    .data_state_0     (data_state_0),
    .we_state         (we_state),
    .waddr_state      (waddr_state),
    .wdata_state      (wdata_state)
);


    // =================================================================
    // FSM: последовательность работы и управление BRAM
    // =================================================================

    always_ff @(posedge clk) begin
        if (rst) begin
            bram_addr_word   <= '0;
            bram_en          <= 1'b1;
            bram_we          <= '0;
            bram_din         <= '0;

            en_tick_manual   <= 1'b0;
            rst_tick_manual  <= 1'b0;
            wr_fifo_in       <= 1'b0;
            wr_data_fifo_in  <= '0;
            rd_fifo_out      <= 1'b0;
            rd_weights_0     <= 1'b0;
            addr_weights_0   <= '0;
            rd_state_0       <= 1'b0;
            addr_state_0     <= '0;
            we_state         <= 1'b0;
            waddr_state      <= '0;
            wdata_state      <= '0;

            last_read_data   <= '0;
        end else begin
            // циклично обходим зарезервированные адреса и обновляем регистры ядра
            bram_addr_word <= bram_addr_next;

            unique case (bram_addr_word)
                CFG_CTRL: begin
                    en_tick_manual  <= bram_dout[0];
                    rst_tick_manual <= bram_dout[1];
                    wr_fifo_in      <= bram_dout[2];
                    rd_fifo_out     <= bram_dout[3];
                    rd_weights_0    <= bram_dout[4];
                    rd_state_0      <= bram_dout[5];
                    we_state        <= bram_dout[6];
                end
                CFG_FIFO_DATA: begin
                    wr_data_fifo_in <= bram_dout[15:0];
                end
                CFG_WADDR: begin
                    addr_weights_0 <= bram_dout[3:0];
                end
                CFG_SADDR: begin
                    addr_state_0 <= bram_dout[3:0];
                end
                CFG_WSTATE: begin
                    waddr_state <= bram_dout[3:0];
                end
                CFG_WSDATA: begin
                    wdata_state <= bram_dout[11:0];
                end
                default: /* none */;
            endcase

            // для отладки выводим свежие данные из ядра в last_read_data
            last_read_data <= {data_weights_0, 4'b0, rd_data_fifo_out, 2'b0, full_fifo_in, empty_fifo_out};
        end
    end

    // Комбинционная часть: цикл по адресам конфигурации BRAM
    always_comb begin
        bram_en        = 1'b1;          // постоянно читаем BRAM
        bram_we        = '0;            // запись в BRAM пока не используем
        bram_din       = '0;

        if (bram_addr_word >= CFG_WSDATA)
            bram_addr_next = CFG_BASE;  // вернуться к первому слову
        else
            bram_addr_next = bram_addr_word + (1 << 2);
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
