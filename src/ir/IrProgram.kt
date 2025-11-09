package ir

import arch.SnnArch
import symbols.SymbolTable

/**
 * Immutable view of the IR program produced by [IrBuilder]. The program retains the original
 * architecture and symbol table so that downstream passes do not need to carry additional state.
 */
class IrProgram internal constructor(
    val phases: List<IrPhaseBlock>,
    val architecture: SnnArch,
    val symbols: SymbolTable,
    private val notes: MutableList<IrTransformNote> = mutableListOf()
) {

    /** Returns a snapshot of all transformation notes recorded for the program. */
    val plannedTransformations: List<IrTransformNote>
        get() = notes.toList()

    /** Records a new transformation note. */
    internal fun addTransformation(note: IrTransformNote) {
        notes += note
    }
}

