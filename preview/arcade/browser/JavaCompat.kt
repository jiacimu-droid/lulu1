package java.util

typealias ArrayDeque<T> = kotlin.collections.ArrayDeque<T>

// Java's LCG preserves the app's seeded chest rolls.
class Random(seed: Long) {
    private var state = (seed xor MULTIPLIER) and MASK
    private fun next(bits: Int): Int {
        state = (state * MULTIPLIER + 11) and MASK
        return (state ushr (48 - bits)).toInt()
    }
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        if (bound and -bound == bound) return ((bound.toLong() * next(31)) shr 31).toInt()
        while (true) {
            val sample = next(31)
            val value = sample % bound
            if (sample - value + (bound - 1) >= 0) return value
        }
    }
    private companion object {
        const val MULTIPLIER = 0x5DEECE66DL
        const val MASK = (1L shl 48) - 1
    }
}

object UUID {
    fun randomUUID(): String {
        val random = js("globalThis.crypto")
        return if (random?.randomUUID != null) random.randomUUID() as String
        else kotlin.random.Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
