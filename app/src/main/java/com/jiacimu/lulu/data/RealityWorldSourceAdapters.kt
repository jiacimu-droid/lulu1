package com.jiacimu.lulu.data

import android.location.Location
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.OutputStreamWriter
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.round

internal data class RealityProviderBatch(
    val events: List<RealityWindowEvent>,
    val providerHints: Set<String> = emptySet(),
)

/**
 * Source adapters behind the real-world window.
 *
 * The role never calls these endpoints itself. Program code fetches external data, normalizes it,
 * and only then exposes source-backed events to a role. Every adapter is best-effort: one source
 * failing must not take the whole reality window down.
 */
internal object RealityWorldSourceAdapters {
    private const val GOOGLE_TOP =
        "https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
    private const val GOOGLE_SEARCH =
        "https://news.google.com/rss/search?q=%s&hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
    private const val GDELT_DOC = "https://api.gdeltproject.org/api/v2/doc/doc"
    private const val BANGUMI_SEARCH = "https://api.bgm.tv/v0/search/subjects?limit=8&offset=0"
    private const val ANILIST_GRAPHQL = "https://graphql.anilist.co/"
    private const val STEAM_STORE_SEARCH = "https://store.steampowered.com/api/storesearch/"
    private const val STEAM_NEWS = "https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/"
    private const val OPEN_METEO = "https://api.open-meteo.com/v1/forecast"
    private const val USGS_45_DAY =
        "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson"

    fun fetchWorldNews(topicId: String, topicLabel: String, now: Instant): List<RealityWindowEvent> {
        val result = mutableListOf<RealityWindowEvent>()
        runCatching { fetchGoogleRss(topicId, topicLabel, GOOGLE_TOP, now) }
            .getOrNull()?.let(result::addAll)
        runCatching {
            fetchGdelt(
                topicId = topicId,
                topicLabel = topicLabel,
                query = "(中国 OR China OR 日本 OR Japan OR 美国 OR Europe OR AI OR politics)",
                now = now,
                timespan = "1d",
            )
        }.getOrNull()?.let(result::addAll)
        return dedupe(result).take(36)
    }

    fun fetchTopic(
        topic: RealityTopic,
        providerHints: Set<String>,
        probeSpecialized: Boolean,
        now: Instant,
    ): RealityProviderBatch {
        val query = topic.query.trim()
        if (query.isBlank()) return RealityProviderBatch(emptyList(), providerHints)
        val events = mutableListOf<RealityWindowEvent>()
        val hints = providerHints.toMutableSet()

        runCatching {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            fetchGoogleRss(topic.id, topic.label, GOOGLE_SEARCH.format(encoded), now)
        }.getOrNull()?.let(events::addAll)

        runCatching { fetchGdelt(topic.id, topic.label, query, now, "7d") }
            .getOrNull()?.let(events::addAll)

        if (probeSpecialized || "bangumi" in providerHints) {
            runCatching { fetchBangumi(topic, now) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                events += it
                hints += "bangumi"
            }
        }
        if (probeSpecialized || "anilist" in providerHints) {
            runCatching { fetchAniList(topic, now) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                events += it
                hints += "anilist"
            }
        }
        if (probeSpecialized || "steam" in providerHints) {
            runCatching { fetchSteam(topic, now) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                events += it
                hints += "steam"
            }
        }

        return RealityProviderBatch(
            events = dedupe(events).take(44),
            providerHints = hints,
        )
    }

