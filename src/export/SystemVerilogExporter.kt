package export

import generation.GeneratedKernel

/** Simple container for SystemVerilog output. */
data class SystemVerilogArtifact(
    val moduleName: String,
    val body: String
)

/**
 * Converts a Cyclix kernel snippet into a SystemVerilog artefact. The exporter currently preserves
 * the high-level description to keep the example deterministic.
 */
class SystemVerilogExporter {
    fun export(kernel: GeneratedKernel): SystemVerilogArtifact {
        val header = "// SystemVerilog for ${kernel.name}\nmodule ${kernel.name};"
        val footer = "endmodule"
        val body = listOf(header, kernel.cyclixSnippet.prependIndent("  "), footer).joinToString("\n")
        return SystemVerilogArtifact(kernel.name, body)
    }
}
