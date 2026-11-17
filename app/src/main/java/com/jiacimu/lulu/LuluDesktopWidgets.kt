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
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.data.LuluCssStore
import com.jiacimu.lulu.design.LocalLuluCssSheet
import com.jiacimu.lulu.design.luluCssElement
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app Lulu desktop widgets. These are not Android launcher AppWidgetProvider widgets.
 * All data stays in this installation; no character profiles or fortunes are pre-created.
 */
internal enum class LuluWidgetKind(val code: String, val title: String, val columns: Int, val rows: Int) {
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
)

internal val luluZodiacNames = listOf(
    "摩羯座", "水瓶座", "双鱼座", "白羊座", "金牛座", "双子座",
    "巨蟹座", "狮子座", "处女座", "天秤座", "天蝎座", "射手座",
)
private val luluZodiacSymbols = listOf("♑", "♒", "♓", "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐")
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
    if (candidate.page < 1 || candidate.col < 0 || candidate.row < 0 ||
        candidate.columns !in 1..4 || candidate.rows !in 1..4 ||
        candidate.col + candidate.columns > 4 || candidate.row + candidate.rows > 4) return false
    for (y in candidate.row until candidate.row + candidate.rows) {
        for (x in candidate.col until candidate.col + candidate.columns) {
            if (launcherSlots.getOrNull(candidate.page * 16 + y * 4 + x) != null) return false
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
    val first = startPage.coerceAtLeast(1)
    val last = maxOf(first + 2, widgets.maxOfOrNull { it.page }?.plus(2) ?: 2)
    for (page in first..last) {
        for (y in 0..4 - kind.rows) for (x in 0..4 - kind.columns) {
            val candidate = LuluDesktopWidget(
                id = UUID.randomUUID().toString(), kind = kind,
                page = page, col = x, row = y, sign = sign,
                skin = when (kind) {
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

    fun profileSign(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(SIGN, 0).coerceIn(0, 11)

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
                    )
                    if (value.page in 1..100 && luluWidgetFits(value, this, emptyList())) add(value)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, widgets: List<LuluDesktopWidget>): Boolean {
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
            })
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).commit()
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
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onEdit: () -> Unit = onOpen,
) {
    val context = LocalContext.current
    val css = LocalLuluCssSheet.current.element("Home", "widget-" + widget.kind.code)
    val defaultDark = widget.skin == 0 || widget.skin == 3
    val foreground = LuluCssSheet.parseColor(css["color"])?.let { Color(it.toInt()) }
        ?: if (defaultDark) Color(0xFFF8F3E8) else Color(0xFF3D3836)
    val backing = LuluCssSheet.parseColor(css["background-color"])?.let { Color(it.toInt()) }
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
        modifier = modifier.padding(4.dp).luluCssElement("widget-" + widget.kind.code),
        shape = RoundedCornerShape((LuluCssSheet.dp(css["border-radius"]) ?: 19f).coerceIn(0f, 60f).dp),
        color = backing,
        border = BorderStroke(1.dp, foreground.copy(alpha = 0.15f)),
        shadowElevation = 1.dp,
        onClick = onOpen,
    ) {
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
                    Text(widget.kind.title, color = foreground.copy(alpha = .85f),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                    LuluWidgetKind.HOROSCOPE -> {
                        val sign = widget.sign.coerceIn(0, 11)
                        val (good, caution) = luluDailyFortune(today, sign)
                        Text(luluZodiacSymbols[sign] + "  " + luluZodiacNames[sign],
                            color = foreground, fontSize = if (compact) 17.sp else 23.sp,
                            fontWeight = FontWeight.SemiBold)
                        if (!compact) Text("宜 · " + good + "      忌 · " + caution,
                            color = foreground, fontSize = 12.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(if (compact) "宜 · " + good
                            else if (characterName == null) "每日灵感 · 娱乐参考"
                            else characterName + " · 点开聊聊今日灵感",
                            color = foreground.copy(alpha = .84f), fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    LuluWidgetKind.NOTE -> {
                        Text(characterName ?: "一封等待署名的信",
                            color = foreground, fontSize = if (compact) 13.sp else 17.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(lastConversationText?.takeIf(String::isNotBlank)
                            ?: "绑定一个角色，把真实的最近对话留在桌面。",
                            color = foreground, fontSize = 12.sp, lineHeight = 17.sp,
                            maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
                        if (!compact) Text(if (characterName != null) "最近对话 · 点击继续"
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
                        Text("把今天，留给重要的事。", color = foreground,
                            fontSize = if (compact) 14.sp else 20.sp, fontWeight = FontWeight.Medium)
                        if (!compact) Text("打开「自序」查看和勾选真实待办，不会生成虚假的完成记录。",
                            color = foreground, fontSize = 12.sp, maxLines = 2)
                        Text("今日计划  ·  点击进入自序",
                            color = foreground.copy(alpha = .82f), fontSize = 10.sp)
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
    modifier: Modifier = Modifier,
    onOpenWidget: (LuluDesktopWidget) -> Unit,
    onEditWidget: (LuluDesktopWidget) -> Unit,
    onBlankLongPress: () -> Unit,
) {
    BoxWithConstraints(modifier.pointerInput(page, widgets, launcherSlots) {
        detectTapGestures(onLongPress = { p ->
            val x = (p.x / (size.width / 4f)).toInt().coerceIn(0, 3)
            val y = (p.y / (size.height / 4f)).toInt().coerceIn(0, 3)
            val occupiedByIcon = launcherSlots.getOrNull(page * 16 + y * 4 + x) != null
            val occupiedByWidget = widgets.any {
                it.page == page && x in it.col until it.col + it.columns &&
                    y in it.row until it.row + it.rows
            }
            if (!occupiedByIcon && !occupiedByWidget) onBlankLongPress()
        })
    }) {
        val cellWidth = maxWidth / 4
        val cellHeight = maxHeight / 4
        widgets.filter { it.page == page }.forEach { widget ->
            key(widget.id) {
                LuluDesktopWidgetCard(
                    widget = widget, today = today,
                    characterName = characterNames[widget.characterId],
                    lastConversationText = conversationTexts[widget.characterId],
                    modifier = Modifier.width(cellWidth * widget.columns)
                        .height(cellHeight * widget.rows)
                        .offset(
                            x = cellWidth * widget.col,
                            y = cellHeight * widget.row,
                        ),
                    onOpen = { onOpenWidget(widget) },
                    onEdit = { onEditWidget(widget) },
                )
            }
        }
    }
}

@Composable
internal fun LuluDesktopWidgetPicker(onAdd: (LuluWidgetKind) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("添加小组件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("自由摆放到桌面空位，长按组件可拖动位置。",
                    style = MaterialTheme.typography.bodySmall)
                LuluWidgetKind.entries.forEach { kind ->
                    Surface(
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { onAdd(kind) },
                    ) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (kind) {
                                    LuluWidgetKind.HOROSCOPE -> Icons.Outlined.AutoAwesome
                                    LuluWidgetKind.NOTE -> Icons.Outlined.StickyNote2
                                    LuluWidgetKind.COUNTDOWN -> Icons.Outlined.Event
                                    LuluWidgetKind.PLANNER -> Icons.Outlined.Checklist
                                }, contentDescription = null,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(kind.title, fontWeight = FontWeight.SemiBold)
                                Text(kind.columns.toString() + "×" + kind.rows.toString() + " 格",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
    )
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { LuluCssStore.importImage(context, uri) }
                .onSuccess { draft = draft.copy(imageToken = it); importError = null }
                .onFailure { importError = it.message ?: "导入图片失败" }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("装修 · " + initial.kind.title) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("尺寸", fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2 to 1, 2 to 2, 4 to 2).forEach { (width, height) ->
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
                            onClick = { draft = draft.copy(skin = index) },
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
                    Text("个人资料 · 星座（仅保存在本机）", fontWeight = FontWeight.Medium)
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
                TextButton(onClick = onDelete) { Text("移除这个组件", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (initial.kind != LuluWidgetKind.COUNTDOWN ||
                    draft.targetDate.isBlank() ||
                    runCatching { LocalDate.parse(draft.targetDate) }.isSuccess) {
                    if (!onSave(draft)) layoutError = "目标尺寸与其他图标或组件重叠，请改小尺寸或先移动组件。"
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}
