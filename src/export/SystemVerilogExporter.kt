package export

import generation.GeneratedKernel
import hwast.DEBUG_LEVEL

/** Simple container for SystemVerilog output. */
data class SystemVerilogArtifact(
    val moduleName: String,
    val outputDir: String
)

/** Converts a Cyclix kernel into a SystemVerilog artefact using the ActiveCore backend. */
class SystemVerilogExporter {
    fun export(kernel: GeneratedKernel, outputRoot: String = "out"): SystemVerilogArtifact {
        val rtl = kernel.generic.export_to_rtl(DEBUG_LEVEL.FULL)
        val targetDir = "$outputRoot/${kernel.name}"
        rtl.export_to_sv(targetDir, DEBUG_LEVEL.FULL)
        return SystemVerilogArtifact(kernel.name, targetDir)
    }
}
