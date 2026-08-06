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
                    error = error.message ?: "Gadgetbridge 数据刷新失败，请在设置中重新授权文件",
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
            readHuaweiSleepWindows(database, activityTable, tableColumns.getValue(activityTable)).forEach { (date, window) ->
                val current = days[date] ?: GadgetbridgeDaySummary(date)
                days[date] = current.copy(
                    sleepMinutes = maxOf(current.sleepMinutes ?: 0, window.minutes).takeIf { it > 0 },
                    sleepStartEpochSeconds = earliest(current.sleepStartEpochSeconds, window.startEpochSeconds),
                    sleepEndEpochSeconds = latest(current.sleepEndEpochSeconds, window.endEpochSeconds),
                )
            }
        }

        tables.forEach { table ->
            if (table == activityTable) return@forEach
            val columns = tableColumns.getValue(table)
            if (!couldContainHealthData(table, columns)) return@forEach
            val changed = readSupplementalTable(database, table, columns, days)
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
            tableName = usedTables.take(4).joinToString("、").ifBlank { activityTable ?: "健康数据" },
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
        val quotedTimestamp = quote(timestamp)

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
                ${sum("STEPS", "STEP_COUNT")} AS steps,
                ${average(25.0, 240.0, "HEART_RATE", "HEARTRATE", "BPM")} AS average_hr,
                ${minimum(25, 240, "HEART_RATE", "HEARTRATE", "BPM")} AS minimum_hr,
                ${maximum(25, 240, "HEART_RATE", "HEARTRATE", "BPM")} AS maximum_hr,
                ${average(25.0, 180.0, "RESTING_HEART_RATE", "RESTING_HR", "RHR")} AS resting_hr,
                $sleepExpression AS sleep_minutes,
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
            WHERE $quotedTimestamp >= strftime('%s', 'now', '-180 days')
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
                            sleepMinutes = cursor.nullableInt("sleep_minutes")?.takeIf { it > 0 },
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

    private data class SleepWindow(
        val startEpochSeconds: Long,
        val endEpochSeconds: Long,
        val minutes: Int,
    )

    private fun readHuaweiSleepWindows(
        database: SQLiteDatabase,
        table: String,
        columns: Map<String, String>,
    ): Map<LocalDate, SleepWindow> {
        val timestamp = columns["TIMESTAMP"] ?: return emptyMap()
        val other = columns["OTHER_TIMESTAMP"] ?: return emptyMap()
        val kind = columns["RAW_KIND"] ?: return emptyMap()
        val result = mutableMapOf<LocalDate, SleepWindow>()
        val sql = "SELECT ${quote(timestamp)}, ${quote(other)} FROM ${quote(table)} " +
            "WHERE ${quote(kind)} = 6 ORDER BY ${quote(timestamp)} DESC LIMIT 3000"
        database.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                val first = normalizeEpochSeconds(cursor.getLong(0)) ?: continue
                val second = normalizeEpochSeconds(cursor.getLong(1)) ?: continue
                val start = minOf(first, second)
                val end = maxOf(first, second)
                val minutes = ((end - start) / 60L).toInt()
                if (minutes !in 5..1_200) continue
                val date = Instant.ofEpochSecond(end).atZone(ZoneId.systemDefault()).toLocalDate()
                val existing = result[date]
                if (existing == null || minutes > existing.minutes) {
                    result[date] = SleepWindow(start, end, minutes)
                }
            }
        }
        return result
    }

    private fun couldContainHealthData(table: String, columns: Map<String, String>): Boolean {
        val tableName = table.uppercase(Locale.ROOT)
        if ("SLEEP" in tableName || "HEALTH" in tableName || "SUMMARY" in tableName || "VITAL" in tableName) return true
        val recognized = setOf(
            "SLEEP_START", "SLEEP_END", "DEEP_SLEEP", "LIGHT_SLEEP", "REM_SLEEP", "SLEEP_SCORE",
            "RESTING_HEART_RATE", "HRV", "RMSSD", "RESPIRATORY_RATE", "SKIN_TEMPERATURE",
            "BODY_ENERGY", "BODY_BATTERY", "SYSTOLIC", "DIASTOLIC", "ACTIVE_MINUTES", "FLOORS",
        )
        return columns.keys.any { key -> recognized.any { token -> key.contains(token) } }
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

        val dateColumn = find("LOCAL_DATE", "SUMMARY_DATE", "DATE", "DAY")
        val timestampColumn = find("TIMESTAMP", "TIME", "START_TIMESTAMP", "START_TIME")
        val startColumn = find("SLEEP_START_TIMESTAMP", "SLEEP_START", "START_TIMESTAMP", "START_TIME", "BEGIN_TIME")
        val endColumn = find("SLEEP_END_TIMESTAMP", "SLEEP_END", "END_TIMESTAMP", "END_TIME", "WAKE_TIME", "OTHER_TIMESTAMP")
        val durationColumn = find("SLEEP_DURATION_MINUTES", "SLEEP_MINUTES", "DURATION_MINUTES", "DURATION")
        val stageColumn = find("SLEEP_STAGE", "STAGE", "SLEEP_TYPE")
        val deepColumn = find("DEEP_SLEEP_MINUTES", "DEEP_SLEEP_DURATION", "DEEP_SLEEP")
        val lightColumn = find("LIGHT_SLEEP_MINUTES", "LIGHT_SLEEP_DURATION", "LIGHT_SLEEP")
        val remColumn = find("REM_SLEEP_MINUTES", "REM_SLEEP_DURATION", "REM_SLEEP")
        val awakeColumn = find("AWAKE_MINUTES", "AWAKE_DURATION", "AWAKE_TIME")
        val sleepScoreColumn = find("SLEEP_SCORE")
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

        var changed = false
        database.rawQuery("SELECT * FROM ${quote(table)} LIMIT 10000", null).use { cursor ->
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
                if (startEpoch != null) next = next.copy(sleepStartEpochSeconds = earliest(next.sleepStartEpochSeconds, startEpoch))
                if (endEpoch != null) next = next.copy(sleepEndEpochSeconds = latest(next.sleepEndEpochSeconds, endEpoch))

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
                        "REM" in stage || "RAPID" in stage -> next.copy(remSleepMinutes = (next.remSleepMinutes ?: 0) + sleepMinutes)
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
        val total = day.sleepMinutes ?: stageSleep.takeIf { it > 0 } ?: windowSleep
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
            day.deepSleepMinutes, day.hrvMillis, day.bodyEnergy, day.sleepScore,
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
private fun JSONObject.nullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)
private fun JSONObject.nullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name)
private fun JSONObject.nullableFloat(name: String): Float? =
    if (!has(name) || isNull(name)) null else optDouble(name).toFloat()
