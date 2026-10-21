package com.jiacimu.lulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.CharacterIdentityStore
import com.jiacimu.lulu.data.ChatLexiconAutomation
import com.jiacimu.lulu.data.ChatMemoryAutomation
import com.jiacimu.lulu.data.ChatTurnConsistencyAutomation
import com.jiacimu.lulu.data.CommitmentLexiconSync
import com.jiacimu.lulu.data.CommitmentTaskStore
import com.jiacimu.lulu.data.CommitmentTurnAutomation
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.CompanionOnlineStore
import com.jiacimu.lulu.data.DeterministicMemoryAutomation
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.DigitalWorldStore
import com.jiacimu.lulu.data.LegacyConversationMigration
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.MemoryModelRuntime
import com.jiacimu.lulu.data.MemorySourceIntegrityRuntime
import com.jiacimu.lulu.data.MeetingExperienceStore
import com.jiacimu.lulu.data.MomentsStore
import com.jiacimu.lulu.data.ProactiveIncomingCallStore
import com.jiacimu.lulu.data.ProactivePerceptionPolicyStore
import com.jiacimu.lulu.data.ProactivePerceptionRuntime
import com.jiacimu.lulu.data.ProactivePerceptionScheduler
import com.jiacimu.lulu.data.RoleReadablePerformanceBridge
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.UserDataUpgradeGuard
import com.jiacimu.lulu.data.UserProfileContext
import com.jiacimu.lulu.games.LuluGames
import com.jiacimu.lulu.health.HealthCycleStore
import com.jiacimu.lulu.study.PomodoroCompanionSessions
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.SelfDirectedStudyPlanSeed
import com.jiacimu.lulu.study.StarWishStores
import com.jiacimu.lulu.study.StudyRemovedFeatureMigration
import com.jiacimu.lulu.system.LuluDeviceToolBridge

class MigrationActivity : ComponentActivity() {
    // Activity-scoped owner survives Settings -> Chat -> Settings navigation.
    // All XHS screens reuse the same WebView; it is never a singleton with a
    // leaked Activity and is destroyed only when this Activity is destroyed.
    internal val xhsAppBrowserSession by lazy { XhsAppBrowserSession(this) }

    override fun onDestroy() {
        if (xhsBrowserSessionInitialized) xhsAppBrowserSession.destroy()
        super.onDestroy()
    }

    // Android share-sheet note awaiting a recipient. Never treat a link as verified article content.
    private val incomingSharedNote = androidx.compose.runtime.mutableStateOf<String?>(null)
    // SingleTop receives activity-receipt links through onNewIntent. A fixed
    // onCreate snapshot leaves the current chat on-screen and makes links look inert.
    private val navigationIntent = androidx.compose.runtime.mutableStateOf<android.content.Intent?>(null)
    private val navigationRequestSerial = androidx.compose.runtime.mutableLongStateOf(0L)

