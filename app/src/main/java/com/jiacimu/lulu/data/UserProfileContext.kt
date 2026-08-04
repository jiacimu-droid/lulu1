package com.jiacimu.lulu.data

import android.content.Context

object UserProfileContext {
    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE)
    }

    fun promptSection(): String {
        val source = prefs ?: return ""
        val fields = listOfNotNull(
            source.getString("display_name", "")?.trim()?.takeIf(String::isNotBlank)?.let { "名字：$it" },
            source.getString("preferred_name", "")?.trim()?.takeIf(String::isNotBlank)?.let { "希望角色称呼：$it" },
            source.getString("birthday", "")?.trim()?.takeIf(String::isNotBlank)?.let { "生日：$it" },
            source.getString("location", "")?.trim()?.takeIf(String::isNotBlank)?.let { "所在地：$it" },
            source.getString("bio", "")?.trim()?.takeIf(String::isNotBlank)?.let { "个人信息：$it" },
        )
        return if (fields.isEmpty()) "" else buildString {
            appendLine("用户填写的个人资料：")
            append(fields.joinToString("\n"))
            appendLine()
            append("这些只是资料事实，不代表角色与用户的关系，也不授权默认使用‘主人’等称呼。")
        }
    }

    fun displayLabel(): String {
        val source = prefs ?: return "用户"
        return source.getString("preferred_name", "").orEmpty().trim()
            .ifBlank { source.getString("display_name", "").orEmpty().trim() }
            .ifBlank { "用户" }
    }
}
