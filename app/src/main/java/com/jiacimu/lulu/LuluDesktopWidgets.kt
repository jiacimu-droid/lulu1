package com.jiacimu.lulu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.vector.ImageVector
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.data.LuluCssStore
import com.jiacimu.lulu.design.LocalLuluAppearance
import com.jiacimu.lulu.design.LocalLuluCssSheet
import com.jiacimu.lulu.design.LuluCssArtwork
import com.jiacimu.lulu.design.luluCssDecor
import com.jiacimu.lulu.design.cssFontSize
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app Lulu desktop widgets. These are not Android launcher AppWidgetProvider widgets.
 * All data stays in this installation; no character profiles or fortunes are pre-created.
 */
internal const val LULU_WIDGET_DESKTOP_SKIN = 4
private const val WIDGET_STACKED_SKIN = 5
private const val WIDGET_ALBUM_SKIN = 6
private const val WIDGET_BORDERLESS_SKIN = 7

internal enum class LuluWidgetKind(val code: String, val title: String, val columns: Int, val rows: Int) {
    CHAT("chat", "聊天快捷卡", 4, 2),
    BULLETIN("bulletin", "数字世界公告栏", 4, 2),
    HOROSCOPE("horoscope", "每日星座", 4, 2),
    NOTE("note", "角色便签", 2, 2),
    COUNTDOWN("countdown", "纪念日", 2, 2),
    PLANNER("planner", "今日计划", 4, 2),
    CALENDAR("calendar", "日历", 2, 1),
    CLOCK("clock", "时钟", 2, 1),
    PHOTO("photo", "照片叠片", 2, 2);

    val isDateTime: Boolean get() = this == CALENDAR || this == CLOCK

    val defaultSkin: Int get() = when (this) {
        NOTE, COUNTDOWN -> WIDGET_STACKED_SKIN
        PHOTO -> WIDGET_ALBUM_SKIN
        else -> LULU_WIDGET_DESKTOP_SKIN
    }
}

internal data class LuluDesktopWidget(
    val id: String,
    val kind: LuluWidgetKind,
    val page: Int,
    val col: Int,
    val row: Int,
    val columns: Int = kind.columns,
    val rows: Int = kind.rows,
    val skin: Int = kind.defaultSkin,
    val sign: Int = 0,
    val characterId: String = "",
    val title: String = "",
    val targetDate: String = "",
    val imageToken: String = "",
    val dailyReadingEnabled: Boolean = true,
    val dailyNotifyEnabled: Boolean = false,
    val allowRoleStyle: Boolean = false,
    val roleBackground: String = "",
    val roleInk: String = "",
    val roleCornerDp: Int = -1,
) {
    val hasBorderlessSkin: Boolean get() = skin == WIDGET_BORDERLESS_SKIN ||
        (skin == LULU_WIDGET_DESKTOP_SKIN && kind.isDateTime)
}

internal val luluZodiacNames = listOf(
    "摩羯座", "水瓶座", "双鱼座", "白羊座", "金牛座", "双子座",
    "巨蟹座", "狮子座", "处女座", "天秤座", "天蝎座", "射手座",
)
private val luluZodiacSymbols = listOf("♑", "♒", "♓", "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐")

/** Display heading only: picker/editor still call this widget type "每日星座".
 * The desktop card itself must use the user's chosen sign instead. */
internal fun luluWidgetCardHeading(widget: LuluDesktopWidget): String =
    if (widget.kind == LuluWidgetKind.HOROSCOPE) {
        val sign = widget.sign.coerceIn(0, 11)
        luluZodiacSymbols[sign] + "  " + luluZodiacNames[sign]
    } else widget.kind.title


/** Reads only a birthday's month/day, without sending the full birth date to any model. */
internal fun luluSignFromBirthday(raw: String?): Int? {
    val value = raw?.trim().orEmpty()
    val match = Regex("""^(?:(?:\d{4})[-/.年])?(\d{1,2})[-/.月](\d{1,2})日?$""")
        .matchEntire(value) ?: return null
    val month = match.groupValues[1].toIntOrNull() ?: return null
    val day = match.groupValues[2].toIntOrNull() ?: return null
    if (runCatching { java.time.MonthDay.of(month, day) }.isFailure) return null
    return when (month) {
        1 -> if (day >= 20) 1 else 0
        2 -> if (day >= 19) 2 else 1
        3 -> if (day >= 21) 3 else 2
        4 -> if (day >= 20) 4 else 3
        5 -> if (day >= 21) 5 else 4
        6 -> if (day >= 21) 6 else 5
        7 -> if (day >= 23) 7 else 6
        8 -> if (day >= 23) 8 else 7
        9 -> if (day >= 23) 9 else 8
        10 -> if (day >= 23) 10 else 9
        11 -> if (day >= 22) 11 else 10
        else -> if (day >= 22) 0 else 11
    }
}
private val luluWidgetSkins = linkedMapOf(
    LULU_WIDGET_DESKTOP_SKIN to "桌面原皮", WIDGET_STACKED_SKIN to "错落信笺",
    WIDGET_ALBUM_SKIN to "相册叠片", WIDGET_BORDERLESS_SKIN to "无边框",
    0 to "星夜", 1 to "植物信纸",
    2 to "樱粉", 3 to "像素夜空",
)
internal fun isLuluWidgetSkin(skin: Int): Boolean = skin in luluWidgetSkins

private val luluGoodAdvice = listOf(
    "整理思路", "主动交流", "阅读新东西", "认真推进一件事", "给自己一点休息",
    "记录灵感", "安排小小惊喜", "温柔表达", "适当运动", "专注重要任务",
)
private val luluCautionAdvice = listOf(
    "仓促下结论", "安排得太满", "忽略休息", "被琐事分心", "情绪上头时决策",
    "同时开启太多任务", "反复纠结", "过度消耗精力", "拖延重要约定", "熬夜",
)

