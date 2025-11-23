// Wrapper that bridges external BRAM with the generated bnmm_manual_demo core.
// The FSM mirrors the previous example: it fetches configuration words from
// BRAM, writes them into the core's register adapter, streams input spikes from
// BRAM into the ingress FIFO, enables tick generation to let the core process
// events, and captures output spikes back into BRAM.
module wrapper #(
    parameter ADDR_WIDTH_WORD = 13,
    parameter DATA_WIDTH      = 32,
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
    // BRAM map (word addresses)
    // ----------------------------------------------------------------
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_BASE             = 'd0;
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_EN_CORE_ADDR     = CFG_BASE + 0;
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_LEAKAGE_ADDR     = CFG_BASE + (1<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_VRST_ADDR        = CFG_BASE + (2<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_VTHRSH_ADDR      = CFG_BASE + (3<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_TOTAL_NEUR_ADDR  = CFG_BASE + (4<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_WEIGHT_BASE_ADDR = CFG_BASE + (5<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_NEURON_BASE_ADDR = CFG_BASE + (6<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_EMIT_TAG_ADDR    = CFG_BASE + (7<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] CFG_POSTSYN_CNT_ADDR = CFG_BASE + (8<<2);
    localparam int                         CFG_WORDS            = 9;

    localparam logic [ADDR_WIDTH_WORD-1:0] WEIGHTS_BASE  = CFG_BASE + (10<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] IN_SPIKES_BASE = WEIGHTS_BASE + (16<<2);
    localparam logic [ADDR_WIDTH_WORD-1:0] IN_SPIKES_COUNT = IN_SPIKES_BASE;
    localparam logic [ADDR_WIDTH_WORD-1:0] OUT_SPIKES_BASE = IN_SPIKES_BASE + (16<<2);

    // ----------------------------------------------------------------
    // Generated core instance
    // ----------------------------------------------------------------
    logic                   en_tick_manual;
    logic                   rst_tick_manual;
    logic                   wr_fifo_in;
    logic [15:0]            wr_data_fifo_in;
    logic                   full_fifo_in;
    logic                   rd_fifo_out;
    logic [7:0]             rd_data_fifo_out;
    logic                   empty_fifo_out;

    // external weight BRAM signals from the core
    logic                   bram_en_weights;
    logic [1:0]             bram_we_weights;
    logic [3:0]             bram_addr_weights;

    // register adapter strobes
    logic                   wr_leakage;
    logic [15:0]            wd_leakage;
    logic                   wr_threshold;
    logic [15:0]            wd_threshold;
    logic                   wr_vreset;
    logic [15:0]            wd_vreset;
    logic                   wr_total_neurons;
    logic [15:0]            wd_total_neurons;
    logic                   wr_weight_base;
    logic [15:0]            wd_weight_base;
    logic                   wr_neuron_base;
    logic [15:0]            wd_neuron_base;
    logic                   wr_emit_tag;
    logic [15:0]            wd_emit_tag;
    logic                   wr_postsyn_count;
    logic [15:0]            wd_postsyn_count;

    bnmm_manual_demo u_core (
        .clk_i           (clk),
        .rst_i           (rst),
        .en_tick_manual  (en_tick_manual),
        .rst_tick_manual (rst_tick_manual),

        .wr_fifo_in      (wr_fifo_in),
        .wr_data_fifo_in (wr_data_fifo_in),
        .full_fifo_in    (full_fifo_in),

        .rd_fifo_out     (rd_fifo_out),
        .rd_data_fifo_out(rd_data_fifo_out),
        .empty_fifo_out  (empty_fifo_out),

        .rd_weights_0    (1'b0),
        .addr_weights_0  (4'd0),
        .data_weights_0  (),

        .bram_addr_weights(bram_addr_weights),
        .bram_en_weights (bram_en_weights),
        .bram_we_weights (bram_we_weights),
        .bram_dout_weights(bram_dout[15:0]),

        .rd_regs_0       (1'b0),
        .addr_regs_0     (4'd0),
        .data_regs_0     (),

        .we_regs         (1'b0),
        .waddr_regs      (4'd0),
        .wdata_regs      (16'd0),

        .wr_leakage      (wr_leakage),
        .wd_leakage      (wd_leakage),
        .wr_threshold    (wr_threshold),
        .wd_threshold    (wd_threshold),
        .wr_vreset       (wr_vreset),
        .wd_vreset       (wd_vreset),
        .wr_total_neurons(wr_total_neurons),
        .wd_total_neurons(wd_total_neurons),
        .wr_weight_base  (wr_weight_base),
        .wd_weight_base  (wd_weight_base),
        .wr_neuron_base  (wr_neuron_base),
        .wd_neuron_base  (wd_neuron_base),
        .wr_emit_tag     (wr_emit_tag),
        .wd_emit_tag     (wd_emit_tag),
        .wr_postsyn_count(wr_postsyn_count),
        .wd_postsyn_count(wd_postsyn_count)
    );

    // ----------------------------------------------------------------
    // FSM bookkeeping
    // ----------------------------------------------------------------
    typedef enum logic [2:0] {
        ST_IDLE,
        ST_CFG_LOAD,
        ST_IN_CNT,
        ST_IN_LOAD,
        ST_RUN
    } state_t;

    state_t state, state_next;

    logic [7:0] cfg_idx;
    logic [9:0] spikes_num;
    logic [9:0] spk_cnt;
    logic [9:0] out_cnt;

    logic [ADDR_WIDTH_WORD-1:0] bram_addr_next;
    logic                       bram_en_next;
    logic [WE_WIDTH-1:0]        bram_we_next;
    logic [DATA_WIDTH-1:0]      bram_din_next;

    // ----------------------------------------------------------------
    // Sequential logic
    // ----------------------------------------------------------------
    always_ff @(posedge clk) begin
        if (rst) begin
            state             <= ST_IDLE;
            cfg_idx           <= '0;
            spikes_num        <= '0;
            spk_cnt           <= '0;
            out_cnt           <= '0;

            bram_addr_word    <= '0;
            bram_en           <= 1'b0;
            bram_we           <= '0;
            bram_din          <= '0;
            last_read_data    <= '0;

            wr_fifo_in        <= 1'b0;
            rd_fifo_out       <= 1'b0;

            en_tick_manual    <= 1'b0;
            rst_tick_manual   <= 1'b1;
        end else begin
            state          <= state_next;
            bram_addr_word <= bram_addr_next;
            bram_en        <= bram_en_next;
            bram_we        <= bram_we_next;
            bram_din       <= bram_din_next;
            last_read_data <= bram_dout;

            rst_tick_manual  <= 1'b0;
            en_tick_manual   <= (state_next == ST_RUN);

            if (state == ST_CFG_LOAD)
                cfg_idx <= cfg_idx + 1'b1;
            else if (state_next == ST_CFG_LOAD && state != ST_CFG_LOAD)
                cfg_idx <= 0;

            if (state == ST_IN_LOAD && wr_fifo_in && !full_fifo_in)
                spk_cnt <= spk_cnt + 1'b1;
            else if (state_next != ST_IN_LOAD)
                spk_cnt <= '0;

            if (state == ST_RUN && rd_fifo_out && !empty_fifo_out)
                out_cnt <= out_cnt + 1'b1;
            else if (state_next != ST_RUN)
                out_cnt <= '0;

            if (state == ST_IN_CNT)
                spikes_num <= bram_dout[9:0];
        end
    end

    // ----------------------------------------------------------------
    // Combinational control
    // ----------------------------------------------------------------
    always_comb begin
        state_next    = state;

        bram_addr_next = bram_addr_word;
        bram_en_next  = 1'b1;
        bram_we_next  = '0;
        bram_din_next = '0;

        wr_leakage       = 1'b0;
        wr_threshold     = 1'b0;
        wr_vreset        = 1'b0;
        wr_total_neurons = 1'b0;
        wr_weight_base   = 1'b0;
        wr_neuron_base   = 1'b0;
        wr_emit_tag      = 1'b0;
        wr_postsyn_count = 1'b0;

        wr_fifo_in       = 1'b0;
        wr_data_fifo_in  = '0;
        rd_fifo_out      = 1'b0;

        wd_leakage        = 16'd0;
        wd_threshold      = 16'd0;
        wd_vreset         = 16'd0;
        wd_total_neurons  = 16'd0;
        wd_weight_base    = 16'd0;
        wd_neuron_base    = 16'd0;
        wd_emit_tag       = 16'd0;
        wd_postsyn_count  = 16'd0;

        case (state)
            ST_IDLE: begin
                bram_addr_next = CFG_EN_CORE_ADDR;
                if (bram_dout[0])
                    state_next = ST_CFG_LOAD;
            end

            ST_CFG_LOAD: begin
                case (cfg_idx)
                    0: begin
                        bram_addr_next = CFG_LEAKAGE_ADDR;
                        wr_leakage     = 1'b1;
                        wd_leakage     = bram_dout[15:0];
                    end
                    1: begin
                        bram_addr_next = CFG_VRST_ADDR;
                        wr_vreset      = 1'b1;
                        wd_vreset      = bram_dout[15:0];
                    end
                    2: begin
                        bram_addr_next = CFG_VTHRSH_ADDR;
                        wr_threshold   = 1'b1;
                        wd_threshold   = bram_dout[15:0];
                    end
                    3: begin
                        bram_addr_next = CFG_TOTAL_NEUR_ADDR;
                        wr_total_neurons = 1'b1;
                        wd_total_neurons = bram_dout[15:0];
                    end
                    4: begin
                        bram_addr_next = CFG_WEIGHT_BASE_ADDR;
                        wr_weight_base = 1'b1;
                        wd_weight_base = bram_dout[15:0];
                    end
                    5: begin
                        bram_addr_next = CFG_NEURON_BASE_ADDR;
                        wr_neuron_base = 1'b1;
                        wd_neuron_base = bram_dout[15:0];
                    end
                    6: begin
                        bram_addr_next = CFG_EMIT_TAG_ADDR;
                        wr_emit_tag    = 1'b1;
                        wd_emit_tag    = bram_dout[15:0];
                    end
                    7: begin
                        bram_addr_next   = CFG_POSTSYN_CNT_ADDR;
                        wr_postsyn_count = 1'b1;
                        wd_postsyn_count = bram_dout[15:0];
                        state_next       = ST_IN_CNT;
                    end
                    default: begin
                        state_next = ST_IN_CNT;
                    end
                endcase
            end

            ST_IN_CNT: begin
                bram_addr_next = IN_SPIKES_COUNT;
                state_next     = ST_IN_LOAD;
            end

            ST_IN_LOAD: begin
                bram_addr_next = IN_SPIKES_BASE + (spk_cnt << 2);
                if (spk_cnt < spikes_num && !full_fifo_in) begin
                    wr_fifo_in      = 1'b1;
                    wr_data_fifo_in = bram_dout[15:0];
                end else if (spk_cnt >= spikes_num) begin
                    state_next = ST_RUN;
                end
            end

            ST_RUN: begin
                // default: core drives BRAM for weights (offset to WEIGHTS_BASE)
                bram_addr_next = WEIGHTS_BASE + {{(ADDR_WIDTH_WORD-4){1'b0}}, bram_addr_weights};
                bram_en_next   = bram_en_weights;
                bram_we_next   = {{(WE_WIDTH-2){1'b0}}, bram_we_weights};

                // capture outgoing spikes into BRAM (overrides weight access for a cycle)
                if (!empty_fifo_out) begin
                    rd_fifo_out    = 1'b1;
                    bram_en_next   = 1'b1;
                    bram_we_next   = 4'b0011;
                    bram_addr_next = OUT_SPIKES_BASE + (out_cnt << 2);
                    bram_din_next  = {16'd0, rd_data_fifo_out};
                end
            end

            default: state_next = ST_IDLE;
        endcase
    end

endmodule
