package com.jiacimu.lulu.data

/** Stable selectors shared by the editor, the parser and the native page hooks. */
object LuluCssTargets {
    data class Page(val id: String, val label: String, val parent: String? = null)

    val pages: List<Page> = LuluAppearanceCatalog.scopes.map { (id, label) -> Page(id, label) }
        .map { if (it.id == "Theater") it.copy(parent = "Reading") else it } + listOf(
        Page("ChatDetail", "聊天 · 对话", "Chat"),
        Page("GroupChat", "聊天 · 群聊", "ChatDetail"),
        Page("GroupSettings", "聊天 · 群聊设置", "GroupChat"),
        Page("GroupMembers", "聊天 · 群成员管理", "GroupSettings"),
        Page("GroupMemberPicker", "聊天 · 选群成员", "GroupSettings"),
        Page("GroupSearch", "聊天 · 群记录搜索", "GroupSettings"),
        Page("Favorites", "聊天 · 收藏", "Chat"),
        Page("PhoneInspection", "聊天 · 查手机", "ChatDetail"),
        Page("Moments", "朋友圈", "Meeting"),
        Page("PersonalTheater", "聊天 · 番外", "ChatDetail"),
        Page("CharacterCreate", "创建角色", "CharacterSettings"),
        Page("CoreMemory", "核心记忆", "Memory"),
        Page("Wishes", "阅读 · 旧入口", "Theater"),
        Page("SelfPreface", "自序"),
        Page("SelfThoughts", "自序 · 所思", "SelfPreface"),
        Page("SelfValues", "自序 · 所重", "SelfPreface"),
        Page("SelfWishes", "自序 · 所愿", "SelfPreface"),
        Page("SelfTodo", "自序 · 所行", "SelfPreface"),
        Page("CompassEditor", "自序 · 编辑", "SelfPreface"),
        Page("FocusTimer", "自序 · 专注计时", "Pomodoro"),
        Page("Models", "设置 · 模型", "Settings"),
        Page("Backup", "设置 · 备份", "Settings"),
        Page("Capabilities", "设置 · 手机能力", "Settings"),
        Page("Integrations", "设置 · 功能接入", "Settings"),
        Page("StrictMode", "设置 · 强制模式", "Settings"),
        Page("Vision", "设置 · 视觉", "Settings"),
        Page("AdvancedSettings", "设置 · 高级设置", "Settings"),
        Page("Community", "设置 · 许愿与支持", "Settings"),
        Page("CommunityWishes", "设置 · 许愿", "Community"),
        Page("CommunitySupport", "设置 · 打赏", "Community"),
        Page("VoiceSettings", "设置 · 语音", "AdvancedSettings"),
        Page("MemorySettings", "设置 · 记忆", "AdvancedSettings"),
        Page("ImageSettings", "设置 · 生图", "AdvancedSettings"),
        Page("Residents", "数字世界 · 居民管理", "Meeting"),
        Page("WorldArcade", "数字世界 · 游戏馆", "Meeting"),
        Page("WorldEpisode", "数字世界 · 事件记录", "Meeting"),
        Page("Browser", "角色手机 · 浏览器", "PhoneInspection"),
        Page("NoteReader", "角色手机 · 帖子阅读", "PhoneInspection"),
        Page("Guide", "指南"),
        Page("Playground", "Live2D"),
        Page("RolePhoneLive", "通话 · 电话直播", "Phone"),
        Page("GameTurtleSoup", "游戏 · 海龟汤", "Games"),
        Page("GameYachtDice", "游戏 · 快艇骰子", "Games"),
        Page("GameMemoryMatch", "游戏 · 记忆翻牌", "Games"),
        Page("GameGomoku", "游戏 · 五子棋", "Games"),
        Page("GamePerfectMan", "游戏 · 完美男友", "Games"),
        Page("GameRapportQuiz", "游戏 · 默契问答", "Games"),
        Page("GameRecords", "游戏 · 记录", "Games"),
        Page("GameReplay", "游戏 · 回放", "Games"),
        Page("GameParticipants", "游戏 · 选玩家", "Games"),
        Page("ApocalypseAbilities", "末世求生 · 异能设置", "ApocalypseSurvival"),
        Page("ApocalypseAbilityPicker", "末世求生 · 选异能", "ApocalypseSurvival"),
        Page("ApocalypseSettings", "末世求生 · 系统设置", "ApocalypseSurvival"),
        Page("ApocalypseWorld", "末世求生 · 世界", "ApocalypseSurvival"),
        Page("ApocalypseHistory", "末世求生 · 剧情记录", "ApocalypseSurvival"),
        Page("ApocalypseArchives", "末世求生 · 存档", "ApocalypseSurvival"),
        Page("ApocalypsePlotMemory", "末世求生 · 剧情记忆", "ApocalypseSurvival"),
        Page("ApocalypseMap", "末世求生 · 地图", "ApocalypseSurvival"),
    )

    private val byId = pages.associateBy(Page::id)
    private val sharedElements = setOf("page", "header", "body", "footer", "floating", "snackbar",
        "navigation", "title", "actions", "card", "primary-button", "text-input")
    private val desktopElements = setOf("launcher", "launcher-label", "companion", "desktop-dock",
        "widget-chat", "widget-bulletin", "widget-horoscope", "widget-note", "widget-countdown", "widget-planner",
        "widget-calendar", "widget-photo")
    private val chatElements = setOf("bubble", "bubble-user", "bubble-role", "messages", "composer",
        "composer-input", "composer-send", "composer-tools")

    fun lineage(scope: String): List<String> {
        val parent = byId[scope]?.parent ?: return listOf(scope)
        return lineage(parent) + scope
    }

    fun knowsSelector(selector: String): Boolean {
        if (selector == ":root") return true
        val scope = selector.takeIf { it.startsWith('#') }?.substringBefore(' ')?.drop(1)
        if (scope != null && scope !in byId) return false
        if ('.' !in selector) return true
        val name = selector.substringAfter('.')
        if (name in sharedElements) return true
        val allowed = scope?.let(::lineage).orEmpty()
        return ((scope == null || "Home" in allowed) && (name in desktopElements ||
            name.startsWith("app-"))) ||
            ((scope == null || "Chat" in allowed) && name in chatElements)
    }

    fun coverSelector(appId: String): String = "#Home .app-$appId"
}
