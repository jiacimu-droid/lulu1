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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal data class GadgetbridgeDaySummary(
    val date: LocalDate,
    val steps: Int = 0,
    val averageHeartRate: Int? = null,
    val minimumHeartRate: Int? = null,
    val maximumHeartRate: Int? = null,
    val restingHeartRate: Int? = null,
    val sleepMinutes: Int? = null,
    val sleepStartEpochSeconds: Long? = null,
    val sleepEndEpochSeconds: Long? = null,
    val deepSleepMinutes: Int? = null,
    val lightSleepMinutes: Int? = null,
    val remSleepMinutes: Int? = null,
    val awakeSleepMinutes: Int? = null,
    val sleepScore: Int? = null,
    val spo2: Int? = null,
    val stress: Int? = null,
    val hrvMillis: Int? = null,
    val respiratoryRate: Float? = null,
    val skinTemperatureCelsius: Float? = null,
    val bodyEnergy: Int? = null,
    val systolicBloodPressure: Int? = null,
    val diastolicBloodPressure: Int? = null,
    val calories: Int? = null,
    val distanceMeters: Int? = null,
    val activeMinutes: Int? = null,
    val floorsClimbed: Int? = null,
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
    private const val MAX_SUPPLEMENTAL_ROWS = 50_000
    private const val MAX_HUAWEI_SLEEP_ROWS = 20_000

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
            setState(
                context,
                mutableState.value.copy(
                    sourceUri = uri.toString(),
                    sourceName = displayName(context, uri),
                    importing = true,
                    error = "",
                ),
            )
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
                    error = error.message ?: "Gadgetbridge 数据刷新失败，请在设置中重新授权文件",
                ),
            )
        }
    }

    fun disconnect(context: Context) {
        val uri = mutableState.value.sourceUri.takeIf(String::isNotBlank)?.let(Uri::parse)
        if (uri != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
            ).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val tableColumns = tables.associateWith { table -> columns(database, table) }
        val preferred = tables.firstOrNull { it.equals("HUAWEI_ACTIVITY_SAMPLE", ignoreCase = true) }
        val activityTable = preferred ?: tables.firstOrNull { candidate ->
            val keys = tableColumns.getValue(candidate).keys
            "TIMESTAMP" in keys && keys.any {
                it in setOf("STEPS", "HEART_RATE", "SPO", "SPO2", "STRESS", "CALORIES", "DISTANCE")
            }
        }

        val days = linkedMapOf<LocalDate, GadgetbridgeDaySummary>()
        val usedTables = linkedSetOf<String>()
        if (activityTable != null) {
            usedTables += activityTable
            readActivitySummary(database, activityTable, tableColumns.getValue(activityTable)).forEach { day ->
                days[day.date] = day
            }
            readHuaweiSleepAggregates(database, activityTable, tableColumns.getValue(activityTable)).forEach { (date, sleep) ->
                val current = days[date] ?: GadgetbridgeDaySummary(date)
                days[date] = current.copy(
                    sleepMinutes = maxOf(current.sleepMinutes ?: 0, sleep.sleepMinutes).takeIf { it > 0 },
                    sleepStartEpochSeconds = sleep.primaryStartEpochSeconds ?: current.sleepStartEpochSeconds,
                    sleepEndEpochSeconds = sleep.primaryEndEpochSeconds ?: current.sleepEndEpochSeconds,
                )
            }
        }

        // Gadgetbridge has used dedicated sleep/stat tables on several device families, including Huawei.
        // Read those tables after the generic activity table so richer sleep stages/scores can win.
        val orderedSupplementalTables = tables
            .filterNot { it == activityTable }
            .sortedWith(compareByDescending<String> { name ->
                val upper = name.uppercase(Locale.ROOT)
                when {
                    "SLEEP" in upper && ("STATS" in upper || "SUMMARY" in upper) -> 4
                    "SLEEP" in upper -> 3
                    "HEALTH" in upper || "VITAL" in upper -> 2
                    "HUAWEI" in upper -> 1
                    else -> 0
                }
            }.thenBy { it })

        orderedSupplementalTables.forEach { table ->
            val cols = tableColumns.getValue(table)
            if (!couldContainHealthData(table, cols)) return@forEach
            val changed = readSupplementalTable(database, table, cols, days)
            if (changed) usedTables += table
        }

        val cutoff = LocalDate.now().minusDays(180)
        val normalized = days.values
            .filter { !it.date.isBefore(cutoff) && !it.date.isAfter(LocalDate.now().plusDays(1)) }
            .map(::normalizeDay)
            .filter(::hasMeaningfulData)
            .sortedBy(GadgetbridgeDaySummary::date)

        if (normalized.isEmpty()) error("没有找到可识别的 Gadgetbridge 健康数据表")
        return ParsedDatabase(
            tableName = usedTables.take(6).joinToString("、").ifBlank { activityTable ?: "健康数据" },
            days = normalized,
        )
    }

    private fun readActivitySummary(
        database: SQLiteDatabase,
        table: String,
        columns: Map<String, String>,
    ): List<GadgetbridgeDaySummary> {
        val timestamp = columns["TIMESTAMP"] ?: return emptyList()
        val quotedTable = quote(table)
        val timestampEpoch = normalizedEpochSql(quote(timestamp))

        fun picked(vararg names: String): String? = names.firstNotNullOfOrNull { columns[it]?.let(::quote) }
        fun sum(vararg names: String): String = picked(*names)?.let { value ->
            "CAST(SUM(CASE WHEN $value > 0 THEN $value ELSE 0 END) AS INTEGER)"
        } ?: "NULL"
        fun average(minimum: Double, maximum: Double, vararg names: String): String = picked(*names)?.let { value ->
            "ROUND(AVG(CASE WHEN $value BETWEEN $minimum AND $maximum THEN $value END), 2)"
        } ?: "NULL"
        fun minimum(minimum: Int, maximum: Int, vararg names: String): String = picked(*names)?.let { value ->
            "MIN(CASE WHEN $value BETWEEN $minimum AND $maximum THEN $value END)"
        } ?: "NULL"
        fun maximum(minimum: Int, maximum: Int, vararg names: String): String = picked(*names)?.let { value ->
            "MAX(CASE WHEN $value BETWEEN $minimum AND $maximum THEN $value END)"
        } ?: "NULL"

        val sql = """
            SELECT
                date($timestampEpoch, 'unixepoch', 'localtime') AS day,
                ${sum("STEPS", "STEP_COUNT")} AS steps,
                ${average(25.0, 240.0, "HEART_RATE", "HEARTRATE", "BPM")} AS average_hr,
                ${minimum(25, 240, "HEART_RATE", "HEARTRATE", "BPM")} AS minimum_hr,
                ${maximum(25, 240, "HEART_RATE", "HEARTRATE", "BPM")} AS maximum_hr,
                ${average(25.0, 180.0, "RESTING_HEART_RATE", "RESTING_HR", "RHR")} AS resting_hr,
                ${average(50.0, 100.0, "SPO", "SPO2", "OXYGEN_SATURATION")} AS spo2,
                ${average(1.0, 100.0, "STRESS", "STRESS_LEVEL")} AS stress,
                ${average(1.0, 350.0, "HRV", "HRV_MS", "RMSSD", "SDNN")} AS hrv,
                ${average(5.0, 60.0, "RESPIRATORY_RATE", "RESPIRATION_RATE", "BREATHING_RATE")} AS respiratory_rate,
                ${average(20.0, 45.0, "SKIN_TEMPERATURE", "SKIN_TEMP", "TEMPERATURE")} AS skin_temperature,
                ${average(0.0, 100.0, "BODY_ENERGY", "BODY_BATTERY", "ENERGY_LEVEL")} AS body_energy,
                ${average(60.0, 260.0, "SYSTOLIC", "SYSTOLIC_BP", "BLOOD_PRESSURE_SYSTOLIC")} AS systolic,
                ${average(30.0, 180.0, "DIASTOLIC", "DIASTOLIC_BP", "BLOOD_PRESSURE_DIASTOLIC")} AS diastolic,
                ${sum("CALORIES", "ACTIVE_CALORIES")} AS calories,
                ${sum("DISTANCE", "DISTANCE_METERS")} AS distance,
                ${sum("ACTIVE_MINUTES", "ACTIVE_TIME_MINUTES")} AS active_minutes,
                ${sum("FLOORS", "FLOORS_CLIMBED")} AS floors
            FROM $quotedTable
            WHERE $timestampEpoch >= CAST(strftime('%s', 'now', '-180 days') AS INTEGER)
            GROUP BY day
            HAVING day IS NOT NULL
            ORDER BY day ASC
        """.trimIndent()

        return database.rawQuery(sql, null).use { cursor ->
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
                            restingHeartRate = cursor.nullableInt("resting_hr"),
                            spo2 = cursor.nullableInt("spo2"),
                            stress = cursor.nullableInt("stress"),
                            hrvMillis = cursor.nullableInt("hrv"),
                            respiratoryRate = cursor.nullableFloat("respiratory_rate"),
                            skinTemperatureCelsius = cursor.nullableFloat("skin_temperature"),
                            bodyEnergy = cursor.nullableInt("body_energy"),
                            systolicBloodPressure = cursor.nullableInt("systolic"),
                            diastolicBloodPressure = cursor.nullableInt("diastolic"),
                            calories = cursor.nullableInt("calories")?.takeIf { it > 0 },
                            distanceMeters = cursor.nullableInt("distance")?.takeIf { it > 0 },
                            activeMinutes = cursor.nullableInt("active_minutes")?.takeIf { it > 0 },
                            floorsClimbed = cursor.nullableInt("floors")?.takeIf { it > 0 },
                        ),
                    )
                }
            }
        }
    }

    private data class SleepInterval(val start: Long, val end: Long) {
        val minutes: Int get() = ((end - start) / 60L).toInt().coerceAtLeast(0)
    }

    private data class SleepSession(
        val start: Long,
        val end: Long,
        val sleepMinutes: Int,
    )

    private data class HuaweiSleepAggregate(
        val sleepMinutes: Int,
        val primaryStartEpochSeconds: Long?,
        val primaryEndEpochSeconds: Long?,
    )

    /**
     * Huawei's legacy/simple TruSleep data is stored as multiple RAW_KIND=6 intervals in
     * HUAWEI_ACTIVITY_SAMPLE. Those rows are not one single sleep window: they need to be
     * accumulated and stitched into sessions. A long daytime gap starts a separate nap/session.
     */
    private fun readHuaweiSleepAggregates(
        database: SQLiteDatabase,
        table: String,
        columns: Map<String, String>,
    ): Map<LocalDate, HuaweiSleepAggregate> {
        val timestamp = columns["TIMESTAMP"] ?: return emptyMap()
        val other = columns["OTHER_TIMESTAMP"] ?: return emptyMap()
        val kind = columns["RAW_KIND"] ?: return emptyMap()
        val intervals = mutableListOf<SleepInterval>()
        val sql = "SELECT ${quote(timestamp)}, ${quote(other)} FROM ${quote(table)} " +
            "WHERE ${quote(kind)} = 6 ORDER BY ${quote(timestamp)} DESC LIMIT $MAX_HUAWEI_SLEEP_ROWS"
        database.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                val first = normalizeEpochSeconds(cursor.getLong(0)) ?: continue
                val second = normalizeEpochSeconds(cursor.getLong(1)) ?: continue
                val start = minOf(first, second)
                val end = maxOf(first, second)
                val minutes = ((end - start) / 60L).toInt()
                if (minutes !in 1..1_200) continue
                val date = Instant.ofEpochSecond(end).atZone(ZoneId.systemDefault()).toLocalDate()
                if (date.isBefore(LocalDate.now().minusDays(190)) || date.isAfter(LocalDate.now().plusDays(1))) continue
                intervals += SleepInterval(start, end)
            }
        }
        if (intervals.isEmpty()) return emptyMap()

        val sorted = intervals.distinct().sortedBy(SleepInterval::start)
        val sessions = mutableListOf<SleepSession>()
        var currentStart = sorted.first().start
        var currentEnd = sorted.first().end
        var currentSleepMinutes = sorted.first().minutes
        sorted.drop(1).forEach { interval ->
            val gapSeconds = interval.start - currentEnd
            if (gapSeconds <= 90 * 60L) {
                currentEnd = maxOf(currentEnd, interval.end)
                currentSleepMinutes += interval.minutes
            } else {
                sessions += SleepSession(currentStart, currentEnd, currentSleepMinutes.coerceAtMost(1_200))
                currentStart = interval.start
                currentEnd = interval.end
                currentSleepMinutes = interval.minutes
            }
        }
        sessions += SleepSession(currentStart, currentEnd, currentSleepMinutes.coerceAtMost(1_200))

        return sessions
            .filter { session -> session.sleepMinutes > 0 && session.end > session.start }
            .groupBy { session -> Instant.ofEpochSecond(session.end).atZone(ZoneId.systemDefault()).toLocalDate() }
            .mapValues { (_, dateSessions) ->
                val primary = dateSessions.maxByOrNull { it.end - it.start }
                HuaweiSleepAggregate(
                    sleepMinutes = dateSessions.sumOf { it.sleepMinutes }.coerceIn(1, 1_200),
                    primaryStartEpochSeconds = primary?.start,
                    primaryEndEpochSeconds = primary?.end,
                )
            }
    }

    private fun couldContainHealthData(table: String, columns: Map<String, String>): Boolean {
        val tableName = table.uppercase(Locale.ROOT)
        if (
            "SLEEP" in tableName || "HEALTH" in tableName || "SUMMARY" in tableName ||
            "VITAL" in tableName || "TRUSLEEP" in tableName
        ) return true
        val recognized = setOf(
            "SLEEP", "BED", "WAKE", "STAGE", "DEEP", "LIGHT", "REM", "AWAKE",
            "RESTING_HEART_RATE", "HRV", "RMSSD", "RESPIRATORY_RATE", "SKIN_TEMPERATURE",
            "BODY_ENERGY", "BODY_BATTERY", "SYSTOLIC", "DIASTOLIC", "ACTIVE_MINUTES", "FLOORS",
        )
        return columns.keys.any { key -> recognized.any(key::contains) }
    }

    private fun readSupplementalTable(
        database: SQLiteDatabase,
        table: String,
        columns: Map<String, String>,
        days: MutableMap<LocalDate, GadgetbridgeDaySummary>,
    ): Boolean {
        fun find(vararg candidates: String): String? = candidates.firstNotNullOfOrNull { candidate ->
            columns[candidate] ?: columns.entries.firstOrNull { (upper, _) -> upper.contains(candidate) }?.value
        }

        val dateColumn = find("SLEEP_DATE", "LOCAL_DATE", "SUMMARY_DATE", "CALENDAR_DATE", "DATE", "DAY_START", "DAY")
        val timestampColumn = find("TIMESTAMP", "SLEEP_TIMESTAMP", "TIME")
        val startColumn = find(
            "SLEEP_START_TIMESTAMP", "SLEEP_START", "SLEEP_BEGIN_TIME", "SLEEP_BEGIN",
            "FALL_ASLEEP_TIME", "BEDTIME", "BED_TIME", "START_TIMESTAMP", "START_TIME", "BEGIN_TIME",
        )
        val endColumn = find(
            "SLEEP_END_TIMESTAMP", "SLEEP_END", "SLEEP_FINISH", "WAKEUP_TIME", "WAKE_UP_TIME",
            "WAKE_TIME", "END_TIMESTAMP", "END_TIME", "OTHER_TIMESTAMP",
        )
        val durationColumn = find(
            "SLEEP_DURATION_MINUTES", "TOTAL_SLEEP_MINUTES", "TOTAL_SLEEP_TIME", "TOTAL_SLEEP",
            "SLEEP_DURATION", "SLEEP_MINUTES", "DURATION_MINUTES", "DURATION",
        )
        val stageColumn = find("SLEEP_STAGE", "SLEEP_LEVEL", "STAGE", "SLEEP_TYPE")
        val deepColumn = find("DEEP_SLEEP_MINUTES", "DEEP_SLEEP_DURATION", "DEEP_SLEEP_TIME", "DEEP_DURATION", "DEEP_TIME", "DEEP_SLEEP")
        val lightColumn = find("LIGHT_SLEEP_MINUTES", "LIGHT_SLEEP_DURATION", "LIGHT_SLEEP_TIME", "LIGHT_DURATION", "LIGHT_TIME", "LIGHT_SLEEP")
        val remColumn = find("REM_SLEEP_MINUTES", "REM_SLEEP_DURATION", "REM_SLEEP_TIME", "REM_DURATION", "REM_TIME", "REM_SLEEP")
        val awakeColumn = find("AWAKE_MINUTES", "AWAKE_DURATION", "AWAKE_TIME", "WAKE_DURATION", "AWAKE_SLEEP")
        val sleepScoreColumn = find("SLEEP_SCORE", "SLEEPSCORE", "SCORE")
        val restingHeartRateColumn = find("RESTING_HEART_RATE", "RESTING_HR", "RHR")
        val hrvColumn = find("HRV_MS", "HRV", "RMSSD", "SDNN")
        val respiratoryColumn = find("RESPIRATORY_RATE", "RESPIRATION_RATE", "BREATHING_RATE")
        val temperatureColumn = find("SKIN_TEMPERATURE", "SKIN_TEMP")
        val bodyEnergyColumn = find("BODY_ENERGY", "BODY_BATTERY", "ENERGY_LEVEL")
        val systolicColumn = find("SYSTOLIC_BP", "SYSTOLIC", "BLOOD_PRESSURE_SYSTOLIC")
        val diastolicColumn = find("DIASTOLIC_BP", "DIASTOLIC", "BLOOD_PRESSURE_DIASTOLIC")
        val activeMinutesColumn = find("ACTIVE_MINUTES", "ACTIVE_TIME_MINUTES")
        val floorsColumn = find("FLOORS_CLIMBED", "FLOORS")

        val usefulColumns = listOfNotNull(
            startColumn, endColumn, durationColumn, stageColumn, deepColumn, lightColumn, remColumn,
            awakeColumn, sleepScoreColumn, restingHeartRateColumn, hrvColumn, respiratoryColumn,
            temperatureColumn, bodyEnergyColumn, systolicColumn, diastolicColumn, activeMinutesColumn, floorsColumn,
        ).distinct()
        if (usefulColumns.isEmpty() || dateColumn == null && timestampColumn == null && startColumn == null && endColumn == null) {
            return false
        }

        // Important: do not read the first 10k physical rows. On long-running Gadgetbridge databases
        // those are often old records and the newest sleep rows are far beyond that range.
        val orderColumn = endColumn ?: startColumn ?: timestampColumn ?: dateColumn
        val sql = buildString {
            append("SELECT * FROM ${quote(table)}")
            if (orderColumn != null) append(" ORDER BY ${quote(orderColumn)} DESC")
            append(" LIMIT $MAX_SUPPLEMENTAL_ROWS")
        }
        val tableUpper = table.uppercase(Locale.ROOT)
        val summaryLike = "SUMMARY" in tableUpper || "STATS" in tableUpper || "SESSION" in tableUpper

        var changed = false
        database.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                val startEpoch = startColumn?.let { cursor.epochSeconds(it) }
                val endEpoch = endColumn?.let { cursor.epochSeconds(it) }
                val date = dateColumn?.let { cursor.localDate(it) }
                    ?: endEpoch?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                    ?: startEpoch?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                    ?: timestampColumn?.let { cursor.localDate(it) }
                    ?: continue
                if (date.isBefore(LocalDate.now().minusDays(180)) || date.isAfter(LocalDate.now().plusDays(1))) continue

                val current = days[date] ?: GadgetbridgeDaySummary(date)
                var next = current
                val explicitDuration = durationColumn?.let { column ->
                    cursor.number(column)?.let { durationToMinutes(it, column) }
                }
                val windowMinutes = if (startEpoch != null && endEpoch != null && endEpoch > startEpoch) {
                    ((endEpoch - startEpoch) / 60L).toInt().takeIf { it in 1..1_200 }
                } else null
                val sleepMinutes = explicitDuration?.takeIf { it in 1..1_200 } ?: windowMinutes
                if (sleepMinutes != null) {
                    next = next.copy(sleepMinutes = maxOf(next.sleepMinutes ?: 0, sleepMinutes))
                }
                if (summaryLike || stageColumn == null) {
                    if (startEpoch != null) next = next.copy(sleepStartEpochSeconds = earliest(next.sleepStartEpochSeconds, startEpoch))
                    if (endEpoch != null) next = next.copy(sleepEndEpochSeconds = latest(next.sleepEndEpochSeconds, endEpoch))
                }

                deepColumn?.let { column -> cursor.number(column)?.let { value ->
                    next = next.copy(deepSleepMinutes = mergeDuration(next.deepSleepMinutes, durationToMinutes(value, column)))
                } }
                lightColumn?.let { column -> cursor.number(column)?.let { value ->
                    next = next.copy(lightSleepMinutes = mergeDuration(next.lightSleepMinutes, durationToMinutes(value, column)))
                } }
                remColumn?.let { column -> cursor.number(column)?.let { value ->
                    next = next.copy(remSleepMinutes = mergeDuration(next.remSleepMinutes, durationToMinutes(value, column)))
                } }
                awakeColumn?.let { column -> cursor.number(column)?.let { value ->
                    next = next.copy(awakeSleepMinutes = mergeDuration(next.awakeSleepMinutes, durationToMinutes(value, column)))
                } }

                val stage = stageColumn?.let { cursor.text(it).uppercase(Locale.ROOT) }.orEmpty()
                if (stage.isNotBlank() && sleepMinutes != null) {
                    next = when {
                        "DEEP" in stage || "深睡" in stage -> next.copy(deepSleepMinutes = (next.deepSleepMinutes ?: 0) + sleepMinutes)
                        "LIGHT" in stage || "浅睡" in stage -> next.copy(lightSleepMinutes = (next.lightSleepMinutes ?: 0) + sleepMinutes)
                        "REM" in stage || "RAPID" in stage || "快速眼动" in stage -> next.copy(remSleepMinutes = (next.remSleepMinutes ?: 0) + sleepMinutes)
                        "AWAKE" in stage || "WAKE" in stage || "清醒" in stage -> next.copy(awakeSleepMinutes = (next.awakeSleepMinutes ?: 0) + sleepMinutes)
                        else -> next
                    }
                }

                sleepScoreColumn?.let { next = next.copy(sleepScore = cursor.validInt(it, 0, 100) ?: next.sleepScore) }
                restingHeartRateColumn?.let { next = next.copy(restingHeartRate = cursor.validInt(it, 25, 180) ?: next.restingHeartRate) }
                hrvColumn?.let { next = next.copy(hrvMillis = cursor.validInt(it, 1, 350) ?: next.hrvMillis) }
                respiratoryColumn?.let { next = next.copy(respiratoryRate = cursor.validFloat(it, 5f, 60f) ?: next.respiratoryRate) }
                temperatureColumn?.let { next = next.copy(skinTemperatureCelsius = cursor.validFloat(it, 20f, 45f) ?: next.skinTemperatureCelsius) }
                bodyEnergyColumn?.let { next = next.copy(bodyEnergy = cursor.validInt(it, 0, 100) ?: next.bodyEnergy) }
                systolicColumn?.let { next = next.copy(systolicBloodPressure = cursor.validInt(it, 60, 260) ?: next.systolicBloodPressure) }
                diastolicColumn?.let { next = next.copy(diastolicBloodPressure = cursor.validInt(it, 30, 180) ?: next.diastolicBloodPressure) }
                activeMinutesColumn?.let { next = next.copy(activeMinutes = cursor.validInt(it, 0, 1_440) ?: next.activeMinutes) }
                floorsColumn?.let { next = next.copy(floorsClimbed = cursor.validInt(it, 0, 1_000) ?: next.floorsClimbed) }

                if (next != current) {
                    days[date] = next
                    changed = true
                }
            }
        }
        return changed
    }

    private fun normalizeDay(day: GadgetbridgeDaySummary): GadgetbridgeDaySummary {
        val stageSleep = listOfNotNull(day.deepSleepMinutes, day.lightSleepMinutes, day.remSleepMinutes).sum()
        val windowSleep = if (
            day.sleepStartEpochSeconds != null && day.sleepEndEpochSeconds != null &&
            day.sleepEndEpochSeconds > day.sleepStartEpochSeconds
        ) {
            ((day.sleepEndEpochSeconds - day.sleepStartEpochSeconds) / 60L).toInt().takeIf { it in 1..1_200 }
        } else null

        // When Gadgetbridge has already decoded deep/light/REM, that sum is the closest equivalent to
        // the sleep total shown by its own UI (awake time is intentionally excluded).
        val total = stageSleep.takeIf { it > 0 } ?: day.sleepMinutes ?: windowSleep
        return day.copy(
            sleepMinutes = total?.coerceIn(1, 1_200),
            deepSleepMinutes = day.deepSleepMinutes?.coerceIn(0, 1_000),
            lightSleepMinutes = day.lightSleepMinutes?.coerceIn(0, 1_000),
            remSleepMinutes = day.remSleepMinutes?.coerceIn(0, 1_000),
            awakeSleepMinutes = day.awakeSleepMinutes?.coerceIn(0, 1_000),
        )
    }

    private fun hasMeaningfulData(day: GadgetbridgeDaySummary): Boolean =
        day.steps > 0 || listOfNotNull(
            day.averageHeartRate, day.sleepMinutes, day.spo2, day.stress, day.calories,
            day.deepSleepMinutes, day.lightSleepMinutes, day.remSleepMinutes, day.awakeSleepMinutes,
            day.hrvMillis, day.bodyEnergy, day.sleepScore,
        ).isNotEmpty() || day.distanceMeters != null || day.respiratoryRate != null || day.skinTemperatureCelsius != null

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

    private fun normalizedEpochSql(expression: String): String =
        "(CASE WHEN $expression > 10000000000000 THEN $expression / 1000000 " +
            "WHEN $expression > 10000000000 THEN $expression / 1000 ELSE $expression END)"

    private fun displayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
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
                    putNullable("restingHeartRate", day.restingHeartRate)
                    putNullable("sleepMinutes", day.sleepMinutes)
                    putNullable("sleepStartEpochSeconds", day.sleepStartEpochSeconds)
                    putNullable("sleepEndEpochSeconds", day.sleepEndEpochSeconds)
                    putNullable("deepSleepMinutes", day.deepSleepMinutes)
                    putNullable("lightSleepMinutes", day.lightSleepMinutes)
                    putNullable("remSleepMinutes", day.remSleepMinutes)
                    putNullable("awakeSleepMinutes", day.awakeSleepMinutes)
                    putNullable("sleepScore", day.sleepScore)
                    putNullable("spo2", day.spo2)
                    putNullable("stress", day.stress)
                    putNullable("hrvMillis", day.hrvMillis)
                    putNullable("respiratoryRate", day.respiratoryRate)
                    putNullable("skinTemperatureCelsius", day.skinTemperatureCelsius)
                    putNullable("bodyEnergy", day.bodyEnergy)
                    putNullable("systolicBloodPressure", day.systolicBloodPressure)
                    putNullable("diastolicBloodPressure", day.diastolicBloodPressure)
                    putNullable("calories", day.calories)
                    putNullable("distanceMeters", day.distanceMeters)
                    putNullable("activeMinutes", day.activeMinutes)
                    putNullable("floorsClimbed", day.floorsClimbed)
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
                        restingHeartRate = item.nullableInt("restingHeartRate"),
                        sleepMinutes = item.nullableInt("sleepMinutes"),
                        sleepStartEpochSeconds = item.nullableLong("sleepStartEpochSeconds"),
                        sleepEndEpochSeconds = item.nullableLong("sleepEndEpochSeconds"),
                        deepSleepMinutes = item.nullableInt("deepSleepMinutes"),
                        lightSleepMinutes = item.nullableInt("lightSleepMinutes"),
                        remSleepMinutes = item.nullableInt("remSleepMinutes"),
                        awakeSleepMinutes = item.nullableInt("awakeSleepMinutes"),
                        sleepScore = item.nullableInt("sleepScore"),
                        spo2 = item.nullableInt("spo2"),
                        stress = item.nullableInt("stress"),
                        hrvMillis = item.nullableInt("hrvMillis"),
                        respiratoryRate = item.nullableFloat("respiratoryRate"),
                        skinTemperatureCelsius = item.nullableFloat("skinTemperatureCelsius"),
                        bodyEnergy = item.nullableInt("bodyEnergy"),
                        systolicBloodPressure = item.nullableInt("systolicBloodPressure"),
                        diastolicBloodPressure = item.nullableInt("diastolicBloodPressure"),
                        calories = item.nullableInt("calories"),
                        distanceMeters = item.nullableInt("distanceMeters"),
                        activeMinutes = item.nullableInt("activeMinutes"),
                        floorsClimbed = item.nullableInt("floorsClimbed"),
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
private fun Cursor.nullableFloat(name: String): Float? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getDouble(index).toFloat()
}
private fun Cursor.number(column: String): Double? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    return runCatching { getDouble(index) }.getOrNull()
}
private fun Cursor.text(column: String): String {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) "" else getString(index).orEmpty()
}
private fun Cursor.validInt(column: String, minimum: Int, maximum: Int): Int? =
    number(column)?.roundToInt()?.takeIf { it in minimum..maximum }
