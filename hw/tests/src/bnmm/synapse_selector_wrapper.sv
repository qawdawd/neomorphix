module synapse_selector_wrapper (
    // -----------------------------------------
    // ПОРТЫ ДЛЯ ТЕСТБЕНЧА
    // -----------------------------------------
    input  logic clk,
    input  logic rst,

    // Управляющие порты
    input  logic        start,
    input  logic [7:0]  preIndex,
    input  logic [7:0]  postsynCount,
    input  logic [11:0] baseAddr,

    // Выходы
    output logic        busy,
    output logic        done,
    output logic [7:0]  postIndex,
    output logic [15:0] weight,

    // Память
    output logic [11:0] mem_addr,
    output logic        mem_en,
    input  logic [31:0] mem_data
);

    // -----------------------------------------
    // DUT (Cyclix-generated)
    // -----------------------------------------
    synapse_selector_demo dut (
        .clk_i      (clk),
        .rst_i      (rst),
        .wmem_addr  (mem_addr),
        .wmem_data  (mem_data),
        .wmem_en    (mem_en)
    );

    // ---------------------------------------------------
    // Теперь связываем глобальные сигналы DUT с портами
    // ---------------------------------------------------

    // Управление
    assign dut.start_demo_sel   = start;
    assign dut.preidx_demo_sel  = preIndex;

    // Рантайм конфигурация
    assign dut.postsyn_count    = postsynCount;
    assign dut.weights_base     = baseAddr;

    // Выходы
    assign busy      = dut.busy_demo_sel;
    assign done      = dut.done_demo_sel;
    assign postIndex = dut.postidx_demo_sel;
    assign weight    = dut.weight_demo_sel;

endmodule
