package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Exports and restores every SharedPreferences file owned by Lulu1.
 *
 * The backup intentionally includes model connection secrets so a restored
 * installation is actually usable. The settings UI must warn the user to keep
 * the file private.
 */
object LuluBackupManager {
    private const val SCHEMA_VERSION = 1

    fun exportJson(context: Context): String {
        val appContext = context.applicationContext
        val preferenceDirectory = File(appContext.applicationInfo.dataDir, "shared_prefs")
        val names = preferenceDirectory.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.extension == "xml" }
            .map { file -> file.name.removeSuffix(".xml") }
            .distinct()
            .sorted()

        val stores = JSONObject()
        names.forEach { name ->
            val values = JSONObject()
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                .all
                .toSortedMap()
                .forEach { (key, value) -> values.put(key, encodeValue(value)) }
            stores.put(name, values)
        }

        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("applicationId", appContext.packageName)
            .put("exportedAt", Instant.now().toString())
            .put("preferences", stores)
            .toString(2)
    }

    fun importJson(context: Context, raw: String): BackupImportResult {
        val appContext = context.applicationContext
        val root = JSONObject(raw)
        val schema = root.optInt("schemaVersion", -1)
        require(schema == SCHEMA_VERSION) { "不支持的备份版本：$schema" }
        val stores = root.optJSONObject("preferences") ?: error("备份中没有本地数据")

        var storeCount = 0
        var valueCount = 0
        val names = stores.keys()
        while (names.hasNext()) {
            val name = names.next()
            val values = stores.optJSONObject(name) ?: continue
            val editor = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val encoded = values.optJSONObject(key) ?: continue
                when (encoded.optString("type")) {
                    "string" -> editor.putString(key, encoded.optString("value"))
                    "int" -> editor.putInt(key, encoded.optInt("value"))
                    "long" -> editor.putLong(key, encoded.optLong("value"))
                    "float" -> editor.putFloat(key, encoded.optDouble("value").toFloat())
                    "boolean" -> editor.putBoolean(key, encoded.optBoolean("value"))
                    "string_set" -> {
                        val array = encoded.optJSONArray("value") ?: JSONArray()
                        val set = buildSet {
                            for (index in 0 until array.length()) {
                                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                        editor.putStringSet(key, set)
                    }
                }
                valueCount += 1
            }
            check(editor.commit()) { "恢复 $name 失败" }
            storeCount += 1
        }
        return BackupImportResult(storeCount, valueCount)
    }

    private fun encodeValue(value: Any?): JSONObject = when (value) {
        is String -> JSONObject().put("type", "string").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value)
        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Set<*> -> JSONObject()
            .put("type", "string_set")
            .put("value", JSONArray(value.filterIsInstance<String>().sorted()))
        else -> JSONObject().put("type", "string").put("value", value?.toString().orEmpty())
    }
}

data class BackupImportResult(
    val preferenceStoreCount: Int,
    val restoredValueCount: Int,
)
