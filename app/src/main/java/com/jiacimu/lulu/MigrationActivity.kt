package com.jiacimu.lulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.ChatLexiconAutomation
import com.jiacimu.lulu.data.ChatMemoryAutomation
import com.jiacimu.lulu.data.ChatTurnConsistencyAutomation
import com.jiacimu.lulu.data.DeterministicMemoryAutomation
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.MemoryModelRuntime
import com.jiacimu.lulu.data.ProactiveMessageAutomation
import com.jiacimu.lulu.data.RoleReadablePerformanceBridge
import com.jiacimu.lulu.data.UserDataUpgradeGuard
import com.jiacimu.lulu.games.LuluGames
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.SelfDirectedStudyPlanSeed
import com.jiacimu.lulu.study.StarWishStores
import com.jiacimu.lulu.study.StudyFocusSessions
import com.jiacimu.lulu.study.StudyRemovedFeatureMigration
import com.jiacimu.lulu.system.LuluDeviceToolBridge

class MigrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        UserDataUpgradeGuard.protectBeforeStoresInitialize(appContext)
        LuluAppPreferencesStore.initialize(appContext)
        LuluRepositories.initialize(appContext)
        LuluRepositories.lexicon.initialize(appContext)
        LuluRepositories.worldBook.initialize(appContext)
        MigratedDomainStores.initialize(appContext)
        LuluAiServices.initialize(appContext)
        MemoryModelRuntime.initialize(appContext)
        UserDataUpgradeGuard.refreshBackup(appContext)
        LuluDeviceToolBridge.initialize(appContext)
        LuluGames.initialize(appContext)
        StudyRemovedFeatureMigration.migrate(appContext)
        PostgraduateExamStores.initialize(appContext)
        SelfDirectedStudyPlanSeed.migrate(appContext, PostgraduateExamStores.main)
        StarWishStores.initialize(appContext)
        StudyFocusSessions.initialize(appContext)
        PostgraduateExamStores.main.syncPomodoroClock()
        RoleReadablePerformanceBridge.initialize()
        ChatTurnConsistencyAutomation.initialize()
        DeterministicMemoryAutomation.initialize(appContext)
        ChatMemoryAutomation.initialize()
        ChatLexiconAutomation.initialize(appContext)
        ProactiveMessageAutomation.initialize(appContext)
        val initialConversationId = intent?.getStringExtra("open_conversation_id")
        setContent { LuluMigrationRootAppV2(initialConversationId = initialConversationId) }
    }
}
