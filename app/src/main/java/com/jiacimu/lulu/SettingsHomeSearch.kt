package com.jiacimu.lulu

import java.util.Locale

/**
 * The settings index is navigation metadata, not a second settings store.
 * Each entry still opens its existing destination and all state stays there.
 */
internal data class SettingsHomeEntry(
    val id: String,
    val section: String,
    val title: String,
    val subtitle: String,
    val aliases: String = "",
)

internal val settingsHomeEntries: List<SettingsHomeEntry> = listOf(
    SettingsHomeEntry(
        "api", "模型与连接", "API 与模型",
        "聊天站点、模型选择与默认聊天",
        "模型存档 模型分工 兜底模型 线路 中转站 默认模型 聊天",
    ),
    SettingsHomeEntry(
        "integrations", "模型与连接", "联网与扩展",
        "MCP、小红书与外部服务连接",
        "搜索 网页 阅读 浏览器 小红书 设备 手环 插件 网络 MCP",
    ),
    SettingsHomeEntry(
        "voice", "模型与连接", "语音与音色",
        "语音合成、自动朗读和播放习惯",
        "TTS 电话 音色 配音 语速 音调 ElevenLabs 声音",
    ),
    SettingsHomeEntry(
        "memory", "模型与连接", "记忆与检索",
        "记忆抽取、向量检索和重排",
        "Embedding Rerank 重排序 召回 记忆模型",
    ),
    SettingsHomeEntry(
        "residents", "角色与设备", "居民管理",
        "居民活动安排与日常次数",
        "NPC 数字世界 活动预算 居民预算",
    ),
    SettingsHomeEntry(
        "vision", "角色与设备", "识图设置",
        "角色阅读图片时的视觉模型",
        "视觉 看图 照片 图片理解 朋友圈",
    ),
    SettingsHomeEntry(
        "image", "角色与设备", "生图设置",
        "图片生成接口、模型和尺寸",
        "绘图 画图 图像生成 生成图片",
    ),
    SettingsHomeEntry(
        "capabilities", "角色与设备", "权限与能力",
        "通知、闹钟、屏幕感知与后台权限",
        "位置 应用感知 无障碍 电量 耗电 自动运行 唤醒",
    ),
    SettingsHomeEntry(
        "backup", "数据与安全", "备份与恢复",
        "保存数据，管理自动与云备份",
        "导出 导入 数据迁移 恢复存档 保存 云端",
    ),
)

internal val settingsHomeSections = listOf("模型与连接", "角色与设备", "数据与安全")

/** All search words must match. Search only presentation metadata, never private data. */
internal fun filterSettingsHomeEntries(
    entries: List<SettingsHomeEntry>,
    query: String,
): List<SettingsHomeEntry> {
    val terms = query.trim().lowercase(Locale.ROOT)
        .split(Regex("\\s+")).filter { it.isNotBlank() }
    if (terms.isEmpty()) return entries
    return entries.filter { entry ->
        val haystack = listOf(entry.title, entry.subtitle, entry.section, entry.aliases)
            .joinToString(" ").lowercase(Locale.ROOT)
        terms.all(haystack::contains)
    }
}