/** Consistent for the same local date and sign; explicitly entertainment, not a verified prediction. */
internal fun luluDailyFortune(date: LocalDate, sign: Int): Pair<String, String> {
    val seed = date.toEpochDay() + sign.coerceIn(0, 11) * 37L
    val good = Math.floorMod(seed * 31L + 7L, luluGoodAdvice.size.toLong()).toInt()
    val caution = Math.floorMod(seed * 17L + 11L, luluCautionAdvice.size.toLong()).toInt()
    return luluGoodAdvice[good] to luluCautionAdvice[caution]
}

internal fun luluWidgetFits(
    candidate: LuluDesktopWidget,
    widgets: List<LuluDesktopWidget>,
    launcherSlots: List<String?>,
    gridRows: Int = DESKTOP_ROWS,
): Boolean {
    if (candidate.page < 0 || candidate.col !in 0 until DESKTOP_COLUMNS || candidate.row !in 0 until gridRows ||
        candidate.columns !in 1..DESKTOP_COLUMNS || candidate.rows !in 1..gridRows ||
        candidate.col + candidate.columns > DESKTOP_COLUMNS || candidate.row + candidate.rows > gridRows) return false
    for (y in candidate.row until candidate.row + candidate.rows) {
        for (x in candidate.col until candidate.col + candidate.columns) {
            if (launcherSlots.getOrNull(desktopSlotIndex(candidate.page, y, x)) != null) return false
        }
    }
    return widgets.none { other ->
        other.id != candidate.id && other.page == candidate.page &&
            candidate.col < other.col + other.columns && candidate.col + candidate.columns > other.col &&
            candidate.row < other.row + other.rows && candidate.row + candidate.rows > other.row
    }
}

/** Find a real empty rectangle instead of covering an existing launcher or widget. */
internal fun luluPlaceWidget(
    kind: LuluWidgetKind,
    startPage: Int,
    widgets: List<LuluDesktopWidget>,
    launcherSlots: List<String?>,
    sign: Int = 0,
): LuluDesktopWidget? {
    val first = startPage.coerceAtLeast(0)
    val last = maxOf(first + 2, widgets.maxOfOrNull { it.page }?.plus(2) ?: 2)
    for (page in first..last) {
        for (y in 0..DESKTOP_ROWS - kind.rows) for (x in 0..DESKTOP_COLUMNS - kind.columns) {
            val candidate = LuluDesktopWidget(
                id = UUID.randomUUID().toString(), kind = kind,
                page = page, col = x, row = y, sign = sign,
            )
            if (luluWidgetFits(candidate, widgets, launcherSlots)) return candidate
        }
    }
    return null
}

/**
 * Starter layout is presentation only. Never create a character, relationship,
 * conversation or simulated world event while preparing a new user's home.
 *
 * 4×7: date [0,1], chat [2], bulletin [3], apps automatically start at [4].
 * Existing installations must keep their saved grid and widget choices.
 */
internal fun firstInstallDesktopWidgets(): List<LuluDesktopWidget> = listOf(
    LuluDesktopWidget("home-date", LuluWidgetKind.CALENDAR, page = 0, col = 0, row = 0,
        columns = 4, rows = 2),
    LuluDesktopWidget("home-chat", LuluWidgetKind.CHAT, page = 0, col = 0, row = 2,
        columns = 4, rows = 1),
    LuluDesktopWidget("home-bulletin", LuluWidgetKind.BULLETIN, page = 0, col = 0, row = 3,
        columns = 4, rows = 1),
)

internal fun shouldSeedFirstInstallDesktop(
    hasSavedCards: Boolean,
    hasAnyPreviousGrid: Boolean,
    previouslySeeded: Boolean,
): Boolean = !hasSavedCards && !hasAnyPreviousGrid && !previouslySeeded

