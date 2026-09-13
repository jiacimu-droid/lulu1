package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.ModelConnection
import org.json.JSONArray
import java.security.MessageDigest

/**
 * Learns a relevance floor from this embedding model's own weak-positive examples.
 *
 * A lexical hit is not treated as perfect ground truth, but it is a much safer calibration sample
 * than copying one cosine threshold across unrelated embedding models. Until enough examples exist,
 * vector-only hits are deliberately not allowed to inject memories by themselves.
 */
internal object MemoryVectorCalibrationStore {
    private const val PREFS_NAME = "lulu_memory_vector_calibration_v1"
    private const val MAX_SAMPLES = 96
    private const val MIN_SAMPLES = 8

    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun recordPositiveSamples(connection: ModelConnection, similarities: Collection<Double>) {
        val clean = similarities
            .filter { it.isFinite() && it in -1.0..1.0 }
        if (clean.isEmpty()) return
        val key = sampleKey(connection)
        val existing = decode(prefs?.getString(key, null))
        val next = (existing + clean).takeLast(MAX_SAMPLES)
        prefs?.edit()?.putString(key, JSONArray(next).toString())?.apply()
    }

    /** Returns null until this exact provider/model has enough observed relevant examples. */
    @Synchronized
    fun calibratedFloor(connection: ModelConnection): Double? {
        val samples = decode(prefs?.getString(sampleKey(connection), null)).sorted()
        if (samples.size < MIN_SAMPLES) return null
        // A low positive percentile tolerates paraphrases while remaining grounded in scores that
        // this model actually produced for text with an independent lexical relevance signal.
        val index = ((samples.size - 1) * 0.20).toInt().coerceIn(0, samples.lastIndex)
        val q20 = samples[index]
        val median = samples[samples.size / 2]
        return (q20 - (median - q20) * 0.20).coerceIn(-1.0, 1.0)
    }

    private fun sampleKey(connection: ModelConnection): String =
        "samples_${sha256(connection.baseUrl.trimEnd('/') + "|" + connection.model).take(24)}"

    private fun decode(raw: String?): List<Double> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optDouble(index, Double.NaN)
                    if (value.isFinite() && value in -1.0..1.0) add(value)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
