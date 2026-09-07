package com.jiacimu.lulu.data

/** A recorded utterance proves it was said, not that its described event happened. */
val SharedTimelineEvent.evidenceLabel: String
    get() = when {
        channel.contains("演绎") || channel.contains("跑团") || channel.contains("小剧场") || channel.contains("番外") -> "虚构体验，不是主世界经历"
        channel.contains("日记") || channel.contains("心声") || channel.contains("此刻") || channel.contains("朋友圈") -> "主观表达，内容待事实佐证"
        channel.startsWith("独自阅读") -> "阅读进度为执行事实，感想为主观表达"
        id.startsWith("world-fact-") -> "程序执行或环境变化"
        channel.startsWith("数字世界") -> "世界经历记录；旧记录需与当前状态核对，不证明仍在发生"
        channel == "私聊" || channel.startsWith("群聊") || channel.contains("电话") -> "对话记录，仅证明说过"
        else -> "来源记录，按语境判断，不自动当作亲历事实"
    }

val SharedTimelineEvent.evidenceContent: String
    get() = "[$evidenceLabel] $content"
