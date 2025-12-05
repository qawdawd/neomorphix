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
    parameter CFG_WORDS       = 12,
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

    parameter PRESYN_L2_NUMS  = POSTSYN_NUM; 
    parameter POSTSYN_L2_NUMS =  8;
    parameter PRESYN_L3_NUMS  = POSTSYN_L2_NUMS; 
    parameter POSTSYN_L3_NUMS  = 8;

    localparam logic [ADDR_WIDTH_WORD-1:0] 
            WEIGHTS_BASE  = 64; // CFG_BASE + (CFG_WORDS<<2);

    localparam int WEIGHTS_PER_WORD = 2;

    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_BASE             = 'd0;
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_EN_CORE_ADDR     = 0; // CFG_BASE + 0;
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_LEAKAGE_ADDR     = 4; //  CFG_BASE + (1<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_VRST_ADDR        = 8; //CFG_BASE + (2<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_VTHRSH_ADDR      = 12; // CFG_BASE + (3<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_POSTSYN_NEUR_CNT_ADDR  = 16; // CFG_BASE + (4<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_WEIGHT_BASE_ADDR = 20; // CFG_BASE + (5<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
            CFG_LAYERS_NUM = 24; // CFG_BASE + (6<<2);


    localparam logic [ADDR_WIDTH_WORD-1:0] 
        CFG_L2_POSTSYN_NEUR_CNT_ADDR  = 28; // CFG_BASE + (4<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
        CFG_L3_POSTSYN_NEUR_CNT_ADDR = 32; // CFG_BASE + (5<<2);
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
        CFG_L4_POSTSYN_NEUR_CNT_ADDR = 36; // CFG_BASE + (5<<2);

    localparam logic [ADDR_WIDTH_WORD-1:0]  WEIGHT_L3_WORDS =  (PRESYN_L3_NUMS * POSTSYN_L3_NUMS) / WEIGHTS_PER_WORD;

    localparam logic [ADDR_WIDTH_WORD-1:0]  WEIGHT_L2_WORDS = (PRESYN_L2_NUMS * POSTSYN_L2_NUMS) / WEIGHTS_PER_WORD;
    
    localparam logic [ADDR_WIDTH_WORD-1:0] 
        WEIGHTS_L2_BASE  = (WEIGHTS_BASE + (WEIGHT_WORDS<<2));  

    localparam logic [ADDR_WIDTH_WORD-1:0] 
        WEIGHTS_L3_BASE  = (WEIGHTS_L2_BASE + (WEIGHT_L2_WORDS<<2));

    localparam logic [ADDR_WIDTH_WORD-1:0] 
        WEIGHTS_L4_BASE  = 48; // CFG_BASE + (CFG_WORDS<<2);


    localparam logic [ADDR_WIDTH_WORD-1:0] 
        CFG_FLAG_DATA_READY  = 52; // CFG_BASE + (CFG_WORDS<<2);
            
    localparam logic [ADDR_WIDTH_WORD-1:0] 
        CFG_CORE_BUSY  = 56; // CFG_BASE + (CFG_WORDS<<2);
    

    // localparam int CFG_WORDS   = 8;



    // localparam int PRESYN_NUM = 16;
    // localparam int POSTSYN_NUM = 16;

    localparam int WEIGHT_WORDS = (PRESYN_NUM * POSTSYN_NUM) / WEIGHTS_PER_WORD;

    localparam logic [ADDR_WIDTH_WORD-1:0] 
            IN_SPIKES_BASE = WEIGHTS_L2_BASE + (WEIGHT_L2_WORDS << 2);
    
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

        logic [ADDR_WIDTH_WORD-1:0] bram_din_next;


    
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

    logic                   emission_done;
    
    
    logic [7:0] cfg_idx;
    logic [9:0] spk_cnt;
    logic [9:0] out_cnt;

    logic [9:0] spikes_num;
    
    logic [15:0] layer_num;
    logic [15:0] layer_cnt;

    logic [SPIKE_WIDTH-1:0] queue_buf[1024];

    logic [7:0] neur_num_curr_layer;


    logic [2:0] network_done;

    logic [31:0] L2_postsyn_neurons;
    logic [31:0] L3_postsyn_neurons;
    logic [31:0] L4_postsyn_neurons;
    logic [31:0] weights_L2_base;
    logic [31:0] weights_L3_base;
    logic [31:0] weights_L4_base;


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
        .wd_emit_tag      (wd_emit_tag),

        .emission_done   (emission_done) 
    );

    
    logic [DATA_WIDTH-1:0] bram_dout_q;

    // Provide the core with the BRAM weight data (registered for timing
    // alignment with bram_dout_q).
    assign bram_dout_weights = bram_dout_q;
    
    always_ff @(posedge clk) begin
        bram_dout_q <= bram_dout;  // всегда задерживаем на 1 такт
    end

    always_ff @(posedge clk) begin
        bram_din <= bram_din_next;  // всегда задерживаем на 1 такт
    end
    

    always_ff @( posedge clk) begin
        bram_addr_word <= bram_addr_word_next;
        bram_en <= bram_en_next;
    end

    // ----------------------------------------------------------------
    // FSM bookkeeping
    // ----------------------------------------------------------------
    typedef enum logic [3:0] {
        ST_IDLE,
        ST_CFG_LOAD,
        ST_SPK_LOAD, 
        ST_RUN,
        ST_OUT_RD,
        ST_PROCESSING_DONE
    } state_t;

    state_t state, state_next;
    
    logic [15:0] idx;

    bit data_ready;
    bit busy;

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

            rd_fifo_out <= 0;

            layer_cnt <= 0;

            network_done <= 0;

            data_ready <= 0;
    
        end else begin
            state <= state_next;          
            rst_tick_manual   <= 1'b0;

            unique case (state)
                //------------------------------------------------------
                ST_IDLE:
                //------------------------------------------------------
                begin
                if (bram_dout[0]) begin 
                    network_done <= 1;
                    busy <= 1;
                end
                end

                // -----------------------------------------------------
                ST_CFG_LOAD:
                // -----------------------------------------------------
                begin
                    if (state_next == ST_CFG_LOAD)begin  
                        if (cfg_idx == CFG_WORDS + 1)  
                            spikes_num <= bram_dout[9:0];
                        cfg_idx <= cfg_idx + 1;
                    end else
                        cfg_idx <= '0;
   
                    // if (wr_fifo_in && !full_fifo_in)
                    //     spk_cnt <= spk_cnt + 1;
                    // else if (state_next != ST_SPK_LOAD)
                    //     spk_cnt <= '0;

                    case (cfg_idx)
                        0: begin
                        end
                        1: begin
                            wr_leakage <= 1'b1;
                            wd_leakage <= bram_dout;
                        end
                        2: begin
                            wr_leakage <= 1'b0;
                            wr_vreset <= 1'b1;
                            wd_vreset <= bram_dout;
                        end
                        3: begin
                            wr_vreset <= 1'b0;
                            wr_threshold <= 1'b1;
                            wd_threshold <= bram_dout;
                        end
                        4: begin
                            wr_threshold <= 1'b0;
                            if (layer_cnt == 0) begin 
                                wr_postsyn_count <= 1'b1;
                                wd_postsyn_count <= bram_dout;
                            end 
                            else if (layer_cnt == 1) begin 
                                wr_postsyn_count <= 1'b1;
                                wd_postsyn_count <= L2_postsyn_neurons;
                            end
                            else if (layer_cnt == 2) begin 
                                wr_postsyn_count <= 1'b1;
                                wd_postsyn_count <= L3_postsyn_neurons;
                            end 
                            else if (layer_cnt == 3) begin 
                                wr_postsyn_count <= 1'b1;
                                wd_postsyn_count <= L4_postsyn_neurons;
                            end  
                        end
                        5: begin
                            wr_postsyn_count <= 0;
                            if (layer_cnt == 0) begin 
                                spikes_num <= bram_dout;
                            end 
                            else if (layer_cnt == 1) begin 
                                spikes_num <= 16;
                            end
                            else if (layer_cnt == 2) begin 
                                spikes_num <= 13;
                            end 
                            else if (layer_cnt == 3) begin 
                                spikes_num <= 8;
                            end
                        end
                        6: begin
                            layer_num <= bram_dout;
                        end
                        7: begin
                            L2_postsyn_neurons <= bram_dout;
                        end                    
                        8: begin
                            L3_postsyn_neurons <= bram_dout;
                        end
                        9: begin
                            L4_postsyn_neurons <= bram_dout;
                        end 
                        10: begin
                            weights_L2_base <= bram_dout;
                        end
                        11: begin
                            weights_L3_base <= bram_dout;
                        end
                        12: begin
                            weights_L4_base <= bram_dout;
                        end
                        13: begin
                            wr_weight_base   <= 1'b1;

                            if (layer_cnt == 0) begin 
                                wd_weight_base   <= bram_dout;
                            end 
                            else if (layer_cnt == 1) begin 
                                wd_weight_base   <= weights_L2_base;
                            end
                            else if (layer_cnt == 2) begin 
                                wd_weight_base   <= weights_L3_base;
                            end 
                            else if (layer_cnt == 3) begin 
                                wd_weight_base   <= weights_L4_base;
                            end
                        end

                        default: begin 
                            wr_weight_base <= 0;
                        end 
                    endcase                    
                end

                // -----------------------------------------------------
                ST_SPK_LOAD:
                // -----------------------------------------------------
                begin
                    cfg_idx <= (state_next != ST_CFG_LOAD) ? '0 : cfg_idx;
    
                    // if (wr_fifo_in && !full_fifo_in)
                    //     spk_cnt <= spk_cnt + 1;
                    // else if (state_next != ST_SPK_LOAD)
                    //     spk_cnt <= '0;

                    if (state_next == ST_SPK_LOAD && (spk_cnt < spikes_num))
                        spk_cnt <= spk_cnt + 1;
                    else if (state_next != ST_SPK_LOAD)
                        spk_cnt <= '0;

                    if (spk_cnt == spikes_num) begin 
                        en_tick_manual <= 1;
                    end 
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

                    if (state_next == ST_OUT_RD) begin 
                        rd_fifo_out <= 1'b1;
                    end 
                end

                //------------------------------------------------------
                ST_OUT_RD:
                //------------------------------------------------------
                begin              
                    if (layer_cnt < layer_num) begin     
                        if (rd_fifo_out && !empty_fifo_out) begin 
                            out_cnt <= out_cnt + 1;
                            queue_buf[out_cnt] <= rd_data_fifo_out;
                        end 
                    end 
                    else if (state_next == ST_PROCESSING_DONE) begin 
                        rd_fifo_out <= 0;
                        out_cnt <= '0;
                        layer_cnt <= 0;
                        network_done <= 2;
                    end
                    else if (state_next == ST_IDLE) begin 
                        layer_cnt <= layer_cnt + 1; 
                    end 
                    
                end 

                ST_PROCESSING_DONE:
                begin 
                    data_ready <= 1;


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

        bram_din_next        = 0;
        bram_we         = 0;
    
        // core-input defaults
        wr_fifo_in      = 1'b0;
        wr_data_fifo_in = '0;
        // rd_fifo_out     = 1'b0;

        // wr_weight_base   = 1'b1;
        // wd_weight_base   = WEIGHTS_BASE; // в количетсве весов, а не слов. Нужно будет селектор поправить 


    
        // ---------------------------------------------------------
        case (state)
            //------------------------------------------------------
            ST_IDLE:
            //------------------------------------------------------
            begin
                bram_addr_word_next = CFG_EN_CORE_ADDR;
    
                if (bram_dout[0]) begin 
                    if (~busy) begin 
                        bram_addr_word_next = CFG_CORE_BUSY;
                        bram_din_next <= 32'b1;
                        bram_we_next = 2'b1;
                    end
                    else if (busy) begin 
                        state_next = ST_CFG_LOAD;    
                    end 
                end 
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
                    end
                    2: begin
                        bram_addr_word_next = CFG_VTHRSH_ADDR;
                    end
                    3: begin
                        bram_addr_word_next = CFG_POSTSYN_NEUR_CNT_ADDR;
                    end
                    4: begin
                        bram_addr_word_next = IN_SPIKES_COUNT;
                    end
                    5: begin
                        bram_addr_word_next = CFG_LAYERS_NUM;
                    end
                    6: begin
                        bram_addr_word_next = CFG_L2_POSTSYN_NEUR_CNT_ADDR;
                    end
                    7: begin
                        bram_addr_word_next = CFG_L3_POSTSYN_NEUR_CNT_ADDR;
                    end
                    8: begin
                        bram_addr_word_next = CFG_L4_POSTSYN_NEUR_CNT_ADDR;
                    end
                    9: begin
                        bram_addr_word_next = WEIGHTS_L2_BASE;
                    end 
                    10: begin
                        bram_addr_word_next = WEIGHTS_L3_BASE;
                    end
                    11: begin
                        bram_addr_word_next = WEIGHTS_L4_BASE;
                    end
                    12: begin
                        bram_addr_word_next = WEIGHTS_BASE;
                    end

                    // 12: begin
                    //     bram_addr_word_next = IN_SPIKES;
                    // end

    
                    default: begin 
                        // spikes_num = bram_dout_q; // bram_dout;
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
                if (network_done == 1) begin 
                    bram_addr_word_next = IN_SPIKES + ((spk_cnt+1) << 2);
                    if (spk_cnt > 0) begin 
                        if (!full_fifo_in) begin
                            wr_fifo_in      = 1'b1;
                            wr_data_fifo_in = bram_dout_q;
                        end 
                        if (spk_cnt == spikes_num) begin
                            // en_tick_manual = 1;
                            wr_fifo_in      = 1'b0;
                            // if (tick_o_tick_manual == 1) begin 
                                state_next = ST_RUN;
                                // spk_cnt = 0;
                            // end 
                        end
                    end 
                end 
                else if (network_done == 2) begin 
                    if (spk_cnt > 0) begin 
                        if (!full_fifo_in) begin
                            wr_fifo_in      = 1'b1;
                            wr_data_fifo_in = queue_buf[spk_cnt];
                        end 
                        if (spk_cnt == spikes_num) begin
                            // en_tick_manual = 1;
                            wr_fifo_in      = 1'b0;
                            if (tick_o_tick_manual == 1) begin 
                                state_next = ST_RUN;
                                spk_cnt <= 0;
                            end 
                        end
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
    
                if (emission_done)
                    state_next = ST_OUT_RD;
            end
    
            //------------------------------------------------------
            ST_OUT_RD:
            //------------------------------------------------------
            begin
                if (!empty_fifo_out && (layer_cnt <= layer_num)) begin 
                    state_next = ST_OUT_RD;
                end 
                else if (!empty_fifo_out && (layer_cnt == layer_num)) begin 
                    bram_addr_word_next = OUT_SPIKES_BASE + (out_cnt << 1);
                    bram_din_next = rd_data_fifo_out;
                    bram_we_next = 2'b1;
                end 
                else if (empty_fifo_out && (layer_cnt == layer_num)) begin 
                    state_next = ST_PROCESSING_DONE;
                end 
                else if (empty_fifo_out && (layer_cnt < layer_num)) begin 
                    state_next = ST_IDLE;
                end 
            end

            ST_PROCESSING_DONE:
            begin 
                bram_addr_word_next = CFG_CORE_BUSY;
                bram_din_next = 32'b0;
                bram_we_next = 2'b1;
                state_next = ST_IDLE;
            end 
    
        endcase
    end

endmodule
