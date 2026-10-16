package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persist all per-app assignments and custom recipes in private app storage. */
object LuluAppearanceStore {
    private const val PREFS = "lulu_appearance_v1"
    private const val KEY = "library"
    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private val mutable = MutableStateFlow(LuluAppearanceLibrary())
    val state = mutable.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            mutable.value = decode(prefs?.getString(KEY, null))
        }
    }

    fun apply(scope: String, id: String) = update { it.select(scope, id) }

    fun saveCopy(scope: String, sourceId: String, name: String, accent: Long?, corner: Int?) {
        synchronized(lock) {
            val source = mutable.value.find(sourceId) ?: return
            val safeName = name.trim().take(35).ifBlank { source.name + " · 我的版本" }
            val added = source.copy(
                id = UUID.randomUUID().toString(),
                name = safeName,
                description = "我的自定义方案 · 可随时切回",
                palette = source.palette.copy(
                    accent = accent ?: source.palette.accent,
                    onAccent = accent?.let(::readableTextForAccent) ?: source.palette.onAccent,
                ),
                corner = (corner ?: source.corner).coerceIn(0, 36),
                builtIn = false,
            )
            val next = mutable.value.save(added).select(scope, added.id)
            mutable.value = next
            prefs?.edit()?.putString(KEY, encode(next))?.apply()
        }
    }

    fun deleteUnused(id: String) = update { it.remove(id) }


    /** Pick the higher WCAG contrast of black/white for a user-selected accent. */
    private fun readableTextForAccent(argb: Long): Long {
        fun channel(value: Int): Double {
            val v = value / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val red = channel(((argb shr 16) and 255).toInt())
        val green = channel(((argb shr 8) and 255).toInt())
        val blue = channel((argb and 255).toInt())
        val luminance = red * 0.2126 + green * 0.7152 + blue * 0.0722
        return if (luminance >= 0.179) 0xFF231D1BL else 0xFFFFFFFFL
    }

    private fun update(transform: (LuluAppearanceLibrary) -> LuluAppearanceLibrary) {
        synchronized(lock) {
            val next = transform(mutable.value)
            if (next == mutable.value) return
            mutable.value = next
            prefs?.edit()?.putString(KEY, encode(next))?.apply()
        }
    }

    private fun encode(library: LuluAppearanceLibrary): String {
        val result = JSONObject()
        val assignments = JSONObject()
        library.selections.forEach { (key, id) -> assignments.put(key, id) }
        result.put("selections", assignments)
        result.put("recent", JSONArray(library.recent))
        val saved = JSONArray()
        library.custom.forEach { profile ->
            val p = profile.palette
            saved.put(JSONObject()
                .put("id", profile.id).put("name", profile.name)
                .put("description", profile.description).put("motif", profile.motif.name)
                .put("font", profile.font.name).put("corner", profile.corner)
                .put("dark", profile.dark)
                .put("paper", p.paper).put("card", p.card).put("cardStrong", p.cardStrong)
                .put("ink", p.ink).put("muted", p.muted).put("border", p.border)
                .put("accent", p.accent).put("onAccent", p.onAccent).put("soft", p.soft))
        }
        result.put("custom", saved)
        return result.toString()
    }

    private fun decode(raw: String?): LuluAppearanceLibrary {
        if (raw.isNullOrBlank()) return LuluAppearanceLibrary()
        return runCatching {
            val obj = JSONObject(raw)
            val custom = mutableListOf<LuluAppearance>()
            val array = obj.optJSONArray("custom") ?: JSONArray()
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val id = entry.optString("id")
                if (id.isBlank() || LuluAppearanceCatalog.builtIns.any { it.id == id }) continue
                val scheme = LuluAppearance(
                    id = id,
                    name = entry.optString("name", "我的方案"),
                    description = entry.optString("description", "我的自定义方案"),
                    palette = LuluAppearancePalette(
                        entry.getLong("paper"), entry.getLong("card"), entry.getLong("cardStrong"),
                        entry.getLong("ink"), entry.getLong("muted"), entry.getLong("border"),
                        entry.getLong("accent"), entry.getLong("onAccent"), entry.getLong("soft"),
                    ),
                    motif = enumValueOrDefault(entry.optString("motif"), LuluAppearanceMotif.CLEAN),
                    font = enumValueOrDefault(entry.optString("font"), LuluAppearanceFont.SANS),
                    corner = entry.optInt("corner", 18).coerceIn(0, 36),
                    dark = entry.optBoolean("dark"),
                    builtIn = false,
                )
                if (custom.none { it.id == id }) custom.add(scheme)
            }
            val selected = mutableMapOf<String, String>()
            val assigned = obj.optJSONObject("selections") ?: JSONObject()
            assigned.keys().forEach { scope ->
                if (LuluAppearanceCatalog.scopes.any { it.first == scope })
                    selected[scope] = assigned.optString(scope)
            }
            val recent = mutableListOf<String>()
            val ids = obj.optJSONArray("recent") ?: JSONArray()
            for (i in 0 until ids.length()) recent.add(ids.optString(i))
            val valid = LuluAppearanceCatalog.builtIns.map { it.id }.toSet() + custom.map { it.id }
            LuluAppearanceLibrary(
                custom = custom,
                selections = selected.filterValues { it in valid },
                recent = recent.distinct().filter { it in valid }.take(12),
            )
        }.getOrDefault(LuluAppearanceLibrary())
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}