internal object LuluDesktopWidgetStore {
    private const val PREFS = "lulu_desktop_widgets_v1"
    private const val KEY = "cards"
    private const val GRID = "slots_v5"
    private const val SIX_ROW_GRID = "slots_v4"
    private const val HOME_SEEDED = "home_cards_seeded_v1"
    private const val SIGN = "profile_sign"
    private val mutex = Any()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    fun profileSign(context: Context): Int {
        val birthday = context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE)
            .getString("birthday", null)
        return luluSignFromBirthday(birthday)
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(SIGN, 0).coerceIn(0, 11)
    }

    fun load(context: Context): List<LuluDesktopWidget> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val gridRows = when {
            prefs.contains(GRID) -> DESKTOP_ROWS
            prefs.contains(SIX_ROW_GRID) -> SIX_ROW_DESKTOP_ROWS
            else -> LEGACY_DESKTOP_ROWS
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), 100)) {
                    val obj = array.optJSONObject(index) ?: continue
                    val kind = LuluWidgetKind.entries.firstOrNull { it.code == obj.optString("kind") } ?: continue
                    val value = LuluDesktopWidget(
                        id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue,
                        kind = kind,
                        page = obj.optInt("page", -1),
                        col = obj.optInt("col", -1),
                        row = obj.optInt("row", -1),
                        columns = obj.optInt("columns", kind.columns),
                        rows = obj.optInt("rows", kind.rows),
                        skin = obj.optInt("skin", kind.defaultSkin).takeIf(::isLuluWidgetSkin) ?: kind.defaultSkin,
                        sign = obj.optInt("sign", 0).coerceIn(0, 11),
                        characterId = obj.optString("characterId"),
                        title = obj.optString("title").take(70),
                        targetDate = obj.optString("targetDate").take(10),
                        imageToken = obj.optString("imageToken"),
                        dailyReadingEnabled = obj.optBoolean("dailyReadingEnabled", true),
                        dailyNotifyEnabled = obj.optBoolean("dailyNotifyEnabled", false),
                        allowRoleStyle = obj.optBoolean("allowRoleStyle", false),
                        roleBackground = obj.optString("roleBackground").takeIf {
                            LuluCssSheet.parseColor(it) != null
                        }.orEmpty(),
                        roleInk = obj.optString("roleInk").takeIf {
                            LuluCssSheet.parseColor(it) != null
                        }.orEmpty(),
                        roleCornerDp = obj.optInt("roleCornerDp", -1).coerceIn(-1, 60),
                    )
                    if (value.page in 0..1000 && luluWidgetFits(value, this, emptyList(), gridRows)) add(value)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(widgets: List<LuluDesktopWidget>): String {
        val arr = JSONArray()
        widgets.forEach { w ->
            arr.put(JSONObject().apply {
                put("id", w.id)
                put("kind", w.kind.code)
                put("page", w.page)
                put("col", w.col)
                put("row", w.row)
                put("columns", w.columns)
                put("rows", w.rows)
                put("skin", w.skin)
                put("sign", w.sign)
                put("characterId", w.characterId)
                put("title", w.title)
                put("targetDate", w.targetDate)
                put("imageToken", w.imageToken)
                put("dailyReadingEnabled", w.dailyReadingEnabled)
                put("dailyNotifyEnabled", w.dailyNotifyEnabled)
                put("allowRoleStyle", w.allowRoleStyle)
                put("roleBackground", w.roleBackground)
                put("roleInk", w.roleInk)
                put("roleCornerDp", w.roleCornerDp)
            })
        }
        return arr.toString()
    }

    fun save(context: Context, widgets: List<LuluDesktopWidget>): Boolean = synchronized(mutex) {
        if (widgets.size > 100) return@synchronized false
        val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, encode(widgets)).commit()
        if (ok) {
            mutableRevision.value += 1L
            runCatching { LuluWidgetDailyRole.schedule(context, widgets) }
        }
        ok
    }

    /** Persist the new page stride and cards together, so a restart cannot strand either half. */
    fun loadOrMigrateGrid(context: Context, launcherIds: List<String>): List<String?> = synchronized(mutex) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(GRID, null)?.let { return@synchronized readDesktopSlots(it) }
        val sixRowSlots = prefs.getString(SIX_ROW_GRID, null)
        val older = context.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE)
        val hasLegacyGrid = listOf("slots_v1", "slots_v2", "slots_v3")
            .any(older::contains)
        val freshInstall = shouldSeedFirstInstallDesktop(
            hasSavedCards = prefs.contains(KEY),
            hasAnyPreviousGrid = prefs.contains(SIX_ROW_GRID) || hasLegacyGrid,
            previouslySeeded = prefs.getBoolean(HOME_SEEDED, false),
        )
        val existing = load(context).toMutableList()
        // Seed only a truly empty installation. Do not repopulate cards deleted
        // by an older user or reshape any legacy 4×6 / 4×8 desktop.
        if (freshInstall) existing.addAll(firstInstallDesktopWidgets())
        val oldVersion = when {
            sixRowSlots != null -> 4
            older.contains("slots_v3") -> 3
            older.contains("slots_v2") -> 2
            else -> 1
        }
        val oldSlots = readDesktopSlots(sixRowSlots ?: older.getString("slots_v$oldVersion", null))
        val layout = migrateDesktopGrid(oldSlots, existing, oldVersion, launcherIds)
        val committed = prefs.edit().putString(GRID, serializeSlots(layout.slots))
            .putString(KEY, encode(layout.widgets)).putBoolean(HOME_SEEDED, true).commit()
        if (committed) {
            mutableRevision.value += 1L
            runCatching { LuluWidgetDailyRole.schedule(context, layout.widgets) }
        }
        layout.slots
    }

    fun saveLauncherSlots(context: Context, slots: List<String?>) = synchronized(mutex) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(GRID, serializeSlots(slots)).apply()
    }

    private fun serializeSlots(slots: List<String?>): String =
        slots.joinToString("|") { it.orEmpty() }

    /** A model may touch ONLY its bound, explicitly unlocked widgets. */
    fun roleApplySkin(context: Context, widgetId: String, characterId: String, skin: Int): Boolean =
        synchronized(mutex) {
            if (!isLuluWidgetSkin(skin) || characterId.isBlank()) return@synchronized false
            val current = load(context)
            val target = current.firstOrNull { it.id == widgetId &&
                it.characterId == characterId && it.allowRoleStyle } ?: return@synchronized false
            if (target.skin == skin) return@synchronized true
            save(context, current.map { if (it.id == widgetId) target.copy(skin = skin) else it })
        }

    fun roleApplyStyle(
        context: Context, widgetId: String, characterId: String,
        skin: Int?, background: String?, ink: String?, cornerDp: Int?,
    ): Boolean = synchronized(mutex) {
        val current = load(context)
        val card = current.firstOrNull { it.id == widgetId &&
            it.characterId == characterId && characterId.isNotBlank() &&
            it.allowRoleStyle } ?: return@synchronized false
        if (skin != null && !isLuluWidgetSkin(skin)) return@synchronized false
        if (background != null && LuluCssSheet.parseColor(background) == null)
            return@synchronized false
        if (ink != null && LuluCssSheet.parseColor(ink) == null)
            return@synchronized false
        if (cornerDp != null && cornerDp !in 0..60) return@synchronized false
        if (skin == null && background == null && ink == null && cornerDp == null)
            return@synchronized false
        val changed = card.copy(
            skin = skin ?: card.skin,
            roleBackground = background ?: card.roleBackground,
            roleInk = ink ?: card.roleInk,
            roleCornerDp = cornerDp ?: card.roleCornerDp,
        )
        save(context, current.map { if (it.id == widgetId) changed else it })
    }

    fun setProfileSign(context: Context, sign: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(SIGN, sign.coerceIn(0, 11)).apply()
    }
}

@Composable
internal fun rememberLuluDesktopDate(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            val newDate = LocalDate.now()
            if (newDate != today) today = newDate
        }
    }
    return today
}

