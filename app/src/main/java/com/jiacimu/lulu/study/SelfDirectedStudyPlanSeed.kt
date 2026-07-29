package com.jiacimu.lulu.study

import android.content.Context

/** Installs the current user-approved month/week plan once without generating a fixed daily timetable. */
object SelfDirectedStudyPlanSeed {
    private const val PREFS = "lulu_study_plan_migrations"
    private const val KEY = "self_directed_plan_v1"

    fun migrate(context: Context, store: PostgraduateExamStore) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        val existing = store.state.value.planItems.mapTo(mutableSetOf()) { it.title }

        monthlyPlans.forEach { (title, note) ->
            if (title !in existing) store.addPlanItem(StudyPlanRange.Monthly, title, note)
        }
        weeklyPlans.forEach { (title, note) ->
            if (title !in existing) store.addPlanItem(StudyPlanRange.Weekly, title, note)
        }
        val currentTips = store.state.value.tips.mapTo(mutableSetOf()) { it.text }
        dailyReminders.forEach { reminder ->
            if (reminder !in currentTips) store.addTip(reminder)
        }
        // Old generated clock-by-clock schedules conflict with the new responsibility boundary.
        store.clearSchedule()
        prefs.edit().putBoolean(KEY, true).apply()
    }

    private val monthlyPlans = listOf(
        "8月：刑法收口、法理第一轮、民法启动" to "刑法第11—25章按真实课时推进；法理完成第一轮目录与关键词；英语每周阅读3篇、完形2次、新题型和翻译各1次。",
        "9月：民法主线收口、综合课推进、政治启动" to "民法作为唯一大新课主线；法理二轮、刑法一轮、民法滚动背诵；专业课每周至少2次主观题列点。",
        "10月：常规新课收口、五科二轮与真题化" to "宪法和法制史收口后不再开大体量新课；英语大小作文每周各1次完整写改；政治争取完成1000题一刷。",
        "11月：第三轮闭卷、答题纸和整卷" to "专业基础、专业综合轮换限时卷；英语每周至少1套真题整卷并复盘；政治错题二刷和模拟。",
        "12月：模拟、熟题保温与作息校准" to "专业课、英语、政治完成考场节奏训练，考前逐步降量，不大面积开新题。",
    )

    private val weeklyPlans = listOf(
        "7月29日—8月2日：刑法第10章收口" to "补齐第8—10章题目、主错因与框架；法理背诵2—3章或等量内容；英语2篇阅读和1个小三门训练块。",
        "8月3日—8月9日：刑法第11—15章推进" to "目标完成4—5章完整闭环；法理第一轮3—4小时；英语阅读3篇、完形2次、新题型与翻译各1次。",
        "8月10日—8月16日：刑法第16—20章推进" to "完成4—5章闭环；第11—15章错题回炉；刑法已学章节启动目录与关键词背诵。",
        "8月17日—8月23日：刑法第21—25章与诊断" to "刑法收口并完成混合选择题加主观题骨架；法理第一轮争取收口；刑法完成后再启动民法。",
        "8月24日—8月30日：按刑法收口结果复算" to "已收口则启动民法；未收口则先清真实欠账。两条大新课主线不同时开启。",
    )

    private val dailyReminders = listOf(
        "建议做10—20分钟轻运动或广播体操；饭后困时先走动、拉伸或整理桌面。",
        "从本周专业课任务池中自行选择一个主线块：看课、做题闭环或背诵输出。",
        "完成单词滚动复习，并从本周英语任务池中自行选择一个主训练块。",
        "结束前记录有效学习分钟和一个主要卡点；未完成内容回到周任务池，不惩罚式补课。",
    )
}
