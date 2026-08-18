package com.jiacimu.lulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.CharacterIdentityStore
import com.jiacimu.lulu.data.ChatLexiconAutomation
import com.jiacimu.lulu.data.ChatMemoryAutomation
import com.jiacimu.lulu.data.ChatTurnConsistencyAutomation
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.CompanionOnlineStore
import com.jiacimu.lulu.data.DeterministicMemoryAutomation
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.DigitalWorldStore
import com.jiacimu.lulu.data.LegacyConversationMigration
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.MemoryModelRuntime
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
        CompanionOnlineStore.initialize(appContext)
        ProactiveIncomingCallStore.initialize(appContext)
        MeetingExperienceStore.initialize(appContext)
        HealthCycleStore.initialize(appContext)
        LuluAiServices.initialize(appContext)
        MemoryModelRuntime.initialize(appContext)
        UserDataUpgradeGuard.refreshBackup(appContext)
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
        ChatTurnConsistencyAutomation.initialize()
        DeterministicMemoryAutomation.initialize(appContext)
        ChatMemoryAutomation.initialize()
        ChatLexiconAutomation.initialize(appContext)

        // Scheduling only: there is no launch-time perception model call and no in-app 20-minute loop.
        ProactivePerceptionPolicyStore.initialize(appContext)
        ProactivePerceptionRuntime.initialize(appContext)
        ProactivePerceptionScheduler.schedule(appContext)

        val initialConversationId = intent?.getStringExtra("open_conversation_id")
        val initialRouteName = intent?.getStringExtra("open_route")
        val initialCharacterId = intent?.getStringExtra("open_character_id")
        val initialDiaryTitle = intent?.getStringExtra("open_diary_title")
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
                initialReadingTitle = initialReadingTitle,
                initialMeetingInvitationText = initialMeetingInvitationText,
                initialMeetingLocation = initialMeetingLocation,
                initialMeetingInvitationId = initialMeetingInvitationId,
            )
            ProactiveIncomingCallOverlay()
        }
    }
}
