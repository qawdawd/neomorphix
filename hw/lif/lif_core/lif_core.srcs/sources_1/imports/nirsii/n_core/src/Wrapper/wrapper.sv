// Wrapper that bridges external BRAM with the generated bnmm_manual_demo core.
// The FSM fetches configuration words from
// BRAM, writes them into the core's register adapter, streams input spikes from
// BRAM into the ingress FIFO, enables tick generation to let the core process
// events, and captures output spikes back into BRAM.
module wrapper #(
    parameter ADDR_WIDTH_WORD = 32,
    parameter DATA_WIDTH      = 32,
    parameter PRESYN_NUM      = 16,
    parameter POSTSYN_NUM     = 16,
    parameter CFG_WORDS       = 5,
    parameter SPIKE_WIDTH     = 32,
    
    parameter WE_WIDTH        = DATA_WIDTH/8
) (
    input  logic                       clk,
    input  logic                       rst,

    // BRAM port (word address)
    output logic [ADDR_WIDTH_WORD-1:0] bram_addr_word,
    output logic                       bram_en,
    output logic [WE_WIDTH-1:0]        bram_we,
    output logic [DATA_WIDTH-1:0]      bram_din,
    input  logic [DATA_WIDTH-1:0]      bram_dout,

    // debug tap
    output logic [DATA_WIDTH-1:0]      last_read_data
);

    // ----------------------------------------------------------------
    // BRAM map (WORD ADDRESSES, not byte addresses)
    // ----------------------------------------------------------------
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_BASE             = 'd0;
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_EN_CORE_ADDR     = CFG_BASE + 0;
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_LEAKAGE_ADDR     = CFG_BASE + (1<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_VRST_ADDR        = CFG_BASE + (2<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_VTHRSH_ADDR      = CFG_BASE + (3<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_POSTSYN_NEUR_CNT_ADDR  = CFG_BASE + (4<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_WEIGHT_BASE_ADDR = CFG_BASE + (5<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_NEURON_BASE_ADDR = CFG_BASE + (6<<2);
    
    // localparam int CFG_WORDS   = 8;

    localparam logic [ADDR_WIDTH_WORD-1:0] 
            WEIGHTS_BASE  = CFG_BASE + (CFG_WORDS<<2);

    // localparam int PRESYN_NUM = 16;
    // localparam int POSTSYN_NUM = 16;
    localparam int WEIGHTS_PER_WORD = 2;
    localparam int WEIGHT_WORDS = (PRESYN_NUM * POSTSYN_NUM) / WEIGHTS_PER_WORD;

    localparam logic [ADDR_WIDTH_WORD-1:0] 
            IN_SPIKES_BASE = WEIGHTS_BASE + (WEIGHT_WORDS << 2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            IN_SPIKES_COUNT = IN_SPIKES_BASE;

    localparam logic [ADDR_WIDTH_WORD-1:0] 
            IN_SPIKES = IN_SPIKES_COUNT + (1<<2);

    localparam int SPIKES_PER_WORD = 1; // 2;
    localparam int IN_SPIKE_WORDS = PRESYN_NUM / SPIKES_PER_WORD;

    localparam logic [ADDR_WIDTH_WORD-1:0] 
            OUT_SPIKES_BASE = IN_SPIKES + (IN_SPIKE_WORDS<<2);
    
    // ----------------------------------------------------------------
    // Generated core instance
    // ----------------------------------------------------------------
    logic                   en_tick_manual;
    logic                   rst_tick_manual;
    logic                   tick_o_tick_manual;
    
    logic                   wr_fifo_in;
    logic [SPIKE_WIDTH-1:0] wr_data_fifo_in;
    logic                   full_fifo_in;
    
    logic                   rd_fifo_out;
    logic [SPIKE_WIDTH-1:0] rd_data_fifo_out;
    logic                   empty_fifo_out;
    
    // external weight BRAM signals from the core
    logic                   bram_en_weights;
    logic [1:0]             bram_we_weights;
    logic [ADDR_WIDTH_WORD-1:0] bram_addr_weights;
    logic [DATA_WIDTH-1:0]      bram_dout_weights;
    
    logic [ADDR_WIDTH_WORD-1:0] bram_addr_word_next;
    logic       bram_en_next;
    logic [1:0] bram_we_next;

    
    // register adapter strobes
    logic                   wr_leakage;
    logic [15:0]            wd_leakage;
    
    logic                   wr_threshold;
    logic [15:0]            wd_threshold;
    
    logic                   wr_vreset;
    logic [15:0]            wd_vreset;
    
    logic                   wr_weight_base;
    logic [15:0]            wd_weight_base;
    
    logic                   wr_neuron_base;
    logic [15:0]            wd_neuron_base;
    
    logic                   wr_postsyn_count;
    logic [15:0]            wd_postsyn_count;
    
    logic                   wr_emit_tag;
    logic [15:0]            wd_emit_tag;

    logic                   en_lif;
    
    
    logic [7:0] cfg_idx;
    logic [9:0] spk_cnt;
    logic [9:0] out_cnt;

    logic [9:0] spikes_num;
    
//    (* dont_touch = "true" *)
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
        .wd_emit_tag      (wd_emit_tag)
    );

    
    logic [DATA_WIDTH-1:0] bram_dout_q;

    // Provide the core with the BRAM weight data (registered for timing
    // alignment with bram_dout_q).
    assign bram_dout_weights = bram_dout_q;
    
    always_ff @(posedge clk) begin
        bram_dout_q <= bram_dout;  // всегда задерживаем на 1 такт
    end
    

    always_ff @( posedge clk) begin
        bram_addr_word <= bram_addr_word_next;
        bram_en <= bram_en_next;
    end

    // ----------------------------------------------------------------
    // FSM bookkeeping
    // ----------------------------------------------------------------
    typedef enum logic [2:0] {
        ST_IDLE,
        ST_CFG_LOAD,
        ST_SPK_LOAD, 
        ST_RUN,
        ST_OUT_RD
    } state_t;

    state_t state, state_next;
    
    logic [15:0] idx;

    always_ff @(posedge clk) begin
        if (rst) begin
            state <= ST_IDLE;
    
            en_tick_manual <= 1'b0;
            rst_tick_manual   <= 1'b1;
    
            cfg_idx <= '0;
            spk_cnt <= '0;
            out_cnt <= '0;

            idx <= '0;

            en_lif <= 0;
    
        end else begin
            state <= state_next;
    
            // tick: активен только в ST_RUN
            
            rst_tick_manual   <= 1'b0;

            unique case (state)
    
                // -----------------------------------------------------
                ST_CFG_LOAD:
                // -----------------------------------------------------
                    if (state_next == ST_CFG_LOAD)begin  
                        if (cfg_idx == CFG_WORDS + 1)  
                            spikes_num <= bram_dout[9:0];
                         
                        cfg_idx <= cfg_idx + 1;
                    end else
                        cfg_idx <= '0;
                 
    
                // -----------------------------------------------------
                ST_SPK_LOAD:
                // -----------------------------------------------------
                begin
                    cfg_idx <= (state_next != ST_CFG_LOAD) ? '0 : cfg_idx;
    
                    // if (wr_fifo_in && !full_fifo_in)
                    //     spk_cnt <= spk_cnt + 1;
                    // else if (state_next != ST_SPK_LOAD)
                    //     spk_cnt <= '0;

                    if (state_next == ST_SPK_LOAD && (spk_cnt <= spikes_num))
                        spk_cnt <= spk_cnt + 1;
                    else if (state_next != ST_SPK_LOAD)
                        spk_cnt <= '0;
                end
    
                // -----------------------------------------------------
                ST_RUN:
                // -----------------------------------------------------
                begin
                    // en_tick_manual <= 1; // (state_next == ST_RUN);

                    en_lif <= 1;

                    cfg_idx <= (state_next != ST_CFG_LOAD) ? '0 : cfg_idx;
                    spk_cnt <= (state_next != ST_SPK_LOAD) ? '0 : spk_cnt;
    
                    // if (rd_fifo_out && !empty_fifo_out)
                    //     out_cnt <= out_cnt + 1;
                    // else if (state_next != ST_RUN)
                    //     out_cnt <= '0;
                end
    
                // -----------------------------------------------------
                default:
                // Covers ST_IDLE, ST_OUT_RD, any future states
                // -----------------------------------------------------
                begin
                    cfg_idx <= (state_next == ST_CFG_LOAD) ? cfg_idx : '0;
                    spk_cnt <= (state_next == ST_SPK_LOAD) ? spk_cnt : '0;
                    out_cnt <= (state_next == ST_RUN)      ? out_cnt : '0;
                end
    
            endcase
        end
    end
    
        
        
    always_comb begin
        state_next = state;
    
        // default BRAM FSM control
        bram_addr_word_next = 0; //bram_addr_weights;
        bram_en_next   = 1'b1;
        bram_we_next   = 2'b00;

        bram_din        = 0;
        bram_we         = 0;
    
        // core-input defaults
        wr_fifo_in      = 1'b0;
        wr_data_fifo_in = '0;
        rd_fifo_out     = 1'b0;
    
        wr_leakage       = 1'b0;
        wr_threshold     = 1'b0;
        wr_vreset        = 1'b0;

        wr_neuron_base   = 1'b0;
        wr_postsyn_count = 1'b0;
        wr_emit_tag      = 1'b0;
    
        wd_leakage       = '0;
        wd_threshold     = '0;
        wd_vreset        = '0;
        wd_neuron_base   = '0;
        // wd_postsyn_count = '0;
        wd_emit_tag      = '0;

        wr_weight_base   = 1'b1;
        wd_weight_base   = WEIGHTS_BASE;// * 2; // в количетсве весов, а не слов. Нужно будет селектор поправить 


    
        // ---------------------------------------------------------
        case (state)
    
            //------------------------------------------------------
            ST_IDLE:
            //------------------------------------------------------
            begin
                bram_addr_word_next = CFG_EN_CORE_ADDR;
    
                if (bram_dout[0])
                    state_next = ST_CFG_LOAD;
            end
    
            //------------------------------------------------------
            ST_CFG_LOAD:
            //------------------------------------------------------
            begin
                case (cfg_idx)
                    0: begin
                        bram_addr_word_next = CFG_LEAKAGE_ADDR;

                    end
                    1: begin
                        bram_addr_word_next = CFG_VRST_ADDR;
                        // wr_leakage = 1'b1;
                        // wd_leakage = bram_dout_q; // bram_dout;

                    end
                    2: begin
                        bram_addr_word_next = CFG_VTHRSH_ADDR;
                                                wr_leakage = 1'b1;
                        wd_leakage = bram_dout_q; // bram_dout;

                        // wr_vreset = 1'b1;
                        // wd_vreset = bram_dout_q; //bram_dout;


                    end
                    3: begin
                        bram_addr_word_next = CFG_POSTSYN_NEUR_CNT_ADDR;
                                                wr_vreset = 1'b1;
                        wd_vreset = bram_dout_q; //bram_dout;


                        // wr_threshold = 1'b1;
                        // wd_threshold = bram_dout_q; // bram_dout;


                    end
                    4: begin
                        bram_addr_word_next = IN_SPIKES_COUNT;

                        wr_threshold = 1'b1;
                        wd_threshold = bram_dout_q; // bram_dout;

                        // wr_postsyn_count = 1'b1;
                        // wd_postsyn_count = bram_dout_q; // bram_dout;
                    end

                    5: begin
                        wr_postsyn_count = 1'b1;
                        wd_postsyn_count = bram_dout_q; // bram_dout;
                    //     bram_addr_word_next = IN_SPIKES_COUNT;
                    end
    
                    default: begin 
                        spikes_num = bram_dout_q; // bram_dout;
                        
                        bram_addr_word_next = IN_SPIKES;

                        state_next = ST_SPK_LOAD;
                    end 
                endcase
                if (cfg_idx == CFG_WORDS + 1) state_next = ST_SPK_LOAD;
            end
    
            //------------------------------------------------------
            ST_SPK_LOAD:
            //------------------------------------------------------
            begin
                // spikes_num = bram_dout_q; // bram_dout;
    
                bram_addr_word_next = IN_SPIKES + ((spk_cnt+1) << 2);

                if (spk_cnt > 0) begin 
                    if (!full_fifo_in) begin
                        wr_fifo_in      = 1'b1;
                        wr_data_fifo_in = bram_dout_q;
                    end 
                    if (spk_cnt > spikes_num) begin
                        en_tick_manual = 1; // (state_next == ST_RUN);
                        wr_fifo_in      = 1'b0;
                        if (tick_o_tick_manual == 1)
                            state_next = ST_RUN;
                    end
                end 
            end
    
            //------------------------------------------------------
            ST_RUN:
            //------------------------------------------------------
            begin
                // ядро генерирует bram_addr_weights, bram_en, bram_we
                // мы просто передаем их дальше
                bram_addr_word_next = bram_addr_weights;
                bram_en_next   = bram_en_weights;
                bram_we_next   = bram_we_weights;
    
                if (!empty_fifo_out)
                    state_next = ST_OUT_RD;
            end
    
            //------------------------------------------------------
            ST_OUT_RD:
            //------------------------------------------------------
            begin
                rd_fifo_out = 1'b1;
    
                bram_addr_word_next = OUT_SPIKES_BASE + (out_cnt << 1);
                bram_we_next   = 2'b11;
                // bram_din - у тебя нет входа, значит ядро не пишет
                // Если надо писать - добавь bram_din_weights
    
                state_next = ST_RUN;
            end
    
        endcase
    end



//    logic [7:0] cfg_idx;
//    logic [9:0] spikes_num;
//    logic [9:0] spk_cnt;
//    logic [9:0] out_cnt;

//    logic [ADDR_WIDTH_WORD-1:0] bram_addr_next;
//    logic                       bram_en_next;
//    logic [WE_WIDTH-1:0]        bram_we_next;
//    logic [DATA_WIDTH-1:0]      bram_din_next;

//    // ----------------------------------------------------------------
//    // Sequential logic
//    // ----------------------------------------------------------------
//    always_ff @(posedge clk) begin
//        if (rst) begin
//            state             <= ST_IDLE;
//            cfg_idx           <= '0;
//            spikes_num        <= '0;
//            spk_cnt           <= '0;
//            out_cnt           <= '0;

//            bram_addr_word    <= '0;
//            bram_en           <= 1'b0;
//            bram_we           <= '0;
//            bram_din          <= '0;
//            last_read_data    <= '0;

//            wr_fifo_in        <= 1'b0;
//            rd_fifo_out       <= 1'b0;

//            en_tick_manual    <= 1'b0;
//            rst_tick_manual   <= 1'b1;
//        end else begin
//            state          <= state_next;
//            bram_addr_word <= bram_addr_next;
//            bram_en        <= bram_en_next;
//            bram_we        <= bram_we_next;
//            bram_din       <= bram_din_next;
//            last_read_data <= bram_dout;

//            rst_tick_manual  <= 1'b0;
//            en_tick_manual   <= (state_next == ST_RUN);

//            if (state == ST_CFG_LOAD)
//                cfg_idx <= cfg_idx + 1'b1;
//            else if (state_next == ST_CFG_LOAD && state != ST_CFG_LOAD)
//                cfg_idx <= 0;

//            if (state == ST_IN_LOAD && wr_fifo_in && !full_fifo_in)
//                spk_cnt <= spk_cnt + 1'b1;
//            else if (state_next != ST_IN_LOAD)
//                spk_cnt <= '0;

//            if (state == ST_RUN && rd_fifo_out && !empty_fifo_out)
//                out_cnt <= out_cnt + 1'b1;
//            else if (state_next != ST_RUN)
//                out_cnt <= '0;

//            if (state == ST_IN_CNT)
//                spikes_num <= bram_dout[9:0];
//        end
//    end

//    // ----------------------------------------------------------------
//    // Combinational control
//    // ----------------------------------------------------------------
//    always_comb begin
//        state_next    = state;

//        bram_addr_next = bram_addr_word;
//        bram_en_next  = 1'b1;
//        bram_we_next  = '0;
//        bram_din_next = '0;

//        wr_leakage       = 1'b0;
//        wr_threshold     = 1'b0;
//        wr_vreset        = 1'b0;
//        wr_total_neurons = 1'b0;
//        wr_weight_base   = 1'b0;
//        wr_neuron_base   = 1'b0;
//        wr_emit_tag      = 1'b0;

//        wr_fifo_in       = 1'b0;
//        wr_data_fifo_in  = '0;
//        rd_fifo_out      = 1'b0;

//        wd_leakage        = 16'd0;
//        wd_threshold      = 16'd0;
//        wd_vreset         = 16'd0;
//        wd_total_neurons  = 16'd0;
//        wd_weight_base    = 16'd0;
//        wd_neuron_base    = 16'd0;
//        wd_emit_tag       = 16'd0;

//        case (state)
//            ST_IDLE: begin
//                bram_addr_next = CFG_EN_CORE_ADDR;
//                if (bram_dout[0])
//                    state_next = ST_CFG_LOAD;
//            end

//            ST_CFG_LOAD: begin
//                case (cfg_idx)
//                    0: begin
//                        bram_addr_next = CFG_LEAKAGE_ADDR;
//                        wr_leakage     = 1'b1;
//                        wd_leakage     = bram_dout[15:0];
//                    end
//                    1: begin
//                        bram_addr_next = CFG_VRST_ADDR;
//                        wr_vreset      = 1'b1;
//                        wd_vreset      = bram_dout[15:0];
//                    end
//                    2: begin
//                        bram_addr_next = CFG_VTHRSH_ADDR;
//                        wr_threshold   = 1'b1;
//                        wd_threshold   = bram_dout[15:0];
//                    end
//                    3: begin
//                        bram_addr_next = CFG_TOTAL_NEUR_ADDR;
//                        wr_total_neurons = 1'b1;
//                        wd_total_neurons = bram_dout[15:0];
//                    end
//                    4: begin
//                        bram_addr_next = CFG_WEIGHT_BASE_ADDR;
//                        wr_weight_base = 1'b1;
//                        wd_weight_base = bram_dout[15:0];
//                    end
//                    5: begin
//                        bram_addr_next = CFG_NEURON_BASE_ADDR;
//                        wr_neuron_base = 1'b1;
//                        wd_neuron_base = bram_dout[15:0];
//                    end
//                    6: begin
//                        bram_addr_next = CFG_EMIT_TAG_ADDR;
//                        wr_emit_tag    = 1'b1;
//                        wd_emit_tag    = bram_dout[15:0];
//                    end
//                    default: begin
//                        state_next = ST_IN_CNT;
//                    end
//                endcase
//            end

//            ST_IN_CNT: begin
//                bram_addr_next = IN_SPIKES_COUNT;
//                state_next     = ST_IN_LOAD;
//            end

//            ST_IN_LOAD: begin
//                bram_addr_next = IN_SPIKES_BASE + (spk_cnt << 2);
//                if (spk_cnt < spikes_num && !full_fifo_in) begin
//                    wr_fifo_in      = 1'b1;
//                    wr_data_fifo_in = bram_dout[15:0];
//                end else if (spk_cnt >= spikes_num) begin
//                    state_next = ST_RUN;
//                end
//            end

//            ST_RUN: begin
//                // default: core drives BRAM for weights (offset to WEIGHTS_BASE)
//                bram_addr_next = WEIGHTS_BASE + {{(ADDR_WIDTH_WORD-4){1'b0}}, bram_addr_weights};
//                bram_en_next   = bram_en_weights;
//                bram_we_next   = {{(WE_WIDTH-2){1'b0}}, bram_we_weights};

//                // capture outgoing spikes into BRAM (overrides weight access for a cycle)
//                if (!empty_fifo_out) begin
//                    rd_fifo_out    = 1'b1;
//                    bram_en_next   = 1'b1;
//                    bram_we_next   = 4'b0011;
//                    bram_addr_next = OUT_SPIKES_BASE + (out_cnt << 2);
//                    bram_din_next  = {16'd0, rd_data_fifo_out};
//                end
//            end

//            default: state_next = ST_IDLE;
//        endcase
//    end

endmodule
