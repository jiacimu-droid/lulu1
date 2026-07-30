package com.jiacimu.lulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.ChatLexiconAutomation
import com.jiacimu.lulu.data.ChatMemoryAutomation
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.RoleReadablePerformanceBridge
import com.jiacimu.lulu.games.LuluGames
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.SelfDirectedStudyPlanSeed
import com.jiacimu.lulu.study.StarWishStores
import com.jiacimu.lulu.study.StudyFocusSessions
import com.jiacimu.lulu.study.StudyRemovedFeatureMigration

class MigrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        LuluAppPreferencesStore.initialize(appContext)
        LuluRepositories.initialize(appContext)
        LuluRepositories.lexicon.initialize(appContext)
        LuluRepositories.worldBook.initialize(appContext)
        MigratedDomainStores.initialize(appContext)
        LuluAiServices.initialize(appContext)
        LuluGames.initialize(appContext)
        StudyRemovedFeatureMigration.migrate(appContext)
        PostgraduateExamStores.initialize(appContext)
        SelfDirectedStudyPlanSeed.migrate(appContext, PostgraduateExamStores.main)
        StarWishStores.initialize(appContext)
        StudyFocusSessions.initialize(appContext)
        PostgraduateExamStores.main.syncPomodoroClock()
        RoleReadablePerformanceBridge.initialize()
        ChatMemoryAutomation.initialize()
        ChatLexiconAutomation.initialize(appContext)
        setContent { LuluMigrationRootAppV2() }
    }
}
