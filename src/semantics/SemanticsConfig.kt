package semantics

/**
 * Constraints describing how synaptic parameters may be packed into memory words.
 */
data class PackingConstraints(
    val wordWidth: Int = 32,
    val memoryPorts: Int = 1
) {
    init {
        require(wordWidth > 0) { "Word width must be positive" }
        require(memoryPorts > 0) { "Number of memory ports must be positive" }
    }
}

/**
 * Options controlling whether synaptic packing should be performed.
 */
data class PackingOptions(
    val enabled: Boolean = false,
    val constraints: PackingConstraints = PackingConstraints()
)

/**
 * Constraints that influence synaptic pipeline generation.
 */
data class PipelineConstraints(
    val maxStages: Int = 1,
    val operationLatencyCycles: Int = 1
) {
    init {
        require(maxStages > 0) { "Maximum number of stages must be positive" }
        require(operationLatencyCycles > 0) { "Operation latency must be positive" }
    }
}

/**
 * Options that toggle synaptic pipeline planning.
 */
data class PipelineOptions(
    val enabled: Boolean = false,
    val constraints: PipelineConstraints = PipelineConstraints()
)

/**
 * Options shared by phase-level parallelisation passes.
 */
data class ParallelismOptions(
    val enabled: Boolean = false,
    val groupSize: Int = 1,
    val maxActiveGroups: Int? = null
) {
    init {
        require(groupSize > 0) { "Group size must be positive" }
        if (maxActiveGroups != null) {
            require(maxActiveGroups > 0) { "Maximum number of active groups must be positive" }
        }
    }

    /** Returns a copy with the provided group size override (if positive). */
    fun withGroupSizeOverride(override: Int?): ParallelismOptions {
        val effectiveSize = override?.takeIf { it > 0 } ?: groupSize
        return copy(groupSize = effectiveSize)
    }
}

/**
 * Aggregate configuration passed to the semantic analyser.
 */
data class SemanticsConfig(
    val packing: PackingOptions = PackingOptions(),
    val pipeline: PipelineOptions = PipelineOptions(),
    val somaticParallelism: ParallelismOptions = ParallelismOptions(),
    val emissionParallelism: ParallelismOptions = ParallelismOptions(),
    val refractoryParallelism: ParallelismOptions = ParallelismOptions()
)