@Composable
private fun rememberLuluDesktopTime(): LocalTime {
    var time by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - Math.floorMod(System.currentTimeMillis(), 60_000L))
            time = LocalTime.now()
        }
    }
    return time
}

/** One atlas is split into four real image materials; never fetched from the network. */
private object LuluWidgetMaterialCache {
    private var materials: List<ImageBitmap>? = null
    @Synchronized fun get(context: Context, index: Int): ImageBitmap? {
        if (materials == null) {
            materials = runCatching {
                val encoded = context.assets.open("widget_materials.webp.b64")
                    .bufferedReader().use { it.readText() }.trim()
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Widget atlas unavailable")
                val w = source.width / 2
                val h = source.height / 2
                List(4) { i -> Bitmap.createBitmap(source, (i % 2) * w, (i / 2) * h, w, h).asImageBitmap() }
            }.getOrDefault(emptyList())
        }
        return materials?.getOrNull(index.coerceIn(0, 3))
    }
}

@Composable
internal fun LuluDesktopWidgetCard(
    widget: LuluDesktopWidget,
    today: LocalDate,
    characterName: String?,
    lastConversationText: String?,
    roleReading: LuluWidgetDailyReading? = null,
    roleNote: String? = null,
    plannerTasks: List<TodoOccurrence> = emptyList(),
    onCompleteTask: (TodoOccurrence) -> Unit = {},
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onEdit: (() -> Unit)? = onOpen,
    activeOnDesktop: Boolean = false,
    specialContent: @Composable ((LuluDesktopWidget, Color, MutableInteractionSource) -> Unit)? = null,
) {
    val context = LocalContext.current
    val css = LocalLuluCssSheet.current.element("Home", "widget-" + widget.kind.code)
    val colors = MaterialTheme.colorScheme
    val themeSkin = widget.skin >= LULU_WIDGET_DESKTOP_SKIN
    val bareSkin = widget.hasBorderlessSkin
    val layered = widget.skin == WIDGET_STACKED_SKIN || widget.skin == WIDGET_ALBUM_SKIN
    val defaultDark = if (themeSkin) LocalLuluAppearance.current.dark else widget.skin == 0 || widget.skin == 3
    val foreground = LuluCssSheet.parseColor(css["color"] ?: widget.roleInk)?.let { Color(it.toInt()) }
        ?: when {
            themeSkin -> colors.onSurface
            defaultDark -> Color(0xFFF8F3E8)
            else -> Color(0xFF3D3836)
        }
    val backing = LuluCssSheet.parseColor(css["background-color"] ?: widget.roleBackground)?.let { Color(it.toInt()) }
        ?: when (widget.skin) {
            0 -> Color(0xFF17223A)
            1 -> Color(0xFFF8F0E3)
            2 -> Color(0xFFF3DBEB)
            3 -> Color(0xFF1D443B)
            WIDGET_STACKED_SKIN, WIDGET_ALBUM_SKIN -> colors.surface
            else -> if (bareSkin) Color.Transparent else colors.surface
        }
    val artwork = produceState<ImageBitmap?>(null, context, widget.skin) {
        value = if (themeSkin) null else withContext(Dispatchers.IO) {
            LuluWidgetMaterialCache.get(context, widget.skin)
        }
    }.value
    val artworkToken = (LuluCssSheet.assetToken(css["background-image"])
        ?: widget.imageToken.takeIf(String::isNotBlank))?.takeIf { LuluCssStore.assetFile(it) != null }
    val radius = (LuluCssSheet.dp(css["border-radius"])
        ?: widget.roleCornerDp.takeIf { it >= 0 }?.toFloat()
        ?: LocalLuluAppearance.current.corner.toFloat()).coerceIn(0f, 60f).dp
    val shape = RoundedCornerShape(radius)
    val outline = LuluCssSheet.parseColor(css["border-color"])?.let { Color(it.toInt()) }
        ?: if (themeSkin) colors.outline else foreground.copy(alpha = .15f)
    val borderWidth = (LuluCssSheet.dp(css["border-width"]) ?: if (bareSkin) 0f else 1f)
        .coerceIn(0f, 12f).dp
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = animateFloatAsState(
        if (pressed && activeOnDesktop) 1f else 0f,
        tween(if (pressed) 115 else 190, easing = FastOutSlowInEasing),
        label = "widget press",
    )
    val edge = rememberWidgetEdgeMotion(
        widget.id, activeOnDesktop && borderWidth > 0.dp, roleReading?.message ?: roleNote,
    )
    val outer = modifier.luluCssDecor(css, includeArtwork = false)
    BoxWithConstraints(outer.clipToBounds()) {
        val slimChat = widget.kind == LuluWidgetKind.CHAT && widget.rows == 2 &&
            widget.columns == DESKTOP_COLUMNS
        val surfaceHeight = if (slimChat) minOf(maxHeight * .86f, 170.dp) else maxHeight
        val showLayers = layered && maxHeight >= 94.dp
        val inset = if (showLayers) 10.dp else 5.dp
        val verticalInset = if (slimChat || bareSkin) 0.dp else inset
        if (showLayers) {
            val layerBounds = Modifier.align(Alignment.Center).fillMaxSize().padding(13.dp)
            Surface(
                layerBounds.offset(x = 5.dp, y = 5.dp).graphicsLayer {
                        val movement = press.value
                        rotationZ = if (widget.skin == WIDGET_ALBUM_SKIN) 2.5f - movement else -2f + movement
                        translationX = movement * 2.dp.toPx()
                    },
                shape = shape, color = colors.surfaceVariant, border = BorderStroke(.8.dp, outline),
            ) {}
            if (widget.skin == WIDGET_ALBUM_SKIN) {
                Surface(
                    layerBounds.offset(x = (-4).dp, y = 4.dp).graphicsLayer {
                            rotationZ = -2.5f + press.value
                            translationX = -press.value * 2.dp.toPx()
                        },
                    shape = shape, color = colors.surface, border = BorderStroke(.8.dp, outline),
                ) {}
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(surfaceHeight)
                .padding(horizontal = inset, vertical = verticalInset)
                .graphicsLayer {
                    val amount = press.value
                    scaleX = 1f - amount * .016f
                    scaleY = scaleX
                    translationY = amount * 1.5.dp.toPx()
                },
            shape = shape,
            color = backing,
            border = if (borderWidth > 0.dp) BorderStroke(borderWidth, outline) else null,
            shadowElevation = if (showLayers) 1.dp else 0.dp,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().then(
                if (widget.kind == LuluWidgetKind.CHAT || widget.kind == LuluWidgetKind.BULLETIN)
                    Modifier else Modifier.clickable(interactionSource = interaction, indication = null, onClick = onOpen),
            )) {
                val compact = maxHeight < 140.dp
                val short = maxHeight < 95.dp
                val framedPhoto = widget.kind == LuluWidgetKind.PHOTO && borderWidth > 0.dp
                val photoInset = if (framedPhoto) 6.dp else 0.dp
                val photoShape = RoundedCornerShape((radius - photoInset).coerceAtLeast(0.dp))
                val artPosition = if (framedPhoto) {
                    Modifier.matchParentSize().padding(photoInset).clip(photoShape)
                } else Modifier.matchParentSize()
                LuluCssArtwork(artworkToken, artPosition, css, maxEdge = 1024) {
                    artwork?.let { image ->
                        Image(image, null, artPosition, contentScale = ContentScale.Crop)
                    }
                }
                if (widget.kind != LuluWidgetKind.PHOTO && (artworkToken != null || artwork != null)) {
                    Box(Modifier.matchParentSize().background(backing.copy(
                        alpha = if (themeSkin) .50f else if (defaultDark) .30f else .24f,
                    )))
                }

                if (borderWidth > 0.dp) {
                    LuluWidgetFrame(widget.kind, foreground, outline, radius, press, edge, Modifier.matchParentSize())
                }
                when (widget.kind) {
                    LuluWidgetKind.CHAT, LuluWidgetKind.BULLETIN -> specialContent?.invoke(widget, foreground, interaction)
                    LuluWidgetKind.CALENDAR, LuluWidgetKind.CLOCK -> LuluWidgetDateTime(
                        kind = widget.kind, today = today, ink = foreground,
                        preferredFontSize = css.cssFontSize()?.value, hasEdit = onEdit != null,
                    )
                    LuluWidgetKind.PHOTO -> LuluWidgetPhoto(
                        widget, artworkToken != null || artwork != null, foreground, short,
                        Modifier.fillMaxSize().padding(photoInset).clip(photoShape),
                    )
                    LuluWidgetKind.PLANNER -> LuluWidgetPlanner(
                        plannerTasks, foreground, maxHeight, onCompleteTask,
                    )
                    else -> LuluWidgetTextContent(
                        widget, today, foreground, maxWidth, compact, short,
                        characterName, lastConversationText, roleReading, roleNote,
                    )
                }
                if (onEdit != null) IconButton(
                    onClick = onEdit,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(40.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (widget.kind == LuluWidgetKind.PHOTO && (artworkToken != null || artwork != null))
                            backing.copy(alpha = .92f) else Color.Transparent,
                        contentColor = foreground,
                    ),
                ) {
                    Icon(Icons.Outlined.Tune, "装修小组件", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LuluWidgetTextContent(
    widget: LuluDesktopWidget,
    today: LocalDate,
    foreground: Color,
    width: Dp,
    compact: Boolean,
    short: Boolean,
    characterName: String?,
    lastConversationText: String?,
    roleReading: LuluWidgetDailyReading?,
    roleNote: String?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(
            horizontal = if (compact) 10.dp else 15.dp,
            vertical = if (short) 5.dp else 10.dp,
        ),
        verticalArrangement = if (short) Arrangement.Center else Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        when (widget.kind) {
            LuluWidgetKind.HOROSCOPE -> {
                Row(
                    Modifier.fillMaxWidth().padding(end = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(luluWidgetCardHeading(widget), color = foreground, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (width >= 240.dp) Text(today.format(DateTimeFormatter.ofPattern("MM.dd")),
                        color = foreground.copy(alpha = foreground.alpha * .77f), fontSize = 11.sp)
                }
                val sign = widget.sign.coerceIn(0, 11)
                val (good, caution) = luluDailyFortune(today, sign)
                if (!short && width >= 210.dp) Text(
                    "宜 · $good     忌 · $caution",
                    color = foreground.copy(alpha = .82f),
                    fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!short && !characterName.isNullOrBlank()) Text(
                    "$characterName · 今天想对你说", color = foreground.copy(alpha = .82f),
                    fontSize = if (compact) 10.sp else 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Full model-authored character speech belongs on the card,
                // not in a popup or single-line preview. 2x1 cards scroll.
                Text(
                    when {
                        !widget.dailyReadingEnabled -> "在组件设置中开启每日解读。"
                        characterName.isNullOrBlank() || widget.characterId.isBlank() ->
                            "绑定角色后，就能收到 TA 每天写给你的话。"
                        roleReading != null -> "“" + roleReading.message + "”"
                        else -> "新一天开始后，会在这里出现我写给你的话。"
                    },
                    color = foreground, fontSize = if (compact) 11.sp else 14.sp,
                    lineHeight = if (compact) 15.sp else 19.sp,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState()),
                )
            }
            LuluWidgetKind.NOTE -> {
                val note = roleNote?.takeIf(String::isNotBlank)
                    ?: lastConversationText?.takeIf(String::isNotBlank)
                    ?: "点这里绑定角色"
                Text(note, modifier = Modifier.padding(end = if (short) 25.dp else 4.dp),
                    color = foreground, fontSize = if (short) 13.sp else 15.sp,
                    lineHeight = if (short) 17.sp else 21.sp, fontWeight = FontWeight.Medium,
                    maxLines = if (short) 2 else if (compact) 3 else 4, overflow = TextOverflow.Ellipsis)
                if (!characterName.isNullOrBlank()) {
                    val attribution = if (roleNote.isNullOrBlank() && !lastConversationText.isNullOrBlank())
                        "$characterName · 最近对话" else characterName
                    Text(attribution, color = foreground.copy(alpha = foreground.alpha * .72f),
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LuluWidgetKind.COUNTDOWN -> {
                val date = runCatching { LocalDate.parse(widget.targetDate) }.getOrNull()
                val remaining = date?.let { ChronoUnit.DAYS.between(today, it) }
                Text(widget.title.ifBlank { "纪念日" },
                    modifier = Modifier.padding(end = if (short) 26.dp else 4.dp), color = foreground,
                    fontSize = if (short) 12.sp else 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    remaining == null -> Text("设置日期", color = foreground, fontSize = 14.sp)
                    remaining == 0L -> Text("今天", color = foreground,
                        fontSize = if (short) 21.sp else 30.sp, fontWeight = FontWeight.SemiBold)
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val size = if (short) 22.sp else 32.sp
                        Text(kotlin.math.abs(remaining).toString(), Modifier.alignByBaseline(),
                            color = foreground, fontSize = size, lineHeight = size,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(if (remaining > 0) "天后" else "天前", Modifier.alignByBaseline(),
                            color = foreground.copy(alpha = foreground.alpha * .77f),
                            fontSize = 11.sp, maxLines = 1)
                    }
                }
                if (!short && date != null) Text(date.format(widgetDateFormat),
                    color = foreground.copy(alpha = foreground.alpha * .77f), fontSize = 11.sp)
            }
            else -> Unit
        }
    }
}

@Composable
private fun LuluWidgetPlanner(
    tasks: List<TodoOccurrence>,
    ink: Color,
    availableHeight: Dp,
    onCompleteTask: (TodoOccurrence) -> Unit,
) {
    val limit = ((availableHeight.value - 48f) / 40f).toInt().coerceIn(1, 4)
    val extra = (tasks.size - limit).coerceAtLeast(0)
    val subdued = ink.copy(alpha = ink.alpha * .72f)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("今日计划", Modifier.weight(1f), color = ink, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (extra > 0) Text("+$extra", color = subdued, fontSize = 11.sp)
        }
        if (tasks.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("今天没有待办", Modifier.padding(start = 4.dp), color = subdued,
                fontSize = 13.sp, maxLines = 1)
        }
        tasks.take(limit).forEachIndexed { index, occurrence ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 40.dp),
                color = ink.copy(alpha = ink.alpha * .10f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onCompleteTask(occurrence) }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Outlined.RadioButtonUnchecked, "完成 " + occurrence.task.title,
                        tint = subdued, modifier = Modifier.size(17.dp))
                }
                Text(occurrence.task.title, color = ink, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val widgetDateFormat = DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE)
private val widgetWeekdayFormat = DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE)
private val widgetTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)

@Composable
private fun LuluWidgetDateTime(
    kind: LuluWidgetKind,
    today: LocalDate,
    ink: Color,
    preferredFontSize: Float?,
    hasEdit: Boolean,
) {
    val clock = kind == LuluWidgetKind.CLOCK
    val heading = if (clock) rememberLuluDesktopTime().format(widgetTimeFormat) else today.format(widgetDateFormat)
    val weekday = today.format(widgetWeekdayFormat)
    val detail = if (clock) today.format(widgetDateFormat) + "  " + weekday else weekday
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
        contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toSp().value }
        val height = with(density) { maxHeight.toSp().value }
        val reserve = if (hasEdit && maxHeight < 75.dp) 30f else 0f
        val glyphWidth = if (clock) 3.3f else if (heading.length > 5) 4.7f else 4f
        val largest = minOf((width - reserve) / glyphWidth, height - 21f).coerceAtLeast(10f)
        val size = minOf(preferredFontSize ?: if (clock) 56f else 48f, largest)
        Column {
            Text(heading, fontSize = size.sp, lineHeight = (size * 1.06f).sp,
                fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1,
                overflow = TextOverflow.Clip)
            if (height >= 40f) Text(detail, fontSize = 12.sp, lineHeight = 16.sp,
                color = ink.copy(alpha = ink.alpha * .77f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LuluWidgetPhoto(
    widget: LuluDesktopWidget,
    hasArtwork: Boolean,
    ink: Color,
    short: Boolean,
    modifier: Modifier,
) {
    Box(modifier) {
        if (hasArtwork && widget.title.isNotBlank()) {
            Text(widget.title, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .68f))))
                .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 8.dp),
                color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else if (!hasArtwork) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Outlined.AddPhotoAlternate, null, tint = ink.copy(alpha = ink.alpha * .68f),
                    modifier = Modifier.size(if (short) 21.dp else 25.dp))
                Text("添加照片", color = ink, fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun LuluDesktopWidgetCanvas(
    page: Int,
    grid: DesktopGridGeometry,
    isSettledPage: Boolean,
    widgets: List<LuluDesktopWidget>,
    draggingWidgetId: String?,
    launcherSlots: List<String?>,
    today: LocalDate,
    characterNames: Map<String, String>,
    conversationTexts: Map<String, String>,
    roleReadings: Map<String, LuluWidgetDailyReading> = emptyMap(),
    roleNotes: Map<String, String> = emptyMap(),
    plannerTasks: List<TodoOccurrence> = emptyList(),
    onCompleteTask: (TodoOccurrence) -> Unit = {},
    modifier: Modifier = Modifier,
    specialContent: @Composable ((LuluDesktopWidget, Color, MutableInteractionSource) -> Unit)? = null,
    onOpenWidget: (LuluDesktopWidget) -> Unit,
    onEditWidget: (LuluDesktopWidget) -> Unit,
    onBlankLongPress: () -> Unit,
) {
    val cellEdge = with(LocalDensity.current) { grid.cellPx.toDp() }
    Box(modifier.pointerInput(page, grid, widgets, launcherSlots) {
        detectTapGestures(onLongPress = { p ->
            val cell = grid.cellAt(p.x, p.y)
            if (cell == null) {
                onBlankLongPress()
            } else {
                val occupiedByIcon = launcherSlots.getOrNull(desktopSlotIndex(page, cell.row, cell.column)) != null
                val occupiedByWidget = widgets.any {
                    it.page == page && cell.column in it.col until it.col + it.columns &&
                        cell.row in it.row until it.row + it.rows
                }
                if (!occupiedByIcon && !occupiedByWidget) onBlankLongPress()
            }
        })
    }) {
        widgets.filter { it.page == page }.forEach { widget ->
            key(widget.id) {
                LuluDesktopWidgetCard(
                    widget = widget, today = today,
                    characterName = characterNames[widget.characterId],
                    lastConversationText = conversationTexts[widget.characterId],
                    roleReading = roleReadings[widget.id],
                    roleNote = roleNotes[widget.id],
                    plannerTasks = plannerTasks, onCompleteTask = onCompleteTask,
                    modifier = Modifier.size(cellEdge * widget.columns, cellEdge * widget.rows)
                        .absoluteOffset { IntOffset(grid.x(widget.col), grid.y(widget.row)) }
                        .graphicsLayer { alpha = if (widget.id == draggingWidgetId) 0f else 1f },
                    onOpen = { onOpenWidget(widget) },
                    onEdit = { onEditWidget(widget) },
                    activeOnDesktop = isSettledPage && draggingWidgetId == null,
                    specialContent = specialContent,
                )
            }
        }
    }
}

/** Styled in-app sheet shared by widget picker, settings and todo details. */
@Composable
private fun LuluWidgetSheet(
    title: String,
    subtitle: String,
    graphic: ImageVector,
    onClose: () -> Unit,
    heightFraction: Float,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(heightFraction),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
            shadowElevation = 9.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(graphic, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp).size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

@Composable
internal fun LuluDesktopWidgetPicker(onAdd: (LuluWidgetKind) -> Unit, onClose: () -> Unit) {
    LuluWidgetSheet(
        title = "添加小组件",
        subtitle = "挑选一张小卡，放进桌面剩余的空格",
        graphic = Icons.Outlined.Widgets, onClose = onClose, heightFraction = 0.72f,
        actions = { TextButton(onClick = onClose) { Text("完成") } },
    ) {
        LuluWidgetKind.entries.forEach { kind ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth().clickable { onAdd(kind) },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (kind) {
                        LuluWidgetKind.CHAT -> Icons.Outlined.ChatBubbleOutline
                        LuluWidgetKind.BULLETIN -> Icons.Outlined.Campaign
                        LuluWidgetKind.HOROSCOPE -> Icons.Outlined.AutoAwesome
                        LuluWidgetKind.NOTE -> Icons.Outlined.StickyNote2
                        LuluWidgetKind.COUNTDOWN -> Icons.Outlined.Event
                        LuluWidgetKind.PLANNER -> Icons.Outlined.Checklist
                        LuluWidgetKind.CALENDAR -> Icons.Outlined.CalendarToday
                        LuluWidgetKind.CLOCK -> Icons.Outlined.Schedule
                        LuluWidgetKind.PHOTO -> Icons.Outlined.Collections
                    }, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(kind.title, fontWeight = FontWeight.SemiBold)
                        Text("${kind.columns}×${kind.rows} · 支持调整尺寸",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Add, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun LuluDesktopWidgetEditor(
    initial: LuluDesktopWidget,
    characters: Map<String, String>,
    onSave: (LuluDesktopWidget) -> Boolean,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var openCharacters by remember { mutableStateOf(false) }
    var openSigns by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var layoutError by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) draft = draft.copy(dailyNotifyEnabled = true)
        else importError = "通知未授权，角色当天的解读仍可在桌面查看。"
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { LuluCssStore.importImage(context, uri) }
                .onSuccess { draft = draft.copy(imageToken = it); importError = null }
                .onFailure { importError = it.message ?: "导入图片失败" }
        }
    }
    LuluWidgetSheet(
        title = initial.kind.title,
        subtitle = "装修卡片 · 内容、尺寸与角色",
        graphic = Icons.Outlined.Tune, onClose = onClose, heightFraction = 0.87f,
        actions = {
            OutlinedButton(onClick = onClose) { Text("取消") }
            Button(onClick = {
                if (initial.kind != LuluWidgetKind.COUNTDOWN ||
                    draft.targetDate.isBlank() ||
                    runCatching { LocalDate.parse(draft.targetDate) }.isSuccess) {
                    if (!onSave(draft)) layoutError =
                        "目标尺寸与其他图标或组件重叠，请改小尺寸或先移动组件。"
                }
            }) { Text("保存") }
        },
    ) {
                Text("尺寸", fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (initial.kind == LuluWidgetKind.CHAT || initial.kind == LuluWidgetKind.BULLETIN)
                        listOf(2 to 2, 2 to 3, 2 to 4, 4 to 1, 4 to 2, 4 to 3, 4 to 4)
                    else listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4, 4 to 1, 4 to 2, 4 to 3, 4 to 4))
                    .forEach { (width, height) ->
                        FilterChip(
                            selected = draft.columns == width && draft.rows == height,
                            onClick = {
                                draft = draft.copy(columns = width, rows = height)
                                layoutError = null
                            },
                            label = { Text(width.toString() + "×" + height.toString()) },
                        )
                    }
                }
                Text("皮肤", fontWeight = FontWeight.Medium)
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val previewCell = minOf(maxWidth / DESKTOP_COLUMNS, 70.dp)
                    LuluDesktopWidgetCard(
                        widget = draft,
                        today = rememberLuluDesktopDate(),
                        characterName = characters[draft.characterId],
                        lastConversationText = null,
                        modifier = Modifier.size(previewCell * draft.columns, previewCell * draft.rows),
                        onOpen = {},
                        onEdit = null,
                        specialContent = { item, ink, _ ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(item.kind.title, color = ink, fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        },
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    luluWidgetSkins.forEach { (index, name) ->
                        FilterChip(
                            selected = draft.skin == index,
                            onClick = { draft = draft.copy(skin = index, roleBackground = "",
                                roleInk = "", roleCornerDp = -1) },
                            label = { Text(name, fontSize = 11.sp) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (initial.kind == LuluWidgetKind.PHOTO) "挑选照片" else "导入自己的背景")
                    }
                    if (draft.imageToken.isNotBlank()) TextButton(onClick = {
                        draft = draft.copy(imageToken = "")
                    }) { Text("恢复内置") }
                }
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                layoutError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                if (initial.kind == LuluWidgetKind.HOROSCOPE) {
                    Text("星座 · 可直接选择，也可以使用现有个人资料里的生日",
                        fontWeight = FontWeight.Medium)
                    val profileSign = luluSignFromBirthday(
                        context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE)
                            .getString("birthday", null))
                    if (profileSign != null) {
                        TextButton(onClick = { draft = draft.copy(sign = profileSign) }) {
                            Text("按我的生日识别：" + luluZodiacNames[profileSign])
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { openSigns = true }) {
                            Text(luluZodiacSymbols[draft.sign] + "  " + luluZodiacNames[draft.sign])
                        }
                        DropdownMenu(expanded = openSigns, onDismissRequest = { openSigns = false }) {
                            luluZodiacNames.forEachIndexed { i, name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    draft = draft.copy(sign = i)
                                    openSigns = false
                                })
                            }
                        }
                    }
                }
                if (initial.kind == LuluWidgetKind.PHOTO) {
                    OutlinedTextField(
                        value = draft.title, onValueChange = { draft = draft.copy(title = it.take(70)) },
                        label = { Text("照片说明（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (initial.kind == LuluWidgetKind.COUNTDOWN) {
                    OutlinedTextField(
                        value = draft.title, onValueChange = { draft = draft.copy(title = it.take(70)) },
                        label = { Text("纪念日名称") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.targetDate, onValueChange = {
                            draft = draft.copy(targetDate = it.take(10))
                        },
                        label = { Text("日期 · 2027-01-01") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = draft.targetDate.isNotBlank() &&
                            runCatching { LocalDate.parse(draft.targetDate) }.isFailure,
                    )
                }
                if (initial.kind == LuluWidgetKind.HOROSCOPE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = draft.dailyReadingEnabled,
                            onCheckedChange = { draft = draft.copy(dailyReadingEnabled = it) })
                        Text("每天凌晨 00:00 自动用绑定角色的聊天模型解读（后台可能延迟）",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = draft.dailyNotifyEnabled,
                            onCheckedChange = { wants ->
                                if (!wants) draft = draft.copy(dailyNotifyEnabled = false)
                                else if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                        context, android.Manifest.permission.POST_NOTIFICATIONS,
                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else draft = draft.copy(dailyNotifyEnabled = true)
                            })
                        Text("允许角色决定是否发送当天的通知提醒",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (initial.kind in listOf(LuluWidgetKind.NOTE, LuluWidgetKind.HOROSCOPE, LuluWidgetKind.COUNTDOWN)) Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = draft.allowRoleStyle,
                        onCheckedChange = { draft = draft.copy(allowRoleStyle = it) })
                    Text("授权绑定角色自由选择这张组件的内置皮肤",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (initial.kind == LuluWidgetKind.NOTE || initial.kind == LuluWidgetKind.HOROSCOPE ||
                    initial.kind == LuluWidgetKind.COUNTDOWN) {
                    Text("绑定角色", fontWeight = FontWeight.Medium)
                    Box {
                        OutlinedButton(onClick = { openCharacters = true }) {
                            Text(characters[draft.characterId] ?: "不绑定角色")
                        }
                        DropdownMenu(expanded = openCharacters, onDismissRequest = { openCharacters = false }) {
                            DropdownMenuItem(text = { Text("不绑定") }, onClick = {
                                draft = draft.copy(characterId = "")
                                openCharacters = false
                            })
                            characters.forEach { (id, name) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    draft = draft.copy(characterId = id)
                                    openCharacters = false
                                })
                            }
                        }
                    }
                }
                TextButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, null)
            Spacer(Modifier.width(6.dp))
            Text("移除这个组件", color = MaterialTheme.colorScheme.error)
        }
    }
}


/** Full today list stays checkable without opening another app or truncating tasks. */
@Composable
internal fun LuluDesktopPlannerDialog(
    tasks: List<TodoOccurrence>,
    onCheck: (TodoOccurrence) -> Unit,
    onOpenSelfPreface: () -> Unit,
    onClose: () -> Unit,
) {
    LuluWidgetSheet(
        title = "今日计划",
        subtitle = "今天还有 ${tasks.size} 项待办",
        graphic = Icons.Outlined.Checklist, onClose = onClose, heightFraction = 0.75f,
        actions = {
            TextButton(onClick = onClose) { Text("关闭") }
            Button(onClick = onOpenSelfPreface) { Text("打开自序") }
        },
    ) {
                if (tasks.isEmpty()) {
                    Text("今天没有待办啦，可以在自序添加下一项。",
                        style = MaterialTheme.typography.bodyMedium)
                }
                tasks.forEach { occurrence ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onCheck(occurrence) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = "完成",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(occurrence.task.title, style = MaterialTheme.typography.bodyMedium)
                            occurrence.task.minutes?.let { minutes ->
                                Text("%02d:%02d".format(minutes / 60, minutes % 60),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    HorizontalDivider()
                }
                Text("勾选后立即写入「自序·所行」的真实完成记录；非学习或睡眠行为的独立证明。",
                    style = MaterialTheme.typography.labelSmall)
    }
}
