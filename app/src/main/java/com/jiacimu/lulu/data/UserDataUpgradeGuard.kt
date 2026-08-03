package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Protects user-owned API/model configuration when Lulu's storage format changes.
 *
 * Rules:
 * - Never replace a non-empty current library.
 * - Restore the latest valid internal snapshot only when the current value is missing/corrupt.
 * - Import legacy single-site keys from any SharedPreferences file as a last resort.
 * - Refresh a small rolling set of snapshots after a valid library is available.
 */
object UserDataUpgradeGuard {
    private const val TARGET_PREFS = "lulu_model_connection"
    private const val TARGET_KEY = "model_library_v2"
    private const val BACKUP_DIR = "user_data_backups"
    private const val LATEST_BACKUP = "model_library_latest.json"
    private const val MAX_ROLLING_BACKUPS = 5

    fun protectBeforeStoresInitialize(context: Context) {
        val appContext = context.applicationContext
        val target = appContext.getSharedPreferences(TARGET_PREFS, Context.MODE_PRIVATE)
        val current = target.getString(TARGET_KEY, null)

        if (isValidLibrary(current)) {
            writeBackup(appContext, current!!)
            return
        }

        val restored = readLatestValidBackup(appContext)
            ?: findLibraryInOtherPreferences(appContext)
            ?: buildLibraryFromLegacyKeys(appContext)

        if (restored != null && isValidLibrary(restored)) {
            target.edit().putString(TARGET_KEY, restored).commit()
            writeBackup(appContext, restored)
        }
    }

    fun refreshBackup(context: Context) {
        val value = context.applicationContext
            .getSharedPreferences(TARGET_PREFS, Context.MODE_PRIVATE)
            .getString(TARGET_KEY, null)
        if (isValidLibrary(value)) writeBackup(context.applicationContext, value!!)
    }

    private fun isValidLibrary(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return runCatching {
            val root = JSONObject(raw)
            val configurations = root.optJSONArray("configurations") ?: JSONArray()
            configurations.length() > 0 && (0 until configurations.length()).any { index ->
                val item = configurations.optJSONObject(index) ?: return@any false
                item.optString("baseUrl").isNotBlank() && item.optString("apiKey").isNotBlank()
            }
        }.getOrDefault(false)
    }

    private fun backupDirectory(context: Context): File =
        File(context.filesDir, BACKUP_DIR).apply { mkdirs() }

    private fun writeBackup(context: Context, raw: String) {
        if (!isValidLibrary(raw)) return
        val directory = backupDirectory(context)
        runCatching {
            File(directory, LATEST_BACKUP).writeText(raw)
            val rolling = File(directory, "model_library_${System.currentTimeMillis()}.json")
            rolling.writeText(raw)
            directory.listFiles()
                .orEmpty()
                .filter { it.name.startsWith("model_library_") && it.name != LATEST_BACKUP }
                .sortedByDescending(File::lastModified)
                .drop(MAX_ROLLING_BACKUPS)
                .forEach(File::delete)
        }
    }

    private fun readLatestValidBackup(context: Context): String? {
        val directory = backupDirectory(context)
        val candidates = buildList {
            add(File(directory, LATEST_BACKUP))
            addAll(directory.listFiles().orEmpty().sortedByDescending(File::lastModified))
        }.distinctBy(File::absolutePath)
        return candidates.firstNotNullOfOrNull { file ->
            runCatching { file.takeIf(File::isFile)?.readText() }
                .getOrNull()
                ?.takeIf(::isValidLibrary)
        }
    }

    private fun findLibraryInOtherPreferences(context: Context): String? {
        return preferenceNames(context)
            .asSequence()
            .filterNot { it == TARGET_PREFS }
            .mapNotNull { name ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                prefs.all.values.asSequence()
                    .filterIsInstance<String>()
                    .firstOrNull(::isValidLibrary)
            }
            .firstOrNull()
    }

    private fun buildLibraryFromLegacyKeys(context: Context): String? {
        val urlKeys = setOf("base_url", "baseUrl", "api_url", "apiUrl", "api_base", "apiBaseUrl", "endpoint")
        val keyKeys = setOf("api_key", "apiKey", "key", "token", "access_token")
        val nameKeys = setOf("config_name", "configuration_name", "site_name", "name")
        val modelKeys = setOf("model", "model_name", "selected_model", "active_model")

        for (prefsName in preferenceNames(context)) {
            val all = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all
            fun firstString(keys: Set<String>): String = keys.asSequence()
                .mapNotNull { all[it] as? String }
                .map(String::trim)
                .firstOrNull(String::isNotBlank)
                .orEmpty()

            val baseUrl = firstString(urlKeys).trimEnd('/')
            val apiKey = firstString(keyKeys)
            if (baseUrl.isBlank() || apiKey.isBlank()) continue

            val configurationId = UUID.randomUUID().toString()
            val model = firstString(modelKeys)
            val configuration = JSONObject()
                .put("id", configurationId)
                .put("name", firstString(nameKeys).ifBlank { "迁移的站点配置" })
                .put("baseUrl", baseUrl)
                .put("apiKey", apiKey)
            val archives = JSONArray()
            var activeArchiveId: String? = null
            if (model.isNotBlank()) {
                activeArchiveId = UUID.randomUUID().toString()
                archives.put(
                    JSONObject()
                        .put("id", activeArchiveId)
                        .put("configurationId", configurationId)
                        .put("model", model),
                )
            }
            return JSONObject()
                .put("configurations", JSONArray().put(configuration))
                .put("archives", archives)
                .put("activeArchiveId", activeArchiveId ?: JSONObject.NULL)
                .toString()
        }
        return null
    }

    private fun preferenceNames(context: Context): List<String> {
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        return directory.listFiles()
            .orEmpty()
            .filter { it.extension == "xml" }
            .map { it.name.removeSuffix(".xml") }
            .distinct()
    }
}
