package com.jiacimu.lulu

import android.content.Context
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Desktop widget actions execute through the existing ToolRouter receipt chain.
 * No second model/persona store, and no direct role access to another role's card. */
internal object LuluWidgetRoleActions {
    private const val PREFS = "lulu_desktop_widget_role_notes_v1"
    private val changed = MutableStateFlow(0L)
    val revision = changed.asStateFlow()
    private val lock = Any()

    data class AuthoredNote(
        val characterId: String,
        val content: String,
        val createdAt: String,
    )

    fun readNote(context: Context, widget: LuluDesktopWidget): AuthoredNote? {
        if (widget.kind != LuluWidgetKind.NOTE || widget.characterId.isBlank()) return null
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(widget.id, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val author = obj.optString("characterId")
            val content = obj.optString("content")
            if (author != widget.characterId || content.isBlank()) null
            else AuthoredNote(author, content, obj.optString("createdAt"))
        }.getOrNull()
    }

    /** Read-only, real installed cards and their exact affordances. */
    fun characterContext(context: Context, characterId: String): String {
        if (characterId.isBlank()) return ""
        val cards = LuluDesktopWidgetStore.load(context).filter { it.characterId == characterId }
        if (cards.isEmpty()) return ""
        return buildString {
            appendLine("【主人桌面已真正绑定你的组件｜你可以选择使用，也可以不动】")
            cards.forEach { card ->
                append("- widgetId=").append(card.id).append("；类型=").append(card.kind.code)
                    .append("；当前皮肤=").append(card.skin)
                    .append("；授权装修=").append(card.allowRoleStyle)
                    .appendLine()
            }
            appendLine("- write_desktop_widget_note，args={\"widgetId\":\"已绑定你本人且类型为note的ID\",\"text\":\"你本人想留的话\"}：留一条真实桌面便签；不会自动发QQ，不得宣称已通知主人。")
            append("- decorate_desktop_widget，args={\"widgetId\":\"已绑定你本人且开放装修授权的ID\",")
            appendLine("\"skin\":0到3}：按你自己的审美更换单张组件内置插画，不覆盖主人导入的图片或其他组件；无需为展示活跃而强制更换。")
        }.trim()
    }

    fun execute(context: Context, characterId: String, name: String, args: JSONObject): JSONObject =
        synchronized(lock) {
            val id = args.optString("widgetId").trim()
            val current = LuluDesktopWidgetStore.load(context).firstOrNull { it.id == id }
            if (current == null || characterId.isBlank() || current.characterId != characterId) {
                return@synchronized JSONObject().put("success", false).put("status", "failed")
                    .put("summary", "未找到真正绑定本人且仍存在的桌面小组件")
            }
            when (name) {
                "write_desktop_widget_note" -> {
                    val text = args.optString("text").trim().take(180)
                    if (current.kind != LuluWidgetKind.NOTE || text.isBlank()) {
                        return@synchronized JSONObject().put("success", false)
                            .put("summary", "只有本人绑定的便签卡可写入非空文字")
                    }
                    val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(current.id, JSONObject()
                            .put("characterId", characterId).put("content", text)
                            .put("createdAt", Instant.now().toString()).toString()).commit()
                    if (committed) changed.value += 1L
                    JSONObject().put("success", committed)
                        .put("status", if (committed) "succeeded" else "failed")
                        .put("widgetId", current.id)
                        .put("summary", if (committed) "本人桌面便签已保存" else "便签写盘失败，未更新桌面")
                }
                "decorate_desktop_widget" -> {
                    val raw = args.opt("skin")
                    val skin = if (raw is Number) raw.toInt() else -1
                    val ok = raw is Number && raw.toDouble() == skin.toDouble() &&
                        LuluDesktopWidgetStore.roleApplySkin(context, id, characterId, skin)
                    JSONObject().put("success", ok)
                        .put("status", if (ok) "succeeded" else "failed")
                        .put("widgetId", current.id)
                        .put("summary", if (ok) "本人授权范围内的单张桌面组件已更换皮肤"
                            else "没有装修许可、皮肤编号无效或写盘失败；原配置未改")
                }
                else -> JSONObject().put("success", false)
                    .put("status", "failed").put("summary", "不支持的桌面操作")
            }
        }
}
