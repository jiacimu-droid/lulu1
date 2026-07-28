package com.jiacimu.lulu

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.jiacimu.lulu.games.LuluGamesApp
import com.jiacimu.lulu.study.PostgraduateExamApp
import com.jiacimu.lulu.study.StarWishFeatureScreen

@Composable
fun LuluMigrationRootApp() {
    var route by rememberSaveable { mutableStateOf(MigrationRoute.Home) }
    var selectedConversationId by rememberSaveable { mutableStateOf("lulu-main") }
    var worldBookReturnRoute by rememberSaveable { mutableStateOf(MigrationRoute.Home) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = MigrationWheat,
            onPrimary = MigrationInk,
            background = MigrationPaper,
            surface = MigrationCard,
            onSurface = MigrationInk,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MigrationPaper) {
            when (route) {
                MigrationRoute.Home -> MigrationHome(
                    onOpen = { target ->
                        if (target == MigrationRoute.WorldBook) worldBookReturnRoute = MigrationRoute.Home
                        route = target
                    },
                    onOpenConversation = { conversationId ->
                        selectedConversationId = conversationId
                        route = MigrationRoute.ChatDetail
                    },
                )
                MigrationRoute.Chat -> MigratedChatHubScreen(
                    onBack = { route = MigrationRoute.Home },
                    onOpenConversation = { conversationId ->
                        selectedConversationId = conversationId
                        route = MigrationRoute.ChatDetail
                    },
                    onCharacterSettings = { route = MigrationRoute.CharacterSettings },
                    onWorldBook = {
                        worldBookReturnRoute = MigrationRoute.Chat
                        route = MigrationRoute.WorldBook
                    },
                )
                MigrationRoute.ChatDetail -> MigratedChatDetailScreen(
                    conversationId = selectedConversationId,
                    onBack = { route = MigrationRoute.Chat },
                    onOpenBranch = { branchId ->
                        selectedConversationId = branchId
                        route = MigrationRoute.ChatDetail
                    },
                )
                MigrationRoute.CharacterSettings -> CharacterSettingsScreen { route = MigrationRoute.Chat }
                MigrationRoute.Memory -> MemoryFeatureScreen { route = MigrationRoute.Home }
                MigrationRoute.Lexicon -> LexiconFeatureScreen { route = MigrationRoute.Home }
                MigrationRoute.WorldBook -> CharacterWorldBookScreen { route = worldBookReturnRoute }
                MigrationRoute.Performance -> PerformanceFeatureScreen { route = MigrationRoute.Home }
                MigrationRoute.Reading -> MigrationEmptyScreen(
                    title = "阅读",
                    subtitle = "阅读入口按迁移计划保留为空，不迁入旧阅读内容。",
                    onBack = { route = MigrationRoute.Home },
                )
                MigrationRoute.Wishes -> StarWishFeatureScreen { route = MigrationRoute.Home }
                MigrationRoute.Study -> PostgraduateExamApp { route = MigrationRoute.Home }
                MigrationRoute.Games -> LuluGamesApp { route = MigrationRoute.Home }
                MigrationRoute.Settings -> LuluSettingsScreen { route = MigrationRoute.Home }
            }
        }
    }
}
