/*
 * Adapted from InterTune (https://github.com/ItzSkyeYT/InterTune), Copyright (C) 2026 InterTune,
 * licensed under GPL-3.0. Used in Felicity under the AGPL-3.0 as GPL-3.0 section 13 allows.
 */

package app.simple.felicity.managers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Headphone curves from AutoEq (github.com/jaakkopasanen/AutoEq, MIT licence): for thousands of
 * measured models, a "fixed band" curve of ten peaking filters at 31 Hz to 16 kHz. Those are the
 * equalizer's own ten bands, so a model's curve is the sliders' positions as it stands.
 *
 * Nothing is fetched until somebody opens the list of models. Then the list itself (about 850 kB,
 * kept a month in the cache), and a model's curve (under 1 kB) once it is picked.
 */
object AutoEq {
    /** Felicity's ten equalizer bands, 31 Hz to 16 kHz, which AutoEq's fixed-band curves use too. */
    private val BANDS = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    private const val MAX_DB = 15f

    const val RESULTS = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    private const val INDEX_FILE = "autoeq-index.md"
    private const val INDEX_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** One measured model. [path] is its folder under results/ as the index writes it, percent-encoded. */
    data class Model(val name: String, val path: String, val source: String, val rig: String?)

    // "- [Sennheiser HD 800](./oratory1990/over-ear/Sennheiser%20HD%20800) by oratory1990", and
    // " on <rig>" after the source when one source measured with more than one rig.
    private val LINE = Regex("""^- \[(.+)]\(\./(.+)\) by (.+?)(?: on (.+))?$""")

    fun parseIndex(markdown: String): List<Model> = markdown.lineSequence().mapNotNull { line ->
        LINE.find(line.trim())?.let { m ->
            Model(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].ifEmpty { null })
        }
    }.toList()

    /** A model's ten band curve: in its folder, the folder's own name and " FixedBandEQ.txt". */
    fun profileUrl(model: Model): String =
        RESULTS + model.path + "/" + model.path.substringAfterLast('/') + "%20FixedBandEQ.txt"

    private val FILTER = Regex("""Filter\s+\d+:\s+ON\s+PK\s+Fc\s+([\d.]+)\s+Hz\s+Gain\s+(-?[\d.]+)\s+dB\s+Q\s+([\d.]+)""")

    /**
     * The ten gains of a FixedBandEQ.txt, band by band and within the equalizer's reach, or null
     * when its filters are not the equalizer's ten bands.
     */
    fun parseFixedBand(text: String): List<Float>? {
        val filters = FILTER.findAll(text).map {
            Triple(it.groupValues[1].toDouble(), it.groupValues[2].toFloat(), it.groupValues[3].toDouble())
        }.toList()
        if (filters.size != BANDS.size) return null
        val same = filters.zip(BANDS).all { (filter, band) -> abs(filter.first - band) <= band * 0.02 }
        if (!same) return null
        return filters.map { it.second.coerceIn(-MAX_DB, MAX_DB) }
    }

    /** The models whose name has every word of [query], in the index's order, the first [limit]. */
    fun search(models: List<Model>, query: String, limit: Int = 60): List<Model> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return models.asSequence()
            .filter { model -> model.name.lowercase().let { name -> words.all { it in name } } }
            .take(limit)
            .toList()
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun fetch(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    /**
     * The list of models: from the cache while it is under a month old, else fetched, else the old
     * copy; null when there is none of the three (offline the first time).
     */
    suspend fun models(context: Context): List<Model>? = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, INDEX_FILE)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < INDEX_MAX_AGE_MS
        val text = (if (fresh) file.readText() else null)
            ?: fetch(RESULTS + "INDEX.md")?.also { runCatching { file.writeText(it) } }
            ?: file.takeIf { it.exists() }?.readText()
        text?.let { parseIndex(it) }?.takeIf { it.isNotEmpty() }
    }

    /** What picking a model came to. */
    sealed interface Curve {
        data class Gains(val db: List<Float>) : Curve
        data object NotTenBands : Curve
        data object Unreachable : Curve
    }

    suspend fun curve(model: Model): Curve = withContext(Dispatchers.IO) {
        val text = fetch(profileUrl(model)) ?: return@withContext Curve.Unreachable
        parseFixedBand(text)?.let { Curve.Gains(it) } ?: Curve.NotTenBands
    }
}
