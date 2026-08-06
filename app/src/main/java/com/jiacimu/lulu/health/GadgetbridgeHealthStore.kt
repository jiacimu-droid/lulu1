package com.jiacimu.lulu.health

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal data class GadgetbridgeDaySummary(
    val date: LocalDate,
    val steps: Int = 0,
    val averageHeartRate: Int? = null,
    val minimumHeartRate: Int? = null,
    val maximumHeartRate: Int? = null,
    val sleepMinutes: Int? = null,
    val spo2: Int? = null,
    val stress: Int? = null,
    val calories: Int? = null,
    val distanceMeters: Int? = null,
)

internal data class GadgetbridgeHealthState(
    val sourceUri: String = "",
    val sourceName: String = "",
    val tableName: String = "",
    val lastImportedAt: Instant? = null,
    val days: List<GadgetbridgeDaySummary> = emptyList(),
    val importing: Boolean = false,
    val error: String = "",
) {
    val connected: Boolean get() = sourceUri.isNotBlank()
    val latest: GadgetbridgeDaySummary? get() = days.maxByOrNull { it.date }
}

internal object GadgetbridgeHealthStore {
    private const val PREFS_NAME = "lulu_gadgetbridge_health"
    private const val KEY_STATE = "state_v1"
    private const val CACHE_FILE = "gadgetbridge-health-import.db"

