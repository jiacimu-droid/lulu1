package com.jiacimu.lulu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.vector.ImageVector
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.data.LuluCssStore
import com.jiacimu.lulu.design.LocalLuluCssSheet
import com.jiacimu.lulu.design.luluCssElement
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app Lulu desktop widgets. These are not Android launcher AppWidgetProvider widgets.
 * All data stays in this installation; no character profiles or fortunes are pre-created.
 */
/** Full desktop pages use near-square cells rather than stretching four rows over a tall screen. */
internal const val LULU_DESKTOP_CELL_ASPECT = 1.04f
internal fun luluDesktopFullCellHeight(viewportWidth: Float, viewportHeight: Float, rows: Int = 4): Float =
    minOf(viewportHeight / rows.coerceAtLeast(1).toFloat(),
        viewportWidth / 4f * LULU_DESKTOP_CELL_ASPECT)
        .coerceAtLeast(0f)

internal enum class LuluWidgetKind(val code: String, val title: String, val columns: Int, val rows: Int) {
    CHAT("chat", "聊天快捷卡", 4, 2),
    BULLETIN("bulletin", "数字世界公告栏", 4, 2),
    HOROSCOPE("horoscope", "每日星座", 4, 2),
    NOTE("note", "角色便签", 2, 2),
    COUNTDOWN("countdown", "纪念日", 2, 2),
    PLANNER("planner", "今日计划", 4, 2);
}

internal data class LuluDesktopWidget(
    val id: String,
    val kind: LuluWidgetKind,
    val page: Int,
    val col: Int,
    val row: Int,
    val columns: Int = kind.columns,
    val rows: Int = kind.rows,
    val skin: Int = 0,
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
)

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
private val luluWidgetSkins = listOf("星夜", "植物信纸", "樱粉", "像素夜空")
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
): Boolean {
    if (candidate.page < 0 || candidate.col < 0 || candidate.row < 0 ||
        candidate.columns !in 1..4 || candidate.rows !in 1..8 ||
        candidate.col + candidate.columns > 4 || candidate.row + candidate.rows > 8) return false
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
        for (y in 0..8 - kind.rows) for (x in 0..4 - kind.columns) {
            val candidate = LuluDesktopWidget(
                id = UUID.randomUUID().toString(), kind = kind,
                page = page, col = x, row = y, sign = sign,
                skin = when (kind) {
                    LuluWidgetKind.CHAT, LuluWidgetKind.BULLETIN -> 1
                    LuluWidgetKind.HOROSCOPE -> 0
                    LuluWidgetKind.NOTE -> 1
                    LuluWidgetKind.COUNTDOWN -> 2
                    LuluWidgetKind.PLANNER -> 3
                },
            )
            if (luluWidgetFits(candidate, widgets, launcherSlots)) return candidate
        }
    }
    return null
}