    fun fetchWeather(
        topicId: String,
        topicLabel: String,
        now: Instant,
        deviceLocation: Location?,
    ): List<RealityWindowEvent> {
        val location = deviceLocation ?: return emptyList()
        // Weather does not need precise coordinates. Round to roughly a 10 km grid before the
        // request so the external weather provider does not receive the device's exact position.
        val latitude = round(location.latitude * 10.0) / 10.0
        val longitude = round(location.longitude * 10.0) / 10.0
        val query = listOf(
            "latitude=$latitude",
            "longitude=$longitude",
            "current=temperature_2m,apparent_temperature,precipitation,rain,weather_code,wind_speed_10m,wind_gusts_10m",
            "hourly=temperature_2m,precipitation_probability",
            "forecast_hours=6",
            "timezone=auto",
        ).joinToString("&")
        val root = JSONObject(httpGet("$OPEN_METEO?$query"))
        val current = root.optJSONObject("current") ?: return emptyList()
        val temp = current.optDouble("temperature_2m", Double.NaN)
        val apparent = current.optDouble("apparent_temperature", Double.NaN)
        val precipitation = current.optDouble("precipitation", Double.NaN)
        val rain = current.optDouble("rain", Double.NaN)
        val wind = current.optDouble("wind_speed_10m", Double.NaN)
        val gust = current.optDouble("wind_gusts_10m", Double.NaN)
        val code = current.optInt("weather_code", -1)
        val weather = weatherCodeLabel(code)
        val hourly = root.optJSONObject("hourly")
        val probability = hourly?.optJSONArray("precipitation_probability")
        val maxRainChance = probability?.let { values ->
            (0 until values.length()).mapNotNull { index ->
                values.opt(index)?.toString()?.toIntOrNull()
            }.maxOrNull()
        }
        val summary = buildString {
            append("Open-Meteo 结构化天气数据（位置已在设备端约化到 0.1° 网格）：$weather")
            if (!temp.isNaN()) append("；气温=${format1(temp)}°C")
            if (!apparent.isNaN()) append("；体感=${format1(apparent)}°C")
            if (!precipitation.isNaN()) append("；当前降水=${format1(precipitation)}mm")
            if (!rain.isNaN() && rain > 0.0) append("；雨量=${format1(rain)}mm")
            if (!wind.isNaN()) append("；风速=${format1(wind)}km/h")
            if (!gust.isNaN()) append("；阵风=${format1(gust)}km/h")
            if (maxRainChance != null) append("；未来6小时最高降水概率=${maxRainChance}%")
            append("。这只是天气模型数据，不等于用户亲口描述了自己所在位置的实际体感。")
        }
        val slot = now.epochSecond / 3_600L
        return listOf(
            RealityWindowEvent(
                id = "openmeteo-${stableId(topicId, "$latitude:$longitude:$slot")}",
                topicId = topicId,
                topicLabel = topicLabel,
                title = "用户现实所在地附近天气：$weather${if (!temp.isNaN()) " ${format1(temp)}°C" else ""}",
                summary = summary,
                sourceName = "Open-Meteo",
                sourceUrl = OPEN_METEO,
                publishedAt = now,
                fetchedAt = now,
                trust = RealitySourceTrust.STRUCTURED_SOURCE,
            ),
        )
    }

