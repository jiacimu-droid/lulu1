package com.jiacimu.lulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.ChatMemoryAutomation
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.games.LuluGames
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.StarWishStores
import com.jiacimu.lulu.study.StudyFocusSessions
import com.jiacimu.lulu.study.StudyRemovedFeatureMigration

class MigrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        LuluRepositories.initialize(appContext)
        LuluRepositories.lexicon.initialize(appContext)
        LuluRepositories.worldBook.initialize(appContext)
        MigratedDomainStores.initialize(appContext)
        LuluAiServices.initialize(appContext)
        LuluGames.initialize(appContext)
        StudyRemovedFeatureMigration.migrate(appContext)
        PostgraduateExamStores.initialize(appContext)
        StarWishStores.initialize(appContext)
        StudyFocusSessions.initialize(appContext)
        PostgraduateExamStores.main.syncPomodoroClock()
        ChatMemoryAutomation.initialize()
        setContent { LuluMigrationRootAppV2() }
    }
}
