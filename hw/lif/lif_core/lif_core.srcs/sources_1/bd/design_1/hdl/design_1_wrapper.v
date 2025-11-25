//Copyright 1986-2019 Xilinx, Inc. All Rights Reserved.
//--------------------------------------------------------------------------------
//Tool Version: Vivado v.2019.1 (lin64) Build 2552052 Fri May 24 14:47:09 MDT 2019
//Date        : Mon Nov 24 16:32:44 2025
//Host        : thinkpad running 64-bit Debian GNU/Linux 12 (bookworm)
//Command     : generate_target design_1_wrapper.bd
//Design      : design_1_wrapper
//Purpose     : IP block netlist
//--------------------------------------------------------------------------------
`timescale 1 ps / 1 ps

module design_1_wrapper
   (BRAM_PORTB_addr,
    BRAM_PORTB_clk,
    BRAM_PORTB_din,
    BRAM_PORTB_dout,
    BRAM_PORTB_en,
    BRAM_PORTB_rst,
    BRAM_PORTB_we,
    clk_100MHz,
    reset_rtl_0_0);
  input [31:0]BRAM_PORTB_addr;
  input BRAM_PORTB_clk;
  input [31:0]BRAM_PORTB_din;
  output [31:0]BRAM_PORTB_dout;
  input BRAM_PORTB_en;
  input BRAM_PORTB_rst;
  input [3:0]BRAM_PORTB_we;
  input clk_100MHz;
  input reset_rtl_0_0;

  wire [31:0]BRAM_PORTB_addr;
  wire BRAM_PORTB_clk;
  wire [31:0]BRAM_PORTB_din;
  wire [31:0]BRAM_PORTB_dout;
  wire BRAM_PORTB_en;
  wire BRAM_PORTB_rst;
  wire [3:0]BRAM_PORTB_we;
  wire clk_100MHz;
  wire reset_rtl_0_0;

  design_1 design_1_i
       (.BRAM_PORTB_addr(BRAM_PORTB_addr),
        .BRAM_PORTB_clk(BRAM_PORTB_clk),
        .BRAM_PORTB_din(BRAM_PORTB_din),
        .BRAM_PORTB_dout(BRAM_PORTB_dout),
        .BRAM_PORTB_en(BRAM_PORTB_en),
        .BRAM_PORTB_rst(BRAM_PORTB_rst),
        .BRAM_PORTB_we(BRAM_PORTB_we),
        .clk_100MHz(clk_100MHz),
        .reset_rtl_0_0(reset_rtl_0_0));
endmodule
