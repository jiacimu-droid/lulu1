package com.jiacimu.lulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.games.LuluGames
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.StudyFocusSessions
import com.jiacimu.lulu.study.StudyRemovedFeatureMigration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LuluRepositories.initialize(applicationContext)
        LuluRepositories.lexicon.initialize(applicationContext)
        LuluRepositories.worldBook.initialize(applicationContext)
        MigratedDomainStores.initialize(applicationContext)
        LuluAiServices.initialize(applicationContext)
        LuluGames.initialize(applicationContext)
        StudyRemovedFeatureMigration.migrate(applicationContext)
        PostgraduateExamStores.initialize(applicationContext)
        StudyFocusSessions.initialize(applicationContext)
        PostgraduateExamStores.main.syncPomodoroClock()
        setContent { LuluMigrationRootApp() }
    }
}
