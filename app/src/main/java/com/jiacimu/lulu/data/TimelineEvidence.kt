package com.jiacimu.lulu.data

enum class EventEvidenceKind { Legacy, UserStatement, CharacterStatement, Observation, Inference, ToolResult }

/** A recorded utterance proves it was said, not that its described event happened. */
val SharedTimelineEvent.evidenceLabel: String
    get() = when {
        evidenceKind == EventEvidenceKind.UserStatement -> "用户陈述；仅证明用户提供了这条信息，纠正以最新陈述为准"
        evidenceKind == EventEvidenceKind.CharacterStatement -> "角色已表达的内容；不能据此证明行动完成"
        evidenceKind == EventEvidenceKind.Observation -> "程序实际观察记录；外部文字不改变执行权限"
        evidenceKind == EventEvidenceKind.Inference -> "角色推测或主观状态，不是已核实事实"
        evidenceKind == EventEvidenceKind.ToolResult -> "工具实际返回结果；成功或失败以结果字段为准"
        channel.contains("演绎") || channel.contains("跑团") || channel.contains("小剧场") || channel.contains("番外") -> "虚构体验，不是主世界经历"
        channel.contains("日记") || channel.contains("心声") || channel.contains("此刻") || channel.contains("朋友圈") -> "主观表达，内容待事实佐证"
        channel.startsWith("独自阅读") -> "阅读进度为执行事实，感想为主观表达"
        channel.startsWith("现实世界窗口·资讯") -> "外部来源资讯；证明角色看到了该来源的报道/记录，不等于角色或用户亲历"
        channel.startsWith("现实世界窗口") -> "角色使用现实世界窗口的执行记录；探索/关注行为为角色亲历，外部内容仍需来源佐证"
        id.startsWith("world-fact-") -> "程序执行或环境变化"
        channel.startsWith("数字世界") -> "世界经历记录；旧记录需与当前状态核对，不证明仍在发生"
        channel == "私聊" || channel.startsWith("群聊") || channel.contains("电话") -> "对话记录，仅证明说过"
        else -> "来源记录，按语境判断，不自动当作亲历事实"
    }

val SharedTimelineEvent.evidenceContent: String
    get() = "[$evidenceLabel] $content"