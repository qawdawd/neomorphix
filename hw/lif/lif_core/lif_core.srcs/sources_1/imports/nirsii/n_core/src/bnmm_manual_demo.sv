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

    // decoded register writes from the core
    output logic         wr_leakage,
    output logic [15:0]  wd_leakage,
    output logic         wr_threshold,
    output logic [15:0]  wd_threshold,
    output logic         wr_vreset,
    output logic [15:0]  wd_vreset,
    output logic         wr_total_neurons,
    output logic [15:0]  wd_total_neurons,
    output logic         wr_weight_base,
    output logic [15:0]  wd_weight_base,
    output logic         wr_neuron_base,
    output logic [15:0]  wd_neuron_base,
    output logic         wr_emit_tag,
    output logic [15:0]  wd_emit_tag,
    output logic         wr_postsyn_count,
    output logic [15:0]  wd_postsyn_count
);

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
    assign data_regs_0 = 16'h0000;

    // decoded register writes are unused in this placeholder.
    assign wr_leakage       = 1'b0;
    assign wd_leakage       = 16'h0000;
    assign wr_threshold     = 1'b0;
    assign wd_threshold     = 16'h0000;
    assign wr_vreset        = 1'b0;
    assign wd_vreset        = 16'h0000;
    assign wr_total_neurons = 1'b0;
    assign wd_total_neurons = 16'h0000;
    assign wr_weight_base   = 1'b0;
    assign wd_weight_base   = 16'h0000;
    assign wr_neuron_base   = 1'b0;
    assign wd_neuron_base   = 16'h0000;
    assign wr_emit_tag      = 1'b0;
    assign wd_emit_tag      = 16'h0000;
    assign wr_postsyn_count = 1'b0;
    assign wd_postsyn_count = 16'h0000;

endmodule
