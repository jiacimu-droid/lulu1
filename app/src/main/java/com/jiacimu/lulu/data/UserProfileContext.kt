package com.jiacimu.lulu.data

import android.content.Context

object UserProfileContext {
    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE)
    }

    fun promptSection(): String {
        val source = prefs
        val fields = if (source == null) {
            emptyList()
        } else {
            listOfNotNull(
                source.getString("display_name", "")?.trim()?.takeIf(String::isNotBlank)?.let { "名字：$it" },
                source.getString("preferred_name", "")?.trim()?.takeIf(String::isNotBlank)?.let { "希望角色称呼：$it" },
                source.getString("birthday", "")?.trim()?.takeIf(String::isNotBlank)?.let { "生日：$it" },
                source.getString("location", "")?.trim()?.takeIf(String::isNotBlank)?.let { "所在地：$it" },
                source.getString("bio", "")?.trim()?.takeIf(String::isNotBlank)?.let { "个人信息：$it" },
            )
        }
        return buildString {
            appendLine("用户与现实设备归属规则：")
            appendLine("- 露露机通过 Android 系统、无障碍、通知权限、定位、手环或学习 App 读取到的电量、充电状态、前台应用、屏幕/应用活动、通知、位置、健康数据和学习状态，默认全部属于用户本人以及用户正在使用的现实设备。")
            appendLine("- 这些数据不是角色自己的手机、角色自己的通知、角色自己的屏幕或角色自己的身体数据。除非上下文明确另外提供了‘角色侧设备/角色本人数据’，否则角色不得用第一人称认领。")
            appendLine("- 例如‘前台应用=抖音/短视频’表示用户可能正在刷视频，不表示角色自己在刷；‘电量=20%’表示用户手机电量，不表示角色手机电量；通知内容表示用户设备收到的通知，不表示角色收到了同一条通知。")
            appendLine("- 角色可以把这些信息当作观察用户现实状态的线索来理解、关心或选择是否互动，但不能把用户行为改写成自己的行为，也不能因为看到了设备线索就虚构更多未提供事实。")
            if (fields.isNotEmpty()) {
                appendLine()
                appendLine("用户填写的个人资料：")
                append(fields.joinToString("\n"))
                appendLine()
                append("这些只是资料事实，不代表角色与用户的关系，也不授权默认使用‘主人’等称呼。")
            }
        }.trim()
    }

    fun displayLabel(): String {
        val source = prefs ?: return "用户"
        return source.getString("preferred_name", "").orEmpty().trim()
            .ifBlank { source.getString("display_name", "").orEmpty().trim() }
            .ifBlank { "用户" }
    }
}
