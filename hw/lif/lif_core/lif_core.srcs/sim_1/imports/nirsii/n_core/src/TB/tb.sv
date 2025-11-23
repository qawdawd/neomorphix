`timescale 1ns/1ps

module tb;

    logic clk_100MHz;
    logic reset_rtl_0_0;

    // CLOCK 100 МГц
    initial clk_100MHz = 0;
    always #5 clk_100MHz = ~clk_100MHz;

    // RESET
    initial begin
        reset_rtl_0_0 = 1;
        #200;
        reset_rtl_0_0 = 0;
    end

    // DUT
    top dut (
        .clk_100MHz   (clk_100MHz),
        .reset_rtl_0_0(reset_rtl_0_0)
    );


    initial #100000 $finish;

endmodule


// `timescale 1ns/1ps

// module tb;

//     logic clk_100MHz;
//     logic reset_rtl_0_0;
//     wire  [31:0] dbg_data;

//     // такт/ресет
//     initial clk_100MHz = 0;
//     always #5 clk_100MHz = ~clk_100MHz; // 100 МГц

//     initial begin
//         reset_rtl_0_0 = 1;
//         #100;
//         reset_rtl_0_0 = 0;
//     end

//     // DUT - твой top
//     top dut (
//         .clk_100MHz   (clk_100MHz),
//         .reset_rtl_0_0(reset_rtl_0_0),
//         .dbg_data     (dbg_data)
//     );

//     // мониторим кое-что для наглядности
//     initial begin
//         #50000;
//         $display("time   dbg_data");
//         $monitor("%0t   0x%08h", $time, dbg_data);
// //        #50000;
// //        $finish;
//     end

// endmodule
