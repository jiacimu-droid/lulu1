package com.jiacimu.lulu

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.design.LuluColors
import com.jiacimu.lulu.design.LuluLightColorScheme
import com.jiacimu.lulu.design.LuluTypography
import com.jiacimu.lulu.games.LuluGamesAppV2
import com.jiacimu.lulu.study.PostgraduateExamApp
import com.jiacimu.lulu.study.StarWishMigratedScreen

@Composable
fun LuluMigrationRootAppV2() {
    var route by rememberSaveable { mutableStateOf(MigrationRoute.Home) }
    var selectedConversationId by rememberSaveable { mutableStateOf("lulu-main") }
    var worldBookReturnRoute by rememberSaveable { mutableStateOf(MigrationRoute.Home) }
    val preferences by LuluAppPreferencesStore.state.collectAsState()
    val density = LocalDensity.current
    val preferredDensity = remember(density, preferences.largerText) {
        Density(
            density = density.density,
            fontScale = density.fontScale * if (preferences.largerText) 1.12f else 1f,
        )
    }

    CompositionLocalProvider(LocalDensity provides preferredDensity) {
        MaterialTheme(
            colorScheme = LuluLightColorScheme,
            typography = LuluTypography,
        ) {
            Surface(Modifier.fillMaxSize(), color = LuluColors.Paper) {
                when (route) {
                    MigrationRoute.Home -> MigrationHomeV2(
                        onOpen = { target ->
                            if (target == MigrationRoute.WorldBook) {
                                worldBookReturnRoute = MigrationRoute.Home
                            }
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
                    MigrationRoute.ChatDetail -> MigratedChatDetailScreenV2(
                        conversationId = selectedConversationId,
                        onBack = { route = MigrationRoute.Chat },
                        onOpenBranch = { branchId ->
                            selectedConversationId = branchId
                            route = MigrationRoute.ChatDetail
                        },
                        onCharacterSettings = { route = MigrationRoute.CharacterSettings },
                        onWorldBook = {
                            worldBookReturnRoute = MigrationRoute.ChatDetail
                            route = MigrationRoute.WorldBook
                        },
                    )
                    MigrationRoute.CharacterSettings -> CharacterSettingsScreen {
                        route = MigrationRoute.Chat
                    }
                    MigrationRoute.Memory -> MemoryFeatureScreen {
                        route = MigrationRoute.Home
                    }
                    MigrationRoute.Lexicon -> LexiconFeatureScreen {
                        route = MigrationRoute.Home
                    }
                    MigrationRoute.WorldBook -> CharacterWorldBookScreen {
                        route = worldBookReturnRoute
                    }
                    MigrationRoute.Performance -> PerformanceFeatureScreen {
                        route = MigrationRoute.Home
                    }
                    MigrationRoute.Reading -> MigrationEmptyScreen(
                        title = "阅读",
                        subtitle = "阅读入口按迁移计划保留为空，不迁入旧阅读内容。",
                        onBack = { route = MigrationRoute.Home },
                    )
                    MigrationRoute.Wishes -> StarWishMigratedScreen {
                        route = MigrationRoute.Home
                    }
                    MigrationRoute.Study -> PostgraduateExamApp {
                        route = MigrationRoute.Home
                    }
                    MigrationRoute.Games -> LuluGamesAppV2 {
                        route = MigrationRoute.Home
                    }
                    MigrationRoute.Settings -> LuluSettingsHomeScreen {
                        route = MigrationRoute.Home
                    }
                }
            }
        }
    }
}
