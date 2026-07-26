package com.jiacimu.lulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.games.LuluGames
import com.jiacimu.lulu.study.PostgraduateExamStores

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LuluAiServices.initialize(applicationContext)
        LuluGames.initialize(applicationContext)
        PostgraduateExamStores.initialize(applicationContext)
        PostgraduateExamStores.main.syncPomodoroClock()
        setContent { LuluFinalRootApp() }
    }
}
