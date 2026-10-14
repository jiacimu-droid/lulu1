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
        incomingXhsText(intent)?.let { incomingSharedNote.value = it }
    }

    private var xhsBrowserSessionInitialized = false
    internal fun browserSession(): XhsAppBrowserSession {
        xhsBrowserSessionInitialized = true
        return xhsAppBrowserSession
    }

    override fun onResume() {
        super.onResume()
        com.jiacimu.lulu.data.XhsReadingEngine.resume(this)
        // Warm the single Activity-owned browser only while Lulu is foregrounded.
        // A character can then read using the same logged-in WebView even when
        // the user is in chat; Android may pause it when the app is backgrounded.
        if (com.jiacimu.lulu.data.XhsReadingEngine.localEnabled(this)) {
            window.decorView.post {
                if (!isFinishing && !isDestroyed &&
                    com.jiacimu.lulu.data.XhsReadingEngine.localEnabled(this)) {
                    browserSession().prepareForForeground()
                }
            }
        }
        com.jiacimu.lulu.data.UserInteractionPresenceStore.initialize(applicationContext)
        com.jiacimu.lulu.data.UserInteractionPresenceStore.setAppVisible(true)
        ProactivePerceptionScheduler.startForegroundChecks(applicationContext)
        // Returning from Android's exact-alarm permission screen upgrades pending wake-ups
        // from best-effort to exact without requiring a reboot or changing the original time.
        com.jiacimu.lulu.system.LuluAlarmSystem.initialize(applicationContext)
        // Android may suspend the UI ticker in the background. The saved deadline, not
        // the number of delivered ticks, determines the remaining time on return.
        val studyStore = PostgraduateExamStores.main
        val minutes = studyStore.state.value.pomodoro.selectedMinutes
        if (studyStore.syncPomodoroClock()) {
            PomodoroCompanionSessions.handleNaturalCompletion(studyStore, minutes)
        }
        PomodoroCompanionSessions.syncCountUpClock()
    }

    override fun onPause() {
        com.jiacimu.lulu.data.XhsReadingEngine.pause(this)
        com.jiacimu.lulu.data.UserInteractionPresenceStore.setAppVisible(false)
        ProactivePerceptionScheduler.stopForegroundChecks()
        super.onPause()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN)
            com.jiacimu.lulu.data.UserInteractionPresenceStore.touch()
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        UserDataUpgradeGuard.protectBeforeStoresInitialize(appContext)
        LuluAppPreferencesStore.initialize(appContext)
        UserProfileContext.initialize(appContext)
        DigitalLifeProfileStore.initialize(appContext)
        LuluRepositories.initialize(appContext)
        LuluRepositories.lexicon.initialize(appContext)
        LuluRepositories.worldBook.initialize(appContext)
        LegacyConversationMigration.migrateSavedState(appContext)
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
        MeetingExperienceStore.initialize(appContext)
        HealthCycleStore.initialize(appContext)
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
        CommitmentTaskStore.reconcileAfterRestart()
        CommitmentLexiconSync.initialize()
        CommitmentTurnAutomation.initialize(appContext)

        ProactivePerceptionPolicyStore.initialize(appContext)
        ProactivePerceptionRuntime.initialize(appContext)
        ProactivePerceptionScheduler.schedule(appContext)

        incomingSharedNote.value = incomingXhsText(intent)
        val initialConversationId = intent?.getStringExtra("open_conversation_id")
        val initialRouteName = intent?.getStringExtra("open_route")
        val initialCharacterId = intent?.getStringExtra("open_character_id")
        val initialDiaryTitle = intent?.getStringExtra("open_diary_title")
        val initialLexiconSection = intent?.getStringExtra("open_lexicon_section")
        com.jiacimu.lulu.study.ReadingReflectionStore.initialize(appContext)
        val initialReadingTitle = intent?.getStringExtra("open_reading_title")
        val initialMeetingInvitationText = intent?.getStringExtra("open_meeting_invitation_text")
        val initialMeetingLocation = intent?.getStringExtra("open_meeting_location")
        val initialMeetingInvitationId = intent?.getStringExtra("open_meeting_invitation_id")
        setContent {
            LuluMigrationRootAppV2(
                initialConversationId = initialConversationId,
                initialRouteName = initialRouteName,
                initialTargetCharacterId = initialCharacterId,
                initialDiaryTitle = initialDiaryTitle,
                initialLexiconSection = initialLexiconSection,
                initialReadingTitle = initialReadingTitle,
                initialMeetingInvitationText = initialMeetingInvitationText,
                initialMeetingLocation = initialMeetingLocation,
                initialMeetingInvitationId = initialMeetingInvitationId,
                incomingSharedNote = incomingSharedNote.value,
                onSharedNoteHandled = { incomingSharedNote.value = null },
            )
            ProactiveIncomingCallOverlay()
        }
    }
}