    fun fetchEarthquakes(
        topicId: String,
        topicLabel: String,
        now: Instant,
        deviceLocation: Location?,
    ): List<RealityWindowEvent> {
        val root = JSONObject(httpGet(USGS_45_DAY))
        val features = root.optJSONArray("features") ?: return emptyList()
        return buildList {
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val properties = feature.optJSONObject("properties") ?: continue
                val geometry = feature.optJSONObject("geometry")
                val coordinates = geometry?.optJSONArray("coordinates")
                val latitude = coordinates?.optDouble(1, Double.NaN) ?: Double.NaN
                val longitude = coordinates?.optDouble(0, Double.NaN) ?: Double.NaN
                val magnitude = properties.optDouble("mag", Double.NaN)
                if (magnitude.isNaN()) continue
                val distanceKm = if (
                    deviceLocation != null && !latitude.isNaN() && !longitude.isNaN()
                ) {
                    val out = FloatArray(1)
                    Location.distanceBetween(
                        deviceLocation.latitude,
                        deviceLocation.longitude,
                        latitude,
                        longitude,
                        out,
                    )
                    (out[0] / 1_000f).toInt().coerceAtLeast(0)
                } else null
                if (distanceKm == null && magnitude < 6.0) continue
                if (distanceKm != null && distanceKm > 1_500 && magnitude < 6.0) continue

                val eventTime = properties.optLong("time", 0L)
                    .takeIf { it > 0L }?.let(Instant::ofEpochMilli) ?: now
                val place = properties.optString("place").trim().ifBlank { "未命名地点" }
                val critical = when {
                    distanceKm == null -> false
                    distanceKm <= 500 && magnitude >= 4.5 -> true
                    distanceKm <= 1_000 && magnitude >= 5.5 -> true
                    distanceKm <= 1_500 && magnitude >= 6.0 -> true
                    else -> false
                }
                val id = feature.optString("id").trim().ifBlank {
                    stableId("quake", "$place:$eventTime:$magnitude")
                }
                val distanceText = distanceKm?.let { "；距用户设备最近位置约${it}km" }.orEmpty()
                val detail = buildString {
                    append("USGS 结构化地震目录记录：M")
                    append(format1(magnitude))
                    append("，地点=$place，时间=$eventTime")
                    append(distanceText)
                    val alert = properties.optString("alert").trim()
                    if (alert.isNotBlank() && alert != "null") append("；alert=$alert")
                    append("。设备坐标没有发送给 USGS；距离由露露机本地计算。")
                }
                add(
                    RealityWindowEvent(
                        id = "usgs-$id",
                        topicId = topicId,
                        topicLabel = topicLabel,
                        title = "M${format1(magnitude)} $place",
                        summary = detail,
                        sourceName = "USGS Earthquake Catalog",
                        sourceUrl = properties.optString("url").trim(),
                        publishedAt = eventTime,
                        fetchedAt = now,
                        trust = RealitySourceTrust.STRUCTURED_SOURCE,
                        criticalForUser = critical,
                        distanceKm = distanceKm,
                    ),
                )
            }
        }.sortedByDescending(RealityWindowEvent::publishedAt).take(40)
    }

    private fun fetchGoogleRss(
        topicId: String,
        topicLabel: String,
        url: String,
        now: Instant,
    ): List<RealityWindowEvent> {
        val xml = httpGet(url)
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val result = mutableListOf<RealityWindowEvent>()
        var inItem = false
        var currentTag = ""
        var title = ""
        var link = ""
        var description = ""
        var pubDate = ""
        var guid = ""
        var source = ""

        fun addCurrent() {
            val cleanTitle = cleanExternalText(title).take(300)
            if (cleanTitle.isBlank()) return
            val published = parseRssDate(pubDate) ?: now
            val stable = guid.ifBlank { link.ifBlank { "$cleanTitle:$published" } }
            result += RealityWindowEvent(
                id = "gnews-${stableId(topicId, stable)}",
                topicId = topicId,
                topicLabel = topicLabel,
                title = cleanTitle,
                summary = cleanExternalText(description).take(1_000).ifBlank { cleanTitle },
                sourceName = cleanExternalText(source).ifBlank { "Google News 聚合" }.take(100),
                sourceUrl = link.trim().take(1_000),
                publishedAt = published,
                fetchedAt = now,
                trust = RealitySourceTrust.AGGREGATED_REPORT,
            )
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.orEmpty()
                    if (currentTag.equals("item", ignoreCase = true)) {
                        inItem = true
                        title = ""
                        link = ""
                        description = ""
                        pubDate = ""
                        guid = ""
                        source = ""
                    }
                }
                XmlPullParser.TEXT -> if (inItem) {
                    when (currentTag.lowercase(Locale.ROOT)) {
                        "title" -> title += parser.text
                        "link" -> link += parser.text
                        "description" -> description += parser.text
                        "pubdate" -> pubDate += parser.text
                        "guid" -> guid += parser.text
                        "source" -> source += parser.text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true)) {
                        addCurrent()
                        inItem = false
                    }
                    currentTag = ""
                }
            }
            parser.next()
        }
        return result.distinctBy(RealityWindowEvent::id)
            .sortedByDescending(RealityWindowEvent::publishedAt)
            .take(24)
    }

    private fun fetchGdelt(
        topicId: String,
        topicLabel: String,
        query: String,
        now: Instant,
        timespan: String,
    ): List<RealityWindowEvent> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = "$GDELT_DOC?query=$encoded&mode=artlist&format=json&maxrecords=18&timespan=$timespan&sort=datedesc"
        val root = JSONObject(httpGet(url))
        val articles = root.optJSONArray("articles") ?: return emptyList()
        return buildList {
            for (i in 0 until articles.length()) {
                val article = articles.optJSONObject(i) ?: continue
                val title = cleanExternalText(article.optString("title")).take(300)
                val articleUrl = article.optString("url").trim()
                if (title.isBlank()) continue
                val domain = article.optString("domain").trim()
                val country = article.optString("sourcecountry").trim()
                val language = article.optString("language").trim()
                val seen = parseGdeltDate(article.optString("seendate")) ?: now
                val details = buildString {
                    append(title)
                    if (domain.isNotBlank()) append("；来源域名=$domain")
                    if (country.isNotBlank()) append("；来源国家/地区=$country")
                    if (language.isNotBlank()) append("；语言=$language")
                    append("。GDELT 提供的是新闻索引元数据，标题不等于对文章正文的完整阅读。")
                }
                add(
                    RealityWindowEvent(
                        id = "gdelt-${stableId(topicId, articleUrl.ifBlank { "$title:$seen" })}",
                        topicId = topicId,
                        topicLabel = topicLabel,
                        title = title,
                        summary = details,
                        sourceName = if (domain.isBlank()) "GDELT 新闻索引" else "GDELT · $domain",
                        sourceUrl = articleUrl,
                        publishedAt = seen,
                        fetchedAt = now,
                        trust = RealitySourceTrust.AGGREGATED_REPORT,
                    ),
                )
            }
        }.sortedByDescending(RealityWindowEvent::publishedAt).take(18)
    }

    private fun fetchBangumi(topic: RealityTopic, now: Instant): List<RealityWindowEvent> {
        val payload = JSONObject()
            .put("keyword", topic.query)
            .put("sort", "match")
            .put("filter", JSONObject().put("nsfw", false))
            .toString()
        val root = JSONObject(httpPostJson(BANGUMI_SEARCH, payload))
        val data = root.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until minOf(data.length(), 8)) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optInt("id", 0)
                if (id <= 0) continue
                val nameCn = item.optString("name_cn").trim()
                val name = item.optString("name").trim()
                val title = nameCn.ifBlank { name }
                if (title.isBlank()) continue
                val date = item.optString("date").trim()
                val platform = item.optString("platform").trim()
                val summary = cleanExternalText(item.optString("summary")).take(800)
                val rating = item.optJSONObject("rating")
                val score = rating?.optDouble("score", Double.NaN) ?: Double.NaN
                val detail = buildString {
                    append("Bangumi 条目资料：$title")
                    if (name.isNotBlank() && name != title) append("（原名：$name）")
                    if (date.isNotBlank()) append("；日期=$date")
                    if (platform.isNotBlank()) append("；平台=$platform")
                    if (!score.isNaN() && score > 0.0) append("；站内评分=${format1(score)}")
                    if (summary.isNotBlank()) append("；简介=$summary")
                    append("。这是作品条目资料/发现结果，不代表今天刚发生了相关新闻。")
                }
                add(
                    RealityWindowEvent(
                        id = "bangumi-${topic.id}-$id",
                        topicId = topic.id,
                        topicLabel = topic.label,
                        title = "Bangumi：$title",
                        summary = detail,
                        sourceName = "Bangumi",
                        sourceUrl = "https://bgm.tv/subject/$id",
                        publishedAt = now,
                        fetchedAt = now,
                        trust = RealitySourceTrust.STRUCTURED_SOURCE,
                    ),
                )
            }
        }
    }

    private fun fetchAniList(topic: RealityTopic, now: Instant): List<RealityWindowEvent> {
        val gql = """
            query (${ '$' }search: String) {
              Page(page: 1, perPage: 6) {
                media(search: ${ '$' }search, sort: [TRENDING_DESC, POPULARITY_DESC], isAdult: false) {
                  id
                  type
                  title { romaji english native }
                  status
                  startDate { year month day }
                  season
                  seasonYear
                  genres
                  description(asHtml: false)
                  siteUrl
                }
              }
            }
        """.trimIndent()
        val payload = JSONObject()
            .put("query", gql)
            .put("variables", JSONObject().put("search", topic.query))
            .toString()
        val root = JSONObject(httpPostJson(ANILIST_GRAPHQL, payload))
        val media = root.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            ?: return emptyList()
        return buildList {
            for (i in 0 until minOf(media.length(), 6)) {
                val item = media.optJSONObject(i) ?: continue
                val id = item.optInt("id", 0)
                if (id <= 0) continue
                val titles = item.optJSONObject("title")
                val title = listOf(
                    titles?.optString("native").orEmpty(),
                    titles?.optString("english").orEmpty(),
                    titles?.optString("romaji").orEmpty(),
                ).firstOrNull { it.isNotBlank() } ?: continue
                val type = item.optString("type").trim()
                val status = item.optString("status").trim()
                val start = item.optJSONObject("startDate")
                val startDate = listOf(
                    start?.optInt("year", 0) ?: 0,
                    start?.optInt("month", 0) ?: 0,
                    start?.optInt("day", 0) ?: 0,
                ).filter { it > 0 }.joinToString("-")
                val genres = item.optJSONArray("genres").stringList().take(6)
                val description = cleanExternalText(item.optString("description")).take(700)
                val detail = buildString {
                    append("AniList 作品资料：$title")
                    if (type.isNotBlank()) append("；类型=$type")
                    if (status.isNotBlank()) append("；状态=$status")
                    if (startDate.isNotBlank()) append("；开始日期=$startDate")
                    if (genres.isNotEmpty()) append("；类型标签=${genres.joinToString("/")}")
                    if (description.isNotBlank()) append("；简介=$description")
                    append("。这是数据库条目资料，不应被说成角色已经观看了作品。")
                }
                add(
                    RealityWindowEvent(
                        id = "anilist-${topic.id}-$id",
                        topicId = topic.id,
                        topicLabel = topic.label,
                        title = "AniList：$title",
                        summary = detail,
                        sourceName = "AniList",
                        sourceUrl = item.optString("siteUrl").trim(),
                        publishedAt = now,
                        fetchedAt = now,
                        trust = RealitySourceTrust.STRUCTURED_SOURCE,
                    ),
                )
            }
        }
    }

    private fun fetchSteam(topic: RealityTopic, now: Instant): List<RealityWindowEvent> {
        val encoded = URLEncoder.encode(topic.query, StandardCharsets.UTF_8.toString())
        val searchUrl = "$STEAM_STORE_SEARCH?term=$encoded&l=schinese&cc=CN"
        val root = JSONObject(httpGet(searchUrl))
        val items = root.optJSONArray("items") ?: return emptyList()
        val candidates = buildList {
            for (i in 0 until minOf(items.length(), 8)) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("type") != "app") continue
                val id = item.optInt("id", 0)
                val name = item.optString("name").trim()
                if (id > 0 && name.isNotBlank() && titleLooksRelated(topic.query, name)) add(id to name)
            }
        }.take(2)
        if (candidates.isEmpty()) return emptyList()

        return buildList {
            candidates.forEach { (appid, appName) ->
                val newsUrl = "$STEAM_NEWS?appid=$appid&count=5&maxlength=700&format=json"
                val newsRoot = runCatching { JSONObject(httpGet(newsUrl)) }.getOrNull() ?: return@forEach
                val news = newsRoot.optJSONObject("appnews")?.optJSONArray("newsitems") ?: return@forEach
                for (i in 0 until minOf(news.length(), 5)) {
                    val item = news.optJSONObject(i) ?: continue
                    val title = cleanExternalText(item.optString("title")).take(300)
                    if (title.isBlank()) continue
                    val published = item.optLong("date", 0L).takeIf { it > 0L }?.let(Instant::ofEpochSecond) ?: now
                    val contents = cleanExternalText(item.optString("contents")).take(900)
                    val gid = item.optString("gid").ifBlank { "$appid:$title:$published" }
                    val feed = item.optString("feedlabel").trim()
                    val detail = buildString {
                        append("Steam 官方新闻接口记录：游戏=$appName；标题=$title")
                        if (feed.isNotBlank()) append("；feed=$feed")
                        if (contents.isNotBlank()) append("；摘要=$contents")
                    }
                    add(
                        RealityWindowEvent(
                            id = "steam-${stableId(topic.id, gid)}",
                            topicId = topic.id,
                            topicLabel = topic.label,
                            title = "Steam · $appName：$title",
                            summary = detail,
                            sourceName = "Steam News",
                            sourceUrl = item.optString("url").trim(),
                            publishedAt = published,
                            fetchedAt = now,
                            trust = RealitySourceTrust.STRUCTURED_SOURCE,
                        ),
                    )
                }
            }
        }.sortedByDescending(RealityWindowEvent::publishedAt)
    }

    private fun titleLooksRelated(query: String, name: String): Boolean {
        val q = normalizeTitle(query)
        val n = normalizeTitle(name)
        if (q.length < 2 || n.length < 2) return false
        if (q in n || n in q) return true
        val qTokens = q.split(' ').filter { it.length >= 2 }.toSet()
        val nTokens = n.split(' ').filter { it.length >= 2 }.toSet()
        if (qTokens.isEmpty() || nTokens.isEmpty()) return false
        val overlap = qTokens.intersect(nTokens).size
        return overlap >= 2 || overlap.toDouble() / qTokens.size.coerceAtLeast(1) >= 0.7
    }

    private fun normalizeTitle(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun httpGet(url: String): String = httpRequest(url, "GET", null)

    private fun httpPostJson(url: String, json: String): String = httpRequest(url, "POST", json)

    private fun httpRequest(url: String, method: String, body: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 18_000
            instanceFollowRedirects = true
            requestMethod = method
            setRequestProperty("Accept", "application/json, application/rss+xml, application/xml, text/xml, */*")
            setRequestProperty("User-Agent", "Lulu/1.0 RealityWindow Android")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body)
                }
            }
            val code = connection.responseCode
            require(code in 200..299) { "现实资讯源请求失败 HTTP $code" }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRssDate(value: String): Instant? = runCatching {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    }.getOrNull()

    private fun parseGdeltDate(value: String): Instant? = runCatching {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        LocalDateTime.parse(value.trim(), formatter).toInstant(ZoneOffset.UTC)
    }.getOrNull()

    private fun weatherCodeLabel(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大致晴朗"
        2 -> "多云间晴"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55, 56, 57 -> "毛毛雨"
        61, 63, 65, 66, 67 -> "下雨"
        71, 73, 75, 77 -> "下雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95, 96, 99 -> "雷暴"
        else -> "天气代码 $code"
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) optString(i).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun cleanExternalText(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun dedupe(items: List<RealityWindowEvent>): List<RealityWindowEvent> {
        val seen = mutableSetOf<String>()
        return items.sortedByDescending(RealityWindowEvent::publishedAt).filter { event ->
            val key = normalizeTitle(event.title).take(160)
            key.isNotBlank() && seen.add(key)
        }
    }

    private fun stableId(namespace: String, value: String): String {
        val seed = "$namespace::$value"
        val first = seed.hashCode().toUInt().toString(16)
        val second = seed.reversed().hashCode().toUInt().toString(16)
        return "$first$second"
    }

    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
}
