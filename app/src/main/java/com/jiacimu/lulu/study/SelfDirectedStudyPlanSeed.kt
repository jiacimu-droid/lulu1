package com.jiacimu.lulu.study

import android.content.Context
import java.time.LocalDate

/** Installs the current user-approved month/week plan without generating a fixed daily timetable. */
object SelfDirectedStudyPlanSeed {
    private const val PREFS = "lulu_study_plan_migrations"
    private const val KEY = "rolling_plan_v5"
    private const val KEY_SYNC_DATE = "rolling_plan_last_sync_date"

    fun migrate(context: Context, store: PostgraduateExamStore) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY, false)) {
            store.removePlanItemsByTitle(legacyPlanTitles)
            store.replaceRollingPlanItems(RollingStudyPlan.itemsFor(LocalDate.now()))
            // Old generated clock-by-clock schedules conflict with the weekly responsibility boundary.
            store.clearSchedule()
            prefs.edit()
                .putBoolean(KEY, true)
                .putString(KEY_SYNC_DATE, LocalDate.now().toString())
                .apply()
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
        "今天先从周计划里的P0专业课主线选一个块开始；4小时是低状态保底，5小时是常态目标，状态好再向6小时延伸。",
        "专业课优先顺序：未完成的新课/真实尾量 → 当轮背诵 → 章节题与错题 → 真题输出；不要为了凑进度跳章。",
        "英语现在是机动换脑项：专业课学累时做20—40分钟单词、阅读或翻译即可，不设硬性日任务。",
        "10月15日前政治不占固定学习时段；从10月15日起再正式建立政治选择题和基础课节奏。",
        "结束前记录有效学习分钟、完成到哪一章和一个主要卡点；没完成的P0任务回到下周任务池，先滚动续做而不是熬夜惩罚式补课。",
    )

    private val legacyPlanTitles = setOf(
        "完成本周英语真题与错题复盘", "专业课推进到本周节点", "完成当月专业课阶段目标", "整理当月英语错误类型",
        "8月：刑法收口、法理第一轮、民法启动", "9月：民法主线收口、综合课推进、政治启动",
        "10月：常规新课收口、五科二轮与真题化", "11月：第三轮闭卷、答题纸和整卷", "12月：模拟、熟题保温与作息校准",
        "8月：法理先背、刑法收课、民法接续", "9月：民法课程主线、刑法第一轮与法理回顾",
        "10月：中旬前完成新课并建立完整输出闭环", "11月：第三轮闭卷输出、真题与答题纸训练",
        "12月：全真模拟、漏洞压缩与考前保温",
        "7月29日—8月2日：刑法第10章收口", "8月3日—8月9日：刑法第11—15章推进",
        "8月10日—8月16日：刑法第16—20章推进", "8月17日—8月23日：刑法第21—25章与诊断",
        "8月24日—8月30日：按刑法收口结果复算",
    )
}