private fun Cursor.validFloat(column: String, minimum: Float, maximum: Float): Float? =
    number(column)?.toFloat()?.takeIf { it in minimum..maximum }
private fun Cursor.epochSeconds(column: String): Long? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    return normalizeEpochSeconds(runCatching { getLong(index) }.getOrNull())
}
private fun Cursor.localDate(column: String): LocalDate? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    val text = runCatching { getString(index) }.getOrNull().orEmpty().trim()
    if (text.isNotBlank()) {
        runCatching { LocalDate.parse(text.take(10)) }.getOrNull()?.let { return it }
        runCatching { LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()?.let { return it }
    }
    val epoch = runCatching { getLong(index) }.getOrNull()?.let(::normalizeEpochSeconds) ?: return null
    return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate()
}
private fun normalizeEpochSeconds(value: Long?): Long? {
    val raw = value ?: return null
    if (raw <= 0L) return null
    return when {
        raw > 10_000_000_000_000L -> raw / 1_000_000L
        raw > 10_000_000_000L -> raw / 1_000L
        raw > 100_000_000L -> raw
        else -> null
    }
}
private fun durationToMinutes(value: Double, column: String): Int {
    val upper = column.uppercase(Locale.ROOT)
    val minutes = when {
        "MILLI" in upper -> value / 60_000.0
        "SECOND" in upper || "SEC" in upper -> value / 60.0
        "HOUR" in upper -> value * 60.0
        value > 100_000.0 -> value / 60_000.0
        value > 1_440.0 -> value / 60.0
        else -> value
    }
    return minutes.roundToInt().coerceAtLeast(0)
}
private fun mergeDuration(current: Int?, incoming: Int): Int? =
    incoming.takeIf { it in 0..1_200 }?.let { value -> maxOf(current ?: 0, value) } ?: current
private fun earliest(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> minOf(first, second)
}
private fun latest(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}
private fun JSONObject.putNullable(name: String, value: Int?) { put(name, value ?: JSONObject.NULL) }
private fun JSONObject.putNullable(name: String, value: Long?) { put(name, value ?: JSONObject.NULL) }
private fun JSONObject.putNullable(name: String, value: Float?) { put(name, value ?: JSONObject.NULL) }
private fun JSONObject.nullableInt(name: String): Int? = if (!has(name) || isNull(name)) null else optInt(name)
private fun JSONObject.nullableLong(name: String): Long? = if (!has(name) || isNull(name)) null else optLong(name)
private fun JSONObject.nullableFloat(name: String): Float? = if (!has(name) || isNull(name)) null else optDouble(name).toFloat()
