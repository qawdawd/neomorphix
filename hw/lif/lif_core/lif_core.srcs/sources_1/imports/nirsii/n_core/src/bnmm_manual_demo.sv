// Simplified placeholder for the generated BNMM manual demo core.
// Provides the expected interface so wrapper.sv can elaborate in Vivado.
module bnmm_manual_demo (
    input  logic         clk_i,
    input  logic         rst_i,
    input  logic         en_tick_manual,
    input  logic         rst_tick_manual,

    // ingress FIFO
    input  logic         wr_fifo_in,
    input  logic [15:0]  wr_data_fifo_in,
    output logic         full_fifo_in,

    // egress FIFO
    input  logic         rd_fifo_out,
    output logic [7:0]   rd_data_fifo_out,
    output logic         empty_fifo_out,

    // external weight memory interface
    input  logic         rd_weights_0,
    input  logic [3:0]   addr_weights_0,
    output logic [15:0]  data_weights_0,
    output logic [3:0]   bram_addr_weights,
    output logic         bram_en_weights,
    output logic [1:0]   bram_we_weights,
    input  logic [15:0]  bram_dout_weights,

    // register adapter interface
    input  logic         rd_regs_0,
    input  logic [3:0]   addr_regs_0,
    output logic [15:0]  data_regs_0,
    input  logic         we_regs,
    input  logic [3:0]   waddr_regs,
    input  logic [15:0]  wdata_regs,

    // decoded register writes driven by the wrapper
    input  logic         wr_leakage,
    input  logic [15:0]  wd_leakage,
    input  logic         wr_threshold,
    input  logic [15:0]  wd_threshold,
    input  logic         wr_vreset,
    input  logic [15:0]  wd_vreset,
    input  logic         wr_total_neurons,
    input  logic [15:0]  wd_total_neurons,
    input  logic         wr_weight_base,
    input  logic [15:0]  wd_weight_base,
    input  logic         wr_neuron_base,
    input  logic [15:0]  wd_neuron_base,
    input  logic         wr_emit_tag,
    input  logic [15:0]  wd_emit_tag,
    input  logic         wr_postsyn_count,
    input  logic [15:0]  wd_postsyn_count
);

    // Core-visible configuration registers
    logic [15:0] reg_leakage;
    logic [15:0] reg_threshold;
    logic [15:0] reg_vreset;
    logic [15:0] reg_total_neurons;
    logic [15:0] reg_weight_base;
    logic [15:0] reg_neuron_base;
    logic [15:0] reg_emit_tag;
    logic [15:0] reg_postsyn_count;

    // Common reset for all registers
    always_ff @(posedge clk_i) begin
        if (rst_i) begin
            reg_leakage        <= '0;
            reg_threshold      <= '0;
            reg_vreset         <= '0;
            reg_total_neurons  <= '0;
            reg_weight_base    <= '0;
            reg_neuron_base    <= '0;
            reg_emit_tag       <= '0;
            reg_postsyn_count  <= '0;
        end else begin
            if (wr_leakage)       reg_leakage       <= wd_leakage;
            if (wr_threshold)     reg_threshold     <= wd_threshold;
            if (wr_vreset)        reg_vreset        <= wd_vreset;
            if (wr_total_neurons) reg_total_neurons <= wd_total_neurons;
            if (wr_weight_base)   reg_weight_base   <= wd_weight_base;
            if (wr_neuron_base)   reg_neuron_base   <= wd_neuron_base;
            if (wr_emit_tag)      reg_emit_tag      <= wd_emit_tag;
            if (wr_postsyn_count) reg_postsyn_count <= wd_postsyn_count;

            if (we_regs) begin
                unique case (waddr_regs)
                    4'd0: reg_leakage       <= wdata_regs;
                    4'd1: reg_threshold     <= wdata_regs;
                    4'd2: reg_vreset        <= wdata_regs;
                    4'd3: reg_total_neurons <= wdata_regs;
                    4'd4: reg_weight_base   <= wdata_regs;
                    4'd5: reg_neuron_base   <= wdata_regs;
                    4'd6: reg_postsyn_count <= wdata_regs;
                    4'd7: reg_emit_tag      <= wdata_regs;
                    default: ;
                endcase
            end
        end
    end

    // default assignments keep the placeholder inert while providing
    // a consistent, driven interface for elaboration.
    assign full_fifo_in     = 1'b0;
    assign rd_data_fifo_out = 8'h00;
    assign empty_fifo_out   = 1'b1;

    // tie off weight BRAM controls and forward data bus.
    assign bram_addr_weights = addr_weights_0;
    assign bram_en_weights   = rd_weights_0;
    assign bram_we_weights   = 2'b00;
    assign data_weights_0    = bram_dout_weights;

    // register bank readback stub.
    always_comb begin
        data_regs_0 = 16'h0000;
        if (rd_regs_0) begin
            unique case (addr_regs_0)
                4'd0: data_regs_0 = reg_leakage;
                4'd1: data_regs_0 = reg_threshold;
                4'd2: data_regs_0 = reg_vreset;
                4'd3: data_regs_0 = reg_total_neurons;
                4'd4: data_regs_0 = reg_weight_base;
                4'd5: data_regs_0 = reg_neuron_base;
                4'd6: data_regs_0 = reg_postsyn_count;
                4'd7: data_regs_0 = reg_emit_tag;
                default: data_regs_0 = 16'h0000;
            endcase
        end
    end

endmodule