internal object LuluDesktopWidgetStore {
    private const val PREFS = "lulu_desktop_widgets_v1"
    private const val KEY = "cards"
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
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
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
                        skin = obj.optInt("skin", 0).coerceIn(0, 3),
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
                    if (value.page in 0..100 && luluWidgetFits(value, this, emptyList())) add(value)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, widgets: List<LuluDesktopWidget>): Boolean = synchronized(mutex) {
        val arr = JSONArray()
        widgets.take(100).forEach { w ->
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
        val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).commit()
        if (ok) {
            mutableRevision.value += 1L
            runCatching { LuluWidgetDailyRole.schedule(context, widgets) }
        }
        ok
    }

    /** One-time default home layout. User removal stays removed across restart. */
    fun ensureHomeCards(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("home_cards_seeded_v1", false)) return
        synchronized(mutex) {
            if (prefs.getBoolean("home_cards_seeded_v1", false)) return@synchronized
            val existing = load(context)
            val defaults = listOf(
                LuluDesktopWidget("home-chat", LuluWidgetKind.CHAT, 0, 0, 0, skin = 1),
                LuluDesktopWidget("home-bulletin", LuluWidgetKind.BULLETIN, 0, 0, 2, skin = 1),
            ).filter { card -> existing.none { it.kind == card.kind } &&
                luluWidgetFits(card, existing, emptyList()) }
            if (save(context, existing + defaults)) {
                prefs.edit().putBoolean("home_cards_seeded_v1", true).commit()
            }
        }
    }

    /** A model may touch ONLY its bound, explicitly unlocked widgets. */
    fun roleApplySkin(context: Context, widgetId: String, characterId: String, skin: Int): Boolean =
        synchronized(mutex) {
            if (skin !in 0..3 || characterId.isBlank()) return@synchronized false
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
        if (skin != null && skin !in 0..3) return@synchronized false
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
    onEdit: () -> Unit = onOpen,
    specialContent: @Composable ((LuluDesktopWidget, Color) -> Unit)? = null,
) {
    val context = LocalContext.current
    val css = LocalLuluCssSheet.current.element("Home", "widget-" + widget.kind.code)
    val defaultDark = widget.skin == 0 || widget.skin == 3
    val foreground = LuluCssSheet.parseColor(css["color"] ?: widget.roleInk)?.let { Color(it.toInt()) }
        ?: if (defaultDark) Color(0xFFF8F3E8) else Color(0xFF3D3836)
    val backing = LuluCssSheet.parseColor(css["background-color"] ?: widget.roleBackground)?.let { Color(it.toInt()) }
        ?: when (widget.skin) {
            0 -> Color(0xFF17223A)
            1 -> Color(0xFFF8F0E3)
            2 -> Color(0xFFF3DBEB)
            else -> Color(0xFF1D443B)
        }
    val art = remember(context, widget.skin) { LuluWidgetMaterialCache.get(context, widget.skin) }
    val custom = remember(widget.imageToken) {
        LuluCssStore.assetFile(widget.imageToken)?.let { path ->
            runCatching { BitmapFactory.decodeFile(path.path)?.asImageBitmap() }.getOrNull()
        }
    }
    val artImage = custom ?: art
    Surface(
        modifier = modifier.padding(horizontal = 6.dp, vertical = 10.dp)
            .luluCssElement("widget-" + widget.kind.code),
        shape = RoundedCornerShape((LuluCssSheet.dp(css["border-radius"]) ?:
            widget.roleCornerDp.takeIf { it >= 0 }?.toFloat() ?: 19f).coerceIn(0f, 60f).dp),
        color = backing,
        border = BorderStroke(1.dp, foreground.copy(alpha = 0.15f)),
        shadowElevation = 1.dp,
        onClick = onOpen,
    ) {
        if (widget.kind == LuluWidgetKind.CHAT || widget.kind == LuluWidgetKind.BULLETIN) {
            Box(Modifier.fillMaxSize()) {
                if (artImage != null) Image(
                    bitmap = artImage, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(backing.copy(alpha = if (defaultDark) .28f else .14f)))
                specialContent?.invoke(widget, foreground)
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = backing.copy(alpha = 0.88f),
                ) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Tune, "装修小组件", tint = foreground,
                            modifier = Modifier.size(17.dp))
                    }
                }
            }
        } else {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(backing.copy(alpha = .55f), backing.copy(alpha = .86f)),
        ))) {
            val compact = maxHeight < 145.dp
            if (artImage != null) Image(
                bitmap = artImage,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // Small legibility veil; keep artwork visible around the typography.
            Box(Modifier.fillMaxSize().background(
                if (defaultDark) Color(0x33000000) else Color(0x18FFFFFF),
            ))
            Column(
                modifier = Modifier.fillMaxSize().padding(if (compact) 8.dp else 15.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (widget.kind == LuluWidgetKind.HOROSCOPE) {
                        Text(luluWidgetCardHeading(widget),
                            color = foreground, fontSize = if (compact) 14.sp else 19.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(widget.kind.title, color = foreground.copy(alpha = .85f),
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(today.monthValue.toString().padStart(2, '0') + "." +
                            today.dayOfMonth.toString().padStart(2, '0'),
                            color = foreground.copy(alpha = .70f), fontSize = 10.sp)
                        Spacer(Modifier.width(5.dp))
                        IconButton(onClick = onEdit, modifier = Modifier.size(27.dp)) {
                            Icon(Icons.Outlined.Tune, contentDescription = "装修小组件",
                                modifier = Modifier.size(17.dp), tint = foreground)
                        }
                    }
                }
                when (widget.kind) {
                    LuluWidgetKind.CHAT, LuluWidgetKind.BULLETIN -> Unit
                    LuluWidgetKind.HOROSCOPE -> {
                        val sign = widget.sign.coerceIn(0, 11)
                        val (good, caution) = luluDailyFortune(today, sign)
                        if (!compact) Text(
                            "宜 · $good     忌 · $caution",
                            color = foreground.copy(alpha = .82f),
                            fontSize = 10.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (characterName.isNullOrBlank()) "还未绑定角色"
                            else "$characterName · 今天想对你说",
                            color = foreground.copy(alpha = .82f),
                            fontSize = if (compact) 10.sp else 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // Full model-authored character speech belongs on the card,
                        // not in a popup or single-line preview. 2x1 cards scroll.
                        Text(
                            when {
                                !widget.dailyReadingEnabled -> "在组件设置中开启每日解读。"
                                characterName.isNullOrBlank() || widget.characterId.isBlank() ->
                                    "绑定一个角色，就能在这里看到TA每天写给你的话。"
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
                        Text(characterName ?: "一封等待署名的信",
                            color = foreground, fontSize = if (compact) 13.sp else 17.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(roleNote?.takeIf(String::isNotBlank)
                            ?: lastConversationText?.takeIf(String::isNotBlank)
                            ?: "绑定一个角色，等他留下亲自写的便签。",
                            color = foreground, fontSize = 12.sp, lineHeight = 17.sp,
                            maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
                        if (!compact) Text(if (roleNote != null) "本人新留言 · 点击继续"
                            else if (lastConversationText != null) "最近真实对话 · 点击继续"
                            else "点击设置角色",
                            color = foreground.copy(alpha = .8f), fontSize = 10.sp)
                    }
                    LuluWidgetKind.COUNTDOWN -> {
                        val date = runCatching { LocalDate.parse(widget.targetDate) }.getOrNull()
                        val remaining = date?.let { ChronoUnit.DAYS.between(today, it) }
                        Text(widget.title.ifBlank { "值得期待的日子" },
                            color = foreground, fontSize = if (compact) 13.sp else 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                        Text(remaining?.let { if (it >= 0) "还有 " + it + " 天" else "已过去 " + (-it) + " 天" }
                            ?: "点这里设置日期",
                            color = foreground, fontSize = if (compact) 16.sp else 21.sp,
                            fontWeight = FontWeight.SemiBold)
                        if (!compact) Text(widget.targetDate.ifBlank { "COUNTDOWN" },
                            color = foreground.copy(alpha = .80f), fontSize = 10.sp)
                    }
                    LuluWidgetKind.PLANNER -> {
                        if (plannerTasks.isEmpty()) {
                            Text("今天没有待勾选的计划", color = foreground,
                                fontSize = if (compact) 13.sp else 17.sp)
                        } else {
                            plannerTasks.take(if (compact) 1 else 3).forEach { occurrence ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()) {
                                    IconButton(onClick = { onCompleteTask(occurrence) },
                                        modifier = Modifier.size(29.dp)) {
                                        Icon(Icons.Outlined.RadioButtonUnchecked, "完成" + occurrence.task.title,
                                            tint = foreground, modifier = Modifier.size(19.dp))
                                    }
                                    Text(occurrence.task.title, color = foreground, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Text("待办 " + plannerTasks.size + " 项 · 点击展开全部",
                            color = foreground.copy(alpha = .85f), fontSize = 10.sp)
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun LuluDesktopWidgetCanvas(
    page: Int,
    widgets: List<LuluDesktopWidget>,
    launcherSlots: List<String?>,
    today: LocalDate,
    characterNames: Map<String, String>,
    conversationTexts: Map<String, String>,
    roleReadings: Map<String, LuluWidgetDailyReading> = emptyMap(),
    roleNotes: Map<String, String> = emptyMap(),
    plannerTasks: List<TodoOccurrence> = emptyList(),
    onCompleteTask: (TodoOccurrence) -> Unit = {},
    modifier: Modifier = Modifier,
    specialContent: @Composable ((LuluDesktopWidget, Color) -> Unit)? = null,
    onOpenWidget: (LuluDesktopWidget) -> Unit,
    onEditWidget: (LuluDesktopWidget) -> Unit,
    onBlankLongPress: () -> Unit,
) {
    BoxWithConstraints(modifier.pointerInput(page, widgets, launcherSlots) {
        detectTapGestures(onLongPress = { p ->
            val x = (p.x / (size.width / 4f)).toInt().coerceIn(0, 3)
            val rowHeight = luluDesktopFullCellHeight(size.width.toFloat(), size.height.toFloat(), 8)
            val y = if (rowHeight > 0f) (p.y / rowHeight).toInt() else 4
            // Empty space below the four-row grid is still desktop; long-press
            // opens the picker without falsely selecting a clamped last-row icon.
            if (y !in 0..7) {
                onBlankLongPress()
            } else {
                val occupiedByIcon = launcherSlots.getOrNull(desktopSlotIndex(page, y, x)) != null
                val occupiedByWidget = widgets.any {
                    it.page == page && x in it.col until it.col + it.columns &&
                        y in it.row until it.row + it.rows
                }
                if (!occupiedByIcon && !occupiedByWidget) onBlankLongPress()
            }
        })
    }) {
        val cellWidth = maxWidth / 4
        val cellHeight = minOf(maxHeight / 8, cellWidth * LULU_DESKTOP_CELL_ASPECT)
        widgets.filter { it.page == page }.forEach { widget ->
            key(widget.id) {
                LuluDesktopWidgetCard(
                    widget = widget, today = today,
                    characterName = characterNames[widget.characterId],
                    lastConversationText = conversationTexts[widget.characterId],
                    roleReading = roleReadings[widget.id],
                    roleNote = roleNotes[widget.id],
                    plannerTasks = plannerTasks, onCompleteTask = onCompleteTask,
                    modifier = Modifier.width(cellWidth * widget.columns)
                        .height(cellHeight * widget.rows)
                        .offset(
                            x = cellWidth * widget.col,
                            y = cellHeight * widget.row,
                        ),
                    onOpen = { onOpenWidget(widget) },
                    onEdit = { onEditWidget(widget) },
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
                    listOf(2 to 1, 2 to 2, 2 to 3, 4 to 1, 4 to 2, 4 to 3, 4 to 4).forEach { (width, height) ->
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
                Text("选择插画", fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    luluWidgetSkins.forEachIndexed { index, name ->
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
                        Text("导入自己的背景")
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text("高级布局可在「美化 → CSS」里使用 #Home .widget-" +
                    initial.kind.code + " 调整。", style = MaterialTheme.typography.bodySmall)
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