    private fun incomingXhsText(intent: android.content.Intent?): String? {
        if (intent?.action != android.content.Intent.ACTION_SEND ||
            !intent.type.orEmpty().startsWith("text/")) return null
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.trim()?.take(3_000).orEmpty()
        val urls = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE).findAll(text)
        val isXhs = urls.any { match ->
            val host = runCatching {
                android.net.Uri.parse(match.value.trimEnd('。', '，', ',', ')')).host?.lowercase()
            }.getOrNull()
            host == "xhslink.com" || host?.endsWith(".xhslink.com") == true ||
                host == "xiaohongshu.com" || host?.endsWith(".xiaohongshu.com") == true
        }
        return text.takeIf { isXhs }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (StartupFailureRecovery.recovering) return
        navigationIntent.value = intent
        navigationRequestSerial.longValue += 1L
        val incomingCall = ProactiveIncomingCallStore.pending.value?.active() == true
        setIncomingCallWindow(incomingCall)
        incomingXhsText(intent)?.let { incomingSharedNote.value = it }
    }

    private var xhsBrowserSessionInitialized = false
    internal fun browserSession(): XhsAppBrowserSession {
        xhsBrowserSessionInitialized = true
        return xhsAppBrowserSession
    }

    /**
     * Device/OEM foreground-service, WebView and alarm failures are not reasons
     * to crash the Activity or destroy character data. Every sub-system keeps
     * its own failure receipt, and the desktop can still be opened.
     */
    private fun resumePeripheral(stage: String, action: () -> Unit) {
        try {
            action()
        } catch (fatal: ThreadDeath) {
            throw fatal
        } catch (error: Exception) {
            StartupFailureRecovery.recordWarning(applicationContext, stage, error)
        } catch (error: LinkageError) {
            StartupFailureRecovery.recordWarning(applicationContext, stage, error)
        }
    }

    override fun onResume() {
        super.onResume()
        if (StartupFailureRecovery.recovering) return
        resumePeripheral("小红书前台会话恢复") {
            com.jiacimu.lulu.data.XhsReadingEngine.resume(this)
        }
        resumePeripheral("小红书 WebView 预热") {
            if (com.jiacimu.lulu.data.XhsReadingEngine.localEnabled(this)) {
                window.decorView.post {
                    if (!isFinishing && !isDestroyed &&
                        com.jiacimu.lulu.data.XhsReadingEngine.localEnabled(this)) {
                        resumePeripheral("小红书 WebView 加载") {
                            browserSession().prepareForForeground()
                        }
                    }
                }
            }
        }
        resumePeripheral("用户在线状态") {
            com.jiacimu.lulu.data.UserInteractionPresenceStore.initialize(applicationContext)
            com.jiacimu.lulu.data.UserInteractionPresenceStore.setAppVisible(true)
        }
        resumePeripheral("后台陪伴前台服务（受 Android 设备限制）") {
            com.jiacimu.lulu.data.BackgroundCompanionControl.refresh(applicationContext)
        }
        resumePeripheral("角色前台感知检查") {
            ProactivePerceptionScheduler.startForegroundChecks(applicationContext)
        }
        resumePeripheral("精确闹钟恢复") {
            com.jiacimu.lulu.system.LuluAlarmSystem.initialize(applicationContext)
            CommitmentTaskStore.reconcileAfterRestart()
        }
        resumePeripheral("学习计时与番茄钟") {
            val studyStore = PostgraduateExamStores.main
            val minutes = studyStore.state.value.pomodoro.selectedMinutes
            if (studyStore.syncPomodoroClock())
                PomodoroCompanionSessions.handleNaturalCompletion(studyStore, minutes)
            PomodoroCompanionSessions.syncCountUpClock()
        }
    }

    override fun onPause() {
        if (StartupFailureRecovery.recovering) {
            super.onPause()
            return
        }
        com.jiacimu.lulu.data.XhsReadingEngine.pause(this)
        com.jiacimu.lulu.data.UserInteractionPresenceStore.setAppVisible(false)
        ProactivePerceptionScheduler.stopForegroundChecks()
        super.onPause()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!StartupFailureRecovery.recovering &&
            event.actionMasked == android.view.MotionEvent.ACTION_DOWN)
            com.jiacimu.lulu.data.UserInteractionPresenceStore.touch()
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        StartupFailureRecovery.install(appContext)
        StartupFailureRecovery.latest(appContext)?.let { report ->
            StartupFailureRecovery.display(this, report)
            return
        }
        try {
        StartupFailureRecovery.stage("启动安全与旧数据保护")
        UserDataUpgradeGuard.protectBeforeStoresInitialize(appContext)
        LuluAppPreferencesStore.initialize(appContext)
        com.jiacimu.lulu.data.LuluAppearanceStore.initialize(appContext)
        UserProfileContext.initialize(appContext)
        DigitalLifeProfileStore.initialize(appContext)
        LuluRepositories.initialize(appContext)
        LuluRepositories.lexicon.initialize(appContext)
        LuluRepositories.worldBook.initialize(appContext)
        LegacyConversationMigration.migrateSavedState(appContext)
        StartupFailureRecovery.stage("原始时间线与人物配置")
        SharedExperienceTimeline.initialize(appContext)
        MigratedDomainStores.initialize(appContext)
        CharacterIdentityStore.initialize(appContext)
        DigitalWorldStore.initialize(appContext)
        MomentsStore.initialize(appContext)
        SharedExperienceTimeline.backfillChatHistory()
        CompanionPresenceStore.initialize(appContext)
        com.jiacimu.lulu.data.CharacterDevelopmentStore.initialize(appContext)
        com.jiacimu.lulu.data.CharacterLifeStore.initialize(appContext)
        com.jiacimu.lulu.data.CharacterInnerLifeStore.initialize(appContext)
        com.jiacimu.lulu.data.CharacterDevelopmentRuntime.initialize(appContext)
        com.jiacimu.lulu.data.PerceptionStimulusLedger.initialize(appContext)
        CompanionOnlineStore.initialize(appContext)
        ProactiveIncomingCallStore.initialize(appContext)
        val incomingCall = ProactiveIncomingCallStore.pending.value?.active() == true
        setIncomingCallWindow(incomingCall)
        MeetingExperienceStore.initialize(appContext)
        HealthCycleStore.initialize(appContext)
        StartupFailureRecovery.stage("角色模型、记忆与设备能力")
        LuluAiServices.initialize(appContext)
        // Past genuine feedback remains available in the shared timeline and
        // growth trail. Do not re-enact old arguments with a fresh API call on
        // every app start.
        MemoryModelRuntime.initialize(appContext)
        UserDataUpgradeGuard.refreshBackup(appContext)
        com.jiacimu.lulu.data.CloudTaskBridge.initialize(appContext)
        LuluDeviceToolBridge.initialize(appContext)
        ChatAutoVoicePlayback.initialize(appContext)
        LuluGames.initialize(appContext)
        StudyRemovedFeatureMigration.migrate(appContext)
        StartupFailureRecovery.stage("学习记录与番茄钟")
        PostgraduateExamStores.initialize(appContext)
        SelfDirectedStudyPlanSeed.migrate(appContext, PostgraduateExamStores.main)
        StarWishStores.initialize(appContext)
        PomodoroCompanionSessions.initialize(appContext)

        val restoredMinutes = PostgraduateExamStores.main.state.value.pomodoro.selectedMinutes
        if (PostgraduateExamStores.main.syncPomodoroClock()) {
            PomodoroCompanionSessions.handleNaturalCompletion(
                studyStore = PostgraduateExamStores.main,
                actualMinutes = restoredMinutes,
            )
        }

        RoleReadablePerformanceBridge.initialize()
        ChatTurnConsistencyAutomation.initialize(appContext)
        DeterministicMemoryAutomation.initialize(appContext)
        ChatMemoryAutomation.initialize(appContext)
        MemorySourceIntegrityRuntime.initialize()
        ChatLexiconAutomation.initialize(appContext)
        CommitmentTaskStore.initialize(appContext)
        resumePeripheral("已存约定与系统闹钟恢复") {
            CommitmentTaskStore.reconcileAfterRestart()
        }
        CommitmentLexiconSync.initialize()
        CommitmentTurnAutomation.initialize(appContext)

        ProactivePerceptionPolicyStore.initialize(appContext)
        StartupFailureRecovery.stage("角色感知与后台调度")
        ProactivePerceptionRuntime.initialize(appContext)
        resumePeripheral("后台角色唤醒排程初始化") {
            ProactivePerceptionScheduler.schedule(appContext)
        }

        incomingSharedNote.value = incomingXhsText(intent)
        navigationIntent.value = intent
        com.jiacimu.lulu.study.ReadingReflectionStore.initialize(appContext)
        StartupFailureRecovery.stage("桌面 Compose 首次渲染")
        setContent {
            val requestedNavigation = navigationIntent.value
            LuluMigrationRootAppV2(
                initialConversationId = requestedNavigation?.getStringExtra("open_conversation_id"),
                initialRouteName = requestedNavigation?.getStringExtra("open_route"),
                initialTargetCharacterId = requestedNavigation?.getStringExtra("open_character_id"),
                initialDiaryTitle = requestedNavigation?.getStringExtra("open_diary_title"),
                initialLexiconSection = requestedNavigation?.getStringExtra("open_lexicon_section"),
                initialFavoriteEntryId = requestedNavigation?.getStringExtra("open_favorite_entry_id"),
                initialReadingTitle = requestedNavigation?.getStringExtra("open_reading_title"),
                initialMeetingInvitationText = requestedNavigation?.getStringExtra("open_meeting_invitation_text"),
                initialMeetingLocation = requestedNavigation?.getStringExtra("open_meeting_location"),
                initialMeetingInvitationId = requestedNavigation?.getStringExtra("open_meeting_invitation_id"),
                navigationRequestId = navigationRequestSerial.longValue,
                incomingSharedNote = incomingSharedNote.value,
                onSharedNoteHandled = { incomingSharedNote.value = null },
            )
            ProactiveIncomingCallOverlay()
        }
        window.decorView.postDelayed({
            if (!StartupFailureRecovery.recovering) StartupFailureRecovery.ready()
        }, 5_000L)
        } catch (error: Throwable) {
            // Keep all existing stores intact when the new version fails.
            StartupFailureRecovery.recordCurrent(appContext, error)
            StartupFailureRecovery.display(this,
                StartupFailureRecovery.latest(appContext)
                    ?: (error.javaClass.name + ": " + error.message.orEmpty()))
        }
    }
}
