`timescale 1ns / 1ps

module top(
    input  wire        clk_100MHz,
    input  wire        reset_rtl_0_0,
    output wire [31:0] dbg_data      // для отладки
);

    wire [12:0] bram_addr_word;

    wire [31:0] bram_addr = {19'd0, bram_addr_word};

    wire [31:0] bram_din;
    wire [31:0] bram_dout;
    wire        bram_en;
    wire [3:0]  bram_we;

    // 1) Block Design (MicroBlaze + AXI BRAM + BRAM PORT B наружу)
    design_1_wrapper u_bd (
        .clk_100MHz       (clk_100MHz),
        .reset_rtl_0_0    (reset_rtl_0_0),

        .BRAM_PORTB_addr  (bram_addr),
        .BRAM_PORTB_clk   (clk_100MHz),
        .BRAM_PORTB_din   (bram_din),
        .BRAM_PORTB_dout  (bram_dout),
        .BRAM_PORTB_en    (bram_en),
        .BRAM_PORTB_rst   (reset_rtl_0_0),
        .BRAM_PORTB_we    (bram_we)
    );

    wrapper u_wrapper (
        .clk           (clk_100MHz),
        .rst           (reset_rtl_0_0),

        .bram_addr_word(bram_addr_word),
        .bram_en       (bram_en),
        .bram_we       (bram_we),
        .bram_din      (bram_din),
        .bram_dout     (bram_dout),

        .last_read_data(dbg_data)
    );

endmodule