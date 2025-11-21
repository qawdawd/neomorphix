package neuromorphix

object NmMath {
    /** ceil(log2(v)), минимум 1 бит */
    fun log2ceil(v: Int): Int {
        require(v > 0) { "log2ceil: v must be > 0" }
        var x = v - 1
        var r = 0
        while (x > 0) { x = x shr 1; r++ }
        return maxOf(r, 1)
    }

    /** Полезная обёртка, если нужно гарантировать минимум minBits */
    fun bitsForRange(maxValueInclusive: Int, minBits: Int = 1): Int =
        maxOf(minBits, log2ceil(maxValueInclusive + 1))
}