`timescale 1ns/1ps

module tb_synapse_selector;

    // -----------------------------------------------------
    // Signals
    // -----------------------------------------------------
    logic clk = 0;
    logic rst = 1;

    // DUT inputs
    logic start;
    logic [7:0] preIndex;
    logic [7:0] postsynCount;
    logic [11:0] baseAddr;

    // Memory port (BRAM-like)
    logic [11:0] mem_addr;
    logic mem_en;
    logic [31:0] mem_data;

    // DUT outputs
    logic busy;
    logic done;
    logic [7:0] postIndex;
    logic [15:0] weight;

    // Clock generation: 100 MHz
    always #5 clk = ~clk;

    // -----------------------------------------------------
    // Memory model (simple read-only BRAM)
    // -----------------------------------------------------
    logic [31:0] mem [0:4095];   // 4K words of 32b

    initial begin
        // Fill memory with predictable pattern:
        // word[k] = { upper16 = k+1000, lower16 = k+2000 }
        integer i;
        for (i=0; i<4096; i++) begin
            mem[i] = {16'(i+1000), 16'(i+2000)};
        end
    end

    // Model combinational read
    assign mem_data = mem[mem_addr];

    // -----------------------------------------------------
    // Instantiate DUT
    // -----------------------------------------------------
synapse_selector_wrapper dut (
    .clk(clk),
    .rst(rst),

    .start(start),
    .preIndex(preIndex),
    .postsynCount(postsynCount),
    .baseAddr(baseAddr),

    .busy(busy),
    .done(done),
    .postIndex(postIndex),
    .weight(weight),

    .mem_addr(mem_addr),
    .mem_en(mem_en),
    .mem_data(mem_data)
);

    // -----------------------------------------------------
    // Test scenario
    // -----------------------------------------------------
    initial begin
        $display("=== Synapse Selector Testbench Start ===");

        // Hold reset
        rst = 1;
        start = 0;
        preIndex = 0;
        #100;
        rst = 0;

        // Configure selector: postsynCount runtime register
        // In RTL this may be global — here we drive a force
        //        force dut.postsyn_count = 8;   // 8 postsyn neurons
        //        force dut.weights_base  = 12'h040; // offset = 0x40
        postsynCount = 8;
        baseAddr = 12'h040;


        // Start test
        preIndex = 3;   // presyn neuron = 3
        #20;
        start = 1;
        #20;
        start = 0;

        // Wait until selector finishes
        wait (done == 1);
        #20;

        $display("=== Test finished ===");
        $finish;
    end

    // -----------------------------------------------------
    // Debug monitor
    // -----------------------------------------------------
    always @(posedge clk) begin
        if (!rst) begin
            $display("[t=%0t] addr=%0d en=%0b | post=%0d weight=%h busy=%b done=%b",
                $time, mem_addr, mem_en, postIndex, weight, busy, done);
        end
    end

endmodule
