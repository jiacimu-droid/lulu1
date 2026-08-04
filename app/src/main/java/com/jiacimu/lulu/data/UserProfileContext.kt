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
        return if (fields.isEmpty()) "" else "主人填写的个人资料：\n${fields.joinToString("\n")}" 
    }
}
