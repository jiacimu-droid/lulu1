package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.design.LuluColors
import com.jiacimu.lulu.design.LuluLightColorScheme
import com.jiacimu.lulu.design.LuluTypography
import com.jiacimu.lulu.games.LuluGamesAppV2
import com.jiacimu.lulu.study.LuluReadingScreen
import com.jiacimu.lulu.study.PostgraduateExamApp
import com.jiacimu.lulu.study.StarWishMigratedScreen
import com.jiacimu.lulu.study.StarWishTab

@Composable
fun LuluMigrationRootAppV2(initialConversationId: String? = null) {
    val initialStack = remember(initialConversationId) {
        if (initialConversationId.isNullOrBlank()) {
            listOf(MigrationRoute.Home.name)
        } else {
            listOf(MigrationRoute.Home.name, MigrationRoute.Chat.name, MigrationRoute.ChatDetail.name)
        }
    }
    var routeStack by rememberSaveable(initialConversationId) { mutableStateOf(initialStack) }
    val route = MigrationRoute.valueOf(routeStack.last())
    var selectedConversationId by rememberSaveable(initialConversationId) {
        mutableStateOf(initialConversationId?.takeIf(String::isNotBlank) ?: "lulu-main")
    }
    var selectedCharacterId by rememberSaveable { mutableStateOf("lulu") }
    var starWishInitialTab by rememberSaveable { mutableStateOf(StarWishTab.Scroll.name) }
    val preferences by LuluAppPreferencesStore.state.collectAsState()
    val density = LocalDensity.current
    val preferredDensity = remember(density, preferences.largerText) {
        Density(
            density = density.density,
            fontScale = density.fontScale * if (preferences.largerText) 1.12f else 1f,
        )
    }

    fun characterIdForConversation(conversationId: String): String =
        MigratedDomainStores.chat.conversations.value
            .firstOrNull { conversation -> conversation.id == conversationId }
            ?.characterId
            ?: "lulu"

    fun selectConversationCharacter() {
        selectedCharacterId = characterIdForConversation(selectedConversationId)
    }

    fun pushRoute(target: MigrationRoute) {
        routeStack = routeStack + target.name
    }

    fun popRoute() {
        if (routeStack.size > 1) routeStack = routeStack.dropLast(1)
    }

    fun replaceTop(target: MigrationRoute) {
        routeStack = routeStack.dropLast(1) + target.name
    }

    BackHandler(enabled = routeStack.size > 1) {
        popRoute()
    }

    LaunchedEffect(initialConversationId) {
        if (!initialConversationId.isNullOrBlank()) {
            selectedConversationId = initialConversationId
            selectConversationCharacter()
            routeStack = listOf(MigrationRoute.Home.name, MigrationRoute.Chat.name, MigrationRoute.ChatDetail.name)
        }
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
                            if (target == MigrationRoute.WorldBook) selectedCharacterId = "lulu"
                            if (target == MigrationRoute.Wishes) starWishInitialTab = StarWishTab.Scroll.name
                            pushRoute(target)
                        },
                        onOpenConversation = { conversationId ->
                            selectedConversationId = conversationId
                            selectedCharacterId = characterIdForConversation(conversationId)
                            pushRoute(MigrationRoute.ChatDetail)
                        },
                    )
                    MigrationRoute.Chat -> MigratedChatHubScreenV2(
                        onBack = ::popRoute,
                        onOpenConversation = { conversationId ->
                            selectedConversationId = conversationId
                            selectedCharacterId = characterIdForConversation(conversationId)
                            pushRoute(MigrationRoute.ChatDetail)
                        },
                        onCharacterSettings = { characterId ->
                            selectedCharacterId = characterId
                            pushRoute(MigrationRoute.CharacterSettings)
                        },
                        onWorldBook = { characterId ->
                            selectedCharacterId = characterId
                            pushRoute(MigrationRoute.WorldBook)
                        },
                        onOpenSettings = { pushRoute(MigrationRoute.Settings) },
                    )
                    MigrationRoute.ChatDetail -> QqStyleChatDetailScreen(
                        conversationId = selectedConversationId,
                        onBack = ::popRoute,
                        onOpenBranch = { branchId ->
                            selectedConversationId = branchId
                            selectedCharacterId = characterIdForConversation(branchId)
                        },
                        onCharacterSettings = {
                            selectConversationCharacter()
                            pushRoute(MigrationRoute.CharacterSettings)
                        },
                        onWorldBook = {
                            selectConversationCharacter()
                            pushRoute(MigrationRoute.WorldBook)
                        },
                    )
                    MigrationRoute.CharacterSettings -> CharacterSettingsScreenV2(
                        characterId = selectedCharacterId,
                        onBack = ::popRoute,
                        onDeleted = {
                            selectedCharacterId = "lulu"
                            replaceTop(MigrationRoute.Chat)
                        },
                    )
                    MigrationRoute.Memory -> MemoryFeatureScreen(::popRoute)
                    MigrationRoute.Lexicon -> LexiconFeatureScreenV2(::popRoute)
                    MigrationRoute.WorldBook -> CharacterWorldBookScreenV2(
                        initialCharacterId = selectedCharacterId,
                        onBack = ::popRoute,
                    )
                    MigrationRoute.Performance -> OptimizedPerformanceFeatureScreen(::popRoute)
                    MigrationRoute.Reading -> LuluReadingScreen(::popRoute)
                    MigrationRoute.Wishes -> StarWishMigratedScreen(
                        onBack = ::popRoute,
                        initialTab = StarWishTab.valueOf(starWishInitialTab),
                    )
                    MigrationRoute.Study -> PostgraduateExamApp(
                        onBack = ::popRoute,
                        onOpenTheater = {
                            starWishInitialTab = StarWishTab.Theater.name
                            pushRoute(MigrationRoute.Wishes)
                        },
                    )
                    MigrationRoute.Games -> LuluGamesAppV2(::popRoute)
                    MigrationRoute.Settings -> LuluSettingsHomeScreen(::popRoute)
                }
            }
        }
    }
}
