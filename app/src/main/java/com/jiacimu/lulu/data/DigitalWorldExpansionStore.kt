package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class DigitalWorldResident(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val identity: String,
    val homeLocationCode: String,
    val currentLocationCode: String = homeLocationCode,
    val createdAt: Instant = Instant.now(),
    val lastInteractionAt: Instant? = null,
)

data class DigitalWorldDiscoveredPlace(
    val code: String = "shared:discovered:${UUID.randomUUID()}",
    val label: String,
    val subtitle: String,
    val purpose: String,
    val connectedTo: List<String>,
    val createdByCharacterId: String,
    val createdAt: Instant = Instant.now(),
)

/**
 * Program-owned expansion of the digital world. A location/resident is not a world fact until it
 * has been committed here successfully. Model prose can react to these entities but cannot create
 * them by mentioning a new name.
 */
internal object DigitalWorldExpansionStore {
    private const val PREFS_NAME = "lulu_digital_world_expansion_v1"
    private const val KEY_PLACES = "places"
    private const val KEY_RESIDENTS = "residents"
    private const val MAX_PLACES = 8
    private const val MAX_RESIDENTS = 12

    private var prefs: android.content.SharedPreferences? = null
    private var places: List<DigitalWorldDiscoveredPlace> = emptyList()
    private var residents: List<DigitalWorldResident> = emptyList()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        places = decodePlaces(prefs?.getString(KEY_PLACES, null))
        residents = decodeResidents(prefs?.getString(KEY_RESIDENTS, null))
    }

    @Synchronized
    fun places(): List<DigitalWorldDiscoveredPlace> = places.sortedBy(DigitalWorldDiscoveredPlace::createdAt)

    @Synchronized
    fun residents(): List<DigitalWorldResident> = residents.sortedBy(DigitalWorldResident::createdAt)

    @Synchronized
    fun residentsAt(locationCode: String): List<DigitalWorldResident> = residents
        .filter { it.currentLocationCode == locationCode }
        .sortedBy(DigitalWorldResident::name)

    @Synchronized
    fun registerPlace(place: DigitalWorldDiscoveredPlace): DigitalWorldDiscoveredPlace {
        checkNotNull(prefs) { "数字世界扩展状态尚未初始化" }
        require(place.code.startsWith("shared:discovered:")) { "新地点必须使用持久化发现地点 ID" }
        require(place.label.isNotBlank() && place.purpose.isNotBlank()) { "新地点必须有名称和真实用途" }
        require(place.connectedTo.isNotEmpty()) { "新地点至少要连接一个已经存在的地点" }
        require(places.none { it.code == place.code || it.label == place.label }) { "这个地点已经存在" }
        require(place.connectedTo.all(::isExistingLocation)) { "新地点不能连接到不存在的地点" }
        require(places.size < MAX_PLACES) { "动态地点数量已达到上限" }
        places = places + place
        persist()
        return place
    }

    @Synchronized
    fun registerResident(resident: DigitalWorldResident): DigitalWorldResident {
        checkNotNull(prefs) { "数字世界扩展状态尚未初始化" }
        require(resident.name.isNotBlank() && resident.identity.isNotBlank()) { "新居民必须有稳定姓名和身份" }
        require(isExistingLocation(resident.homeLocationCode)) { "新居民必须有真实存在的住处或公共归属地点" }
        require(residents.none { it.id == resident.id || it.name == resident.name }) { "这个居民已经存在" }
        require(residents.size < MAX_RESIDENTS) { "动态居民数量已达到上限" }
        residents = residents + resident
        persist()
        return resident
    }

    @Synchronized
    fun moveResident(residentId: String, locationCode: String, now: Instant = Instant.now()): DigitalWorldResident? {
        if (!isExistingLocation(locationCode)) return null
        var changed: DigitalWorldResident? = null
        residents = residents.map { resident ->
            if (resident.id != residentId) resident else resident.copy(
                currentLocationCode = locationCode,
                lastInteractionAt = now,
            ).also { changed = it }
        }
        if (changed != null) persist()
        return changed
    }

    @Synchronized
    fun contextFor(locationCode: String? = null): String {
        val relevantResidents = if (locationCode == null) residents else residents.filter { it.currentLocationCode == locationCode }
        val relevantPlaces = if (locationCode == null) places else places.filter { it.code == locationCode || locationCode in it.connectedTo }
        if (relevantPlaces.isEmpty() && relevantResidents.isEmpty()) return ""
        return buildString {
            appendLine("【数字世界扩展实体｜程序持久化事实】")
            relevantPlaces.forEach { place ->
                appendLine("- 地点 id=${place.code}；${place.label}；用途=${place.purpose}；连接=${place.connectedTo.joinToString("/")}")
            }
            relevantResidents.forEach { resident ->
                appendLine("- 居民 id=${resident.id}；姓名=${resident.name}；身份=${resident.identity}；住处=${resident.homeLocationCode}；当前位置=${resident.currentLocationCode}")
            }
            append("未列出的新地点、新居民都不存在；模型不能只靠叙述把它们变成世界事实。")
        }
    }

    @Synchronized
    fun isExistingLocation(code: String): Boolean = when {
        code == DigitalWorldStore.CLOUD_MEADOW || code == DigitalWorldStore.ARRIVAL -> true
        code.startsWith("home:") -> true
        code in setOf(
            DigitalWorldPublicPlaces.GAME_HALL,
            DigitalWorldPublicPlaces.READING_LOUNGE,
            DigitalWorldPublicPlaces.CAFE,
            DigitalWorldPublicPlaces.COURTYARD,
        ) -> true
        places.any { it.code == code } -> true
        else -> false
    }

    private fun persist() {
        val placeArray = JSONArray().apply {
            places.forEach { place ->
                put(JSONObject()
                    .put("code", place.code)
                    .put("label", place.label)
                    .put("subtitle", place.subtitle)
                    .put("purpose", place.purpose)
                    .put("connectedTo", JSONArray(place.connectedTo))
                    .put("createdByCharacterId", place.createdByCharacterId)
                    .put("createdAt", place.createdAt.toString()))
            }
        }
        val residentArray = JSONArray().apply {
            residents.forEach { resident ->
                put(JSONObject()
                    .put("id", resident.id)
                    .put("name", resident.name)
                    .put("identity", resident.identity)
                    .put("homeLocationCode", resident.homeLocationCode)
                    .put("currentLocationCode", resident.currentLocationCode)
                    .put("createdAt", resident.createdAt.toString())
                    .put("lastInteractionAt", resident.lastInteractionAt?.toString() ?: JSONObject.NULL))
            }
        }
        check(prefs?.edit()?.putString(KEY_PLACES, placeArray.toString())?.putString(KEY_RESIDENTS, residentArray.toString())?.commit() == true) {
            "数字世界扩展状态保存失败"
        }
    }

    private fun decodePlaces(raw: String?): List<DigitalWorldDiscoveredPlace> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val label = item.optString("label").trim()
                    val purpose = item.optString("purpose").trim()
                    if (label.isBlank() || purpose.isBlank()) continue
                    add(DigitalWorldDiscoveredPlace(
                        code = item.optString("code").takeIf { it.startsWith("shared:discovered:") }
                            ?: "shared:discovered:${UUID.randomUUID()}",
                        label = label,
                        subtitle = item.optString("subtitle"),
                        purpose = purpose,
                        connectedTo = item.optJSONArray("connectedTo").stringValues(),
                        createdByCharacterId = item.optString("createdByCharacterId"),
                        createdAt = item.optString("createdAt").expansionInstant(),
                    ))
                }
            }.takeLast(MAX_PLACES)
        }.getOrDefault(emptyList())
    }

    private fun decodeResidents(raw: String?): List<DigitalWorldResident> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    val home = item.optString("homeLocationCode").trim()
                    if (name.isBlank() || home.isBlank()) continue
                    add(DigitalWorldResident(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = name,
                        identity = item.optString("identity").ifBlank { "数字世界居民" },
                        homeLocationCode = home,
                        currentLocationCode = item.optString("currentLocationCode").ifBlank { home },
                        createdAt = item.optString("createdAt").expansionInstant(),
                        lastInteractionAt = item.optString("lastInteractionAt").takeUnless { it.isBlank() || it.equals("null", true) }
                            ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    ))
                }
            }.takeLast(MAX_RESIDENTS)
        }.getOrDefault(emptyList())
    }
}

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) return emptyList()
    return buildList { for (index in 0 until length()) optString(index).trim().takeIf(String::isNotBlank)?.let(::add) }
}

private fun String.expansionInstant(): Instant = runCatching { Instant.parse(this) }.getOrDefault(Instant.now())
