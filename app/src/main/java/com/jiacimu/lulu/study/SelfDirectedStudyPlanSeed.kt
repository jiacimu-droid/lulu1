package com.jiacimu.lulu.study

import android.content.Context
import java.time.LocalDate

/** Installs the current user-approved month/week plan without generating a fixed daily timetable. */
object SelfDirectedStudyPlanSeed {
    private const val PREFS = "lulu_study_plan_migrations"
    private const val KEY = "rolling_plan_v2"
    private const val KEY_SYNC_DATE = "rolling_plan_last_sync_date"

    fun migrate(context: Context, store: PostgraduateExamStore) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY, false)) {
            store.removePlanItemsByTitle(legacyPlanTitles)
            store.replaceRollingPlanItems(RollingStudyPlan.itemsFor(LocalDate.now()))
            // Old generated clock-by-clock schedules conflict with the new responsibility boundary.
            store.clearSchedule()
            prefs.edit().putBoolean(KEY, true).putString(KEY_SYNC_DATE, LocalDate.now().toString()).apply()
        }
        syncRollingPlan(context, store)
        ensureDailyReminders(store)
    }

    fun syncRollingPlan(context: Context, store: PostgraduateExamStore, date: LocalDate = LocalDate.now()) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SYNC_DATE, null) != date.toString()) {
            store.replaceRollingPlanItems(RollingStudyPlan.itemsFor(date))
            prefs.edit().putString(KEY_SYNC_DATE, date.toString()).apply()
        }
    }

    /**
     * Short reminders are daily guidance, not a one-time migration payload.
     * Re-entering the study app on a new date fills only that date and never duplicates existing tips.
     */
    fun ensureDailyReminders(
        store: PostgraduateExamStore,
        date: LocalDate = LocalDate.now(),
    ) {
        val currentTips = store.state.value.tips
            .filter { tip -> tip.date == date.toString() }
            .mapTo(mutableSetOf()) { tip -> tip.text }
        dailyReminders.forEach { reminder ->
            if (reminder !in currentTips) store.addTip(reminder, date)
        }
    }

    private val dailyReminders = listOf(
        "建议做10—20分钟轻运动或广播体操；饭后困时先走动、拉伸或整理桌面。",
        "从本周专业课任务池中自行选择一个主线块：看课、做题闭环或背诵输出。",
        "完成单词滚动复习，并从本周英语任务池中自行选择一个主训练块。",
        "结束前记录有效学习分钟和一个主要卡点；未完成内容回到周任务池，不惩罚式补课。",
    )

    private val legacyPlanTitles = setOf(
        "完成本周英语真题与错题复盘", "专业课推进到本周节点", "完成当月专业课阶段目标", "整理当月英语错误类型",
        "8月：刑法收口、法理第一轮、民法启动", "9月：民法主线收口、综合课推进、政治启动",
        "10月：常规新课收口、五科二轮与真题化", "11月：第三轮闭卷、答题纸和整卷", "12月：模拟、熟题保温与作息校准",
        "7月29日—8月2日：刑法第10章收口", "8月3日—8月9日：刑法第11—15章推进",
        "8月10日—8月16日：刑法第16—20章推进", "8月17日—8月23日：刑法第21—25章与诊断",
        "8月24日—8月30日：按刑法收口结果复算",
    )
}
