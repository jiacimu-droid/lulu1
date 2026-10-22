package com.jiacimu.lulu.data

import android.content.Context

object UserProfileContext {
    private var prefs: android.content.SharedPreferences? = null
    private var application: Context? = null

    fun initialize(context: Context) {
        application = context.applicationContext
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE)
    }

    /**
     * Only device-verified abilities and objective call receipts belong here.
     * It is read by the shared model gateway for chat, phone and autonomous
     * decisions; it does not generate another model request or invented memory.
     */
    fun live2dCallContext(characterId: String): String {
        if (characterId.isBlank()) return ""
        val installed = application?.let { app ->
            com.jiacimu.lulu.Live2DAssets.modelUrl(app, characterId) != null
        } == true
        val coreReady = application?.let { app ->
            com.jiacimu.lulu.Live2DAssets.hasCore(app)
        } == true
        val call = com.jiacimu.lulu.LuluVoiceCallSession.state.value
        val active = call.connected && call.characterId == characterId
        val videoSelected = active &&
            com.jiacimu.lulu.LuluCallWindowController.videoRequested.value
        val actualVisible = videoSelected &&
            com.jiacimu.lulu.Live2DVisualActionBus.isStageVisible(
                characterId, call.callExperienceId)
        val rendered = SharedExperienceTimeline.recentEvents(characterId, 320)
            .lastOrNull {
                it.source == InteractionSignalBridge.SOURCE &&
                    it.id.startsWith("interaction-call-video-rendered-") &&
                    it.evidenceKind == EventEvidenceKind.Observation
            }
        return buildString {
            appendLine("【露露机当前视频能力与该角色亲历的通话事实】")
            if (installed && coreReady) {
                appendLine("本角色手机端已导入 Live2D 模型与渲染 Core，可以在露露机内使用 Live2D 虚拟视频通话；不是只能语音电话或数字世界见面。")
            } else if (installed) {
                appendLine("本角色已导入 Live2D 模型，但当前渲染 Core 尚未就绪；视频需要先完成组件加载。")
            } else {
                appendLine("露露机支持 Live2D 虚拟视频通话，但本角色当前没有可用的已导入 Live2D 模型；不要把尚未配置说成整个应用不能视频。")
            }
            if (active) {
                when {
                    actualVisible -> appendLine("此刻正处于已接通的 Live2D 视频电话，角色数字画面已显示在用户手机上。")
                    videoSelected -> appendLine("此刻电话已开启 Live2D 视频模式，画面尚未确认成功渲染。")
                    else -> appendLine("此刻正处于语音电话模式，没有开启视频画面；历史视频经历仍然真实存在。")
                }
            }
            if (rendered != null) {
                appendLine("本角色亲历的视频回执：${rendered.occurredAt}，会话证据ID=${rendered.id}。那次角色自己的 Live2D 形象确实成功显示在用户手机上，不能再把它当作只有语音的电话。")
            }
            appendLine("关键区别：用户看见角色的数字形象，并不意味着角色可以看见用户本人。只有用户主动开启摄像头共享、识图结果确实返回时，才能描述用户真实画面；不得捏造实时连续视觉。")
            appendLine("这只是设备能力和事件事实，不要求角色主动提及，也不规定人设或说话风格；当前用户的新指令与真实权限优先。")
        }.trim()
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