    private val mutableState = MutableStateFlow(GadgetbridgeHealthState())
    val state: StateFlow<GadgetbridgeHealthState> = mutableState.asStateFlow()
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STATE, null)
        mutableState.value = decode(raw)
        if (mutableState.value.connected) GadgetbridgeHealthRefreshScheduler.schedule(context)
    }

    suspend fun connect(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        initialize(context)
        runCatching {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val current = mutableState.value.copy(
                sourceUri = uri.toString(),
                sourceName = displayName(context, uri),
                importing = true,
                error = "",
            )
            setState(context, current)
            importUri(context.applicationContext, uri)
            GadgetbridgeHealthRefreshScheduler.schedule(context)
        }.onFailure { error ->
            setState(
                context,
                mutableState.value.copy(
                    importing = false,
                    error = error.message ?: "无法读取 Gadgetbridge 数据库",
                ),
            )
        }
    }

    suspend fun refresh(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        initialize(context)
        val source = mutableState.value.sourceUri
        if (source.isBlank()) return@withContext Result.failure(IllegalStateException("尚未选择 Gadgetbridge.db"))
        runCatching {
            setState(context, mutableState.value.copy(importing = true, error = ""))
            importUri(context.applicationContext, Uri.parse(source))
        }.onFailure { error ->
            setState(
                context,
                mutableState.value.copy(
                    importing = false,
                    error = error.message ?: "Gadgetbridge 数据刷新失败，请重新选择文件",
                ),
            )
        }
    }

    fun disconnect(context: Context) {
        val uri = mutableState.value.sourceUri.takeIf(String::isNotBlank)?.let(Uri::parse)
        if (uri != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        setState(context, GadgetbridgeHealthState())
    }

    private fun importUri(context: Context, uri: Uri) {
        val cacheFile = File(context.cacheDir, CACHE_FILE)
        context.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use(input::copyTo)
        } ?: error("无法打开所选文件")
        require(cacheFile.length() > 100L) { "数据库文件为空或尚未完成导出" }
        val header = cacheFile.inputStream().use { stream ->
            ByteArray(16).also { stream.read(it) }.toString(Charsets.US_ASCII)
        }
        require(header.startsWith("SQLite format 3")) { "所选文件不是 Gadgetbridge SQLite 数据库" }

        val database = SQLiteDatabase.openDatabase(
            cacheFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        val parsed = database.use(::readDatabase)
        require(parsed.days.isNotEmpty()) {
            "数据库中没有识别到运动健康记录；请先在 Gadgetbridge 完成一次手环同步"
        }
        setState(
            context,
            mutableState.value.copy(
                sourceName = displayName(context, uri),
                tableName = parsed.tableName,
                lastImportedAt = Instant.now(),
                days = parsed.days,
                importing = false,
                error = "",
            ),
        )
    }

    private data class ParsedDatabase(
        val tableName: String,
        val days: List<GadgetbridgeDaySummary>,
    )

    private fun readDatabase(database: SQLiteDatabase): ParsedDatabase {
        val tables = buildList {
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val tableColumns = tables.associateWith { table -> columns(database, table) }
        val preferred = tables.firstOrNull { it.equals("HUAWEI_ACTIVITY_SAMPLE", ignoreCase = true) }
        val table = preferred ?: tables.firstOrNull { candidate ->
            val keys = tableColumns.getValue(candidate).keys
            "TIMESTAMP" in keys && keys.any {
                it in setOf("STEPS", "HEART_RATE", "SPO", "SPO2", "STRESS", "CALORIES", "DISTANCE")
            }
        } ?: error("没有找到可识别的 Gadgetbridge 活动数据表")

        val columns = tableColumns.getValue(table)
        val timestamp = columns["TIMESTAMP"] ?: error("活动表缺少时间字段")
        val quotedTable = quote(table)
        val quotedTimestamp = quote(timestamp)

        fun column(name: String): String? = columns[name]?.let(::quote)
        fun sum(name: String): String = column(name)?.let { value ->
            "CAST(SUM(CASE WHEN $value > 0 THEN $value ELSE 0 END) AS INTEGER)"
        } ?: "0"
        fun average(name: String, minimum: Int, maximum: Int): String = column(name)?.let { value ->
            "CAST(ROUND(AVG(CASE WHEN $value BETWEEN $minimum AND $maximum THEN $value END)) AS INTEGER)"
        } ?: "NULL"
        fun minimum(name: String, minimum: Int, maximum: Int): String = column(name)?.let { value ->
            "MIN(CASE WHEN $value BETWEEN $minimum AND $maximum THEN $value END)"
        } ?: "NULL"
        fun maximum(name: String, minimum: Int, maximum: Int): String = column(name)?.let { value ->
            "MAX(CASE WHEN $value BETWEEN $minimum AND $maximum THEN $value END)"
        } ?: "NULL"

        val spoColumnName = when {
            columns.containsKey("SPO") -> "SPO"
            columns.containsKey("SPO2") -> "SPO2"
            else -> ""
        }
        val sleepExpression = if (columns.containsKey("OTHER_TIMESTAMP") && columns.containsKey("RAW_KIND")) {
            val other = quote(columns.getValue("OTHER_TIMESTAMP"))
            val kind = quote(columns.getValue("RAW_KIND"))
            "CAST(SUM(CASE WHEN $kind = 6 AND ABS($other - $quotedTimestamp) BETWEEN 60 AND 86400 " +
                "THEN ABS($other - $quotedTimestamp) ELSE 0 END) / 60 AS INTEGER)"
        } else {
            "NULL"
        }

        val sql = """
            SELECT
                date($quotedTimestamp, 'unixepoch', 'localtime') AS day,
                ${sum("STEPS")} AS steps,
                ${average("HEART_RATE", 25, 240)} AS average_hr,
                ${minimum("HEART_RATE", 25, 240)} AS minimum_hr,
                ${maximum("HEART_RATE", 25, 240)} AS maximum_hr,
                $sleepExpression AS sleep_minutes,
                ${if (spoColumnName.isBlank()) "NULL" else average(spoColumnName, 50, 100)} AS spo2,
                ${average("STRESS", 1, 100)} AS stress,
                ${sum("CALORIES")} AS calories,
                ${sum("DISTANCE")} AS distance
            FROM $quotedTable
            WHERE $quotedTimestamp >= strftime('%s', 'now', '-60 days')
            GROUP BY day
            HAVING day IS NOT NULL
            ORDER BY day ASC
        """.trimIndent()

        val days = database.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val date = runCatching { LocalDate.parse(cursor.string("day")) }.getOrNull() ?: continue
                    add(
                        GadgetbridgeDaySummary(
                            date = date,
                            steps = cursor.intOrZero("steps"),
                            averageHeartRate = cursor.nullableInt("average_hr"),
                            minimumHeartRate = cursor.nullableInt("minimum_hr"),
                            maximumHeartRate = cursor.nullableInt("maximum_hr"),
                            sleepMinutes = cursor.nullableInt("sleep_minutes")?.takeIf { it > 0 },
                            spo2 = cursor.nullableInt("spo2"),
                            stress = cursor.nullableInt("stress"),
                            calories = cursor.nullableInt("calories")?.takeIf { it > 0 },
                            distanceMeters = cursor.nullableInt("distance")?.takeIf { it > 0 },
                        ),
                    )
                }
            }
        }
        return ParsedDatabase(table, days)
    }

    private fun columns(database: SQLiteDatabase, table: String): Map<String, String> = buildMap {
        database.rawQuery("PRAGMA table_info(${quote(table)})", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                put(name.uppercase(Locale.ROOT), name)
            }
        }
    }

    private fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    private fun displayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment ?: "Gadgetbridge.db" }
    }

    private fun setState(context: Context, next: GadgetbridgeHealthState) {
        mutableState.value = next
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, encode(next).toString())
            .apply()
    }

    private fun encode(state: GadgetbridgeHealthState): JSONObject = JSONObject().apply {
        put("sourceUri", state.sourceUri)
        put("sourceName", state.sourceName)
        put("tableName", state.tableName)
        put("lastImportedAt", state.lastImportedAt?.toString().orEmpty())
        put("days", JSONArray().apply {
            state.days.forEach { day ->
                put(JSONObject().apply {
                    put("date", day.date.toString())
                    put("steps", day.steps)
                    putNullable("averageHeartRate", day.averageHeartRate)
                    putNullable("minimumHeartRate", day.minimumHeartRate)
                    putNullable("maximumHeartRate", day.maximumHeartRate)
                    putNullable("sleepMinutes", day.sleepMinutes)
                    putNullable("spo2", day.spo2)
                    putNullable("stress", day.stress)
                    putNullable("calories", day.calories)
                    putNullable("distanceMeters", day.distanceMeters)
                })
            }
        })
    }

    private fun decode(raw: String?): GadgetbridgeHealthState = runCatching {
        val root = JSONObject(raw ?: "{}")
        val array = root.optJSONArray("days") ?: JSONArray()
        val days = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val date = runCatching { LocalDate.parse(item.optString("date")) }.getOrNull() ?: continue
                add(
                    GadgetbridgeDaySummary(
                        date = date,
                        steps = item.optInt("steps"),
                        averageHeartRate = item.nullableInt("averageHeartRate"),
                        minimumHeartRate = item.nullableInt("minimumHeartRate"),
                        maximumHeartRate = item.nullableInt("maximumHeartRate"),
                        sleepMinutes = item.nullableInt("sleepMinutes"),
                        spo2 = item.nullableInt("spo2"),
                        stress = item.nullableInt("stress"),
                        calories = item.nullableInt("calories"),
                        distanceMeters = item.nullableInt("distanceMeters"),
                    ),
                )
            }
        }.sortedBy { it.date }
        GadgetbridgeHealthState(
            sourceUri = root.optString("sourceUri"),
            sourceName = root.optString("sourceName"),
            tableName = root.optString("tableName"),
            lastImportedAt = root.optString("lastImportedAt").takeIf(String::isNotBlank)?.let(Instant::parse),
            days = days,
        )
    }.getOrDefault(GadgetbridgeHealthState())
}

internal object GadgetbridgeHealthRefreshScheduler {
    private const val WORK_NAME = "lulu-gadgetbridge-health-refresh"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<GadgetbridgeHealthRefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

internal class GadgetbridgeHealthRefreshWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        GadgetbridgeHealthStore.initialize(applicationContext)
        if (!GadgetbridgeHealthStore.state.value.connected) return Result.success()
        return GadgetbridgeHealthStore.refresh(applicationContext).fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount < 2) Result.retry() else Result.failure() },
        )
    }
}

private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.intOrZero(name: String): Int = nullableInt(name) ?: 0
private fun Cursor.nullableInt(name: String): Int? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getDouble(index).roundToInt()
}
private fun JSONObject.putNullable(name: String, value: Int?) {
    put(name, value ?: JSONObject.NULL)
}
private fun JSONObject.nullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)
