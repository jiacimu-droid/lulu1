package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
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
import com.jiacimu.lulu.games.ApocalypseSurvivalAppV5
import com.jiacimu.lulu.games.LuluGames
import com.jiacimu.lulu.games.LuluGamesAppV2
import com.jiacimu.lulu.health.HealthFeatureScreen
import com.jiacimu.lulu.study.LuluReadingScreen
import com.jiacimu.lulu.study.PomodoroCompanionSessions
import com.jiacimu.lulu.study.PomodoroMiniWindow
import com.jiacimu.lulu.study.PomodoroTimerMode
import com.jiacimu.lulu.study.PostgraduateExamApp
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.StarWishMigratedScreen
import com.jiacimu.lulu.study.StarWishTab
import kotlinx.coroutines.delay

@Composable
fun LuluMigrationRootAppV2(
    initialConversationId: String? = null,
    initialRouteName: String? = null,
    initialTargetCharacterId: String? = null,
    initialDiaryTitle: String? = null,
    initialReadingTitle: String? = null,
) {
    val deepLinkRoute = remember(initialRouteName) {
        runCatching { MigrationRoute.valueOf(initialRouteName.orEmpty()) }.getOrNull()
            ?.takeIf { it == MigrationRoute.Lexicon || it == MigrationRoute.Reading }
    }
    val initialStack = remember(initialConversationId, deepLinkRoute) {
        when {
            deepLinkRoute != null && !initialConversationId.isNullOrBlank() -> listOf(
                MigrationRoute.Home.name,
                MigrationRoute.Chat.name,
                MigrationRoute.ChatDetail.name,
                deepLinkRoute.name,
            )
            deepLinkRoute != null -> listOf(MigrationRoute.Home.name, deepLinkRoute.name)
            initialConversationId.isNullOrBlank() -> listOf(MigrationRoute.Home.name)
            else -> listOf(MigrationRoute.Home.name, MigrationRoute.Chat.name, MigrationRoute.ChatDetail.name)
        }
    }
    var routeStack by rememberSaveable(initialConversationId, initialRouteName) { mutableStateOf(initialStack) }
    val route = MigrationRoute.valueOf(routeStack.last())
    var selectedConversationId by rememberSaveable(initialConversationId) {
        mutableStateOf(initialConversationId?.takeIf(String::isNotBlank) ?: "lulu-main")
    }
    var chatSessionStarted by rememberSaveable(initialConversationId) {
        mutableStateOf(!initialConversationId.isNullOrBlank())
    }
    var selectedCharacterId by rememberSaveable(initialTargetCharacterId) {
        mutableStateOf(initialTargetCharacterId?.takeIf(String::isNotBlank) ?: "lulu")
    }
    var lexiconInitialDiaryTitle by rememberSaveable(initialDiaryTitle) { mutableStateOf(initialDiaryTitle) }
    var readingInitialTitle by rememberSaveable(initialReadingTitle) { mutableStateOf(initialReadingTitle) }
    var starWishInitialTab by rememberSaveable { mutableStateOf(StarWishTab.Scroll.name) }
    var initialGameId by rememberSaveable { mutableStateOf<String?>(null) }
    var pomodoroFocusVisible by rememberSaveable { mutableStateOf(false) }
    var openPomodoroRequest by rememberSaveable { mutableStateOf(0) }

    val preferences by LuluAppPreferencesStore.state.collectAsState()
    val studyState by PostgraduateExamStores.main.state.collectAsState()
    val pomodoroCompanion by PomodoroCompanionSessions.store.state.collectAsState()
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

    fun openConversation(conversationId: String) {
        selectedConversationId = conversationId
        selectedCharacterId = characterIdForConversation(conversationId)
        chatSessionStarted = true
        pushRoute(MigrationRoute.ChatDetail)
    }

    fun popRoute() {
        if (routeStack.size > 1) routeStack = routeStack.dropLast(1)
    }

    fun replaceTop(target: MigrationRoute) {
        routeStack = routeStack.dropLast(1) + target.name
    }

    fun openActivePomodoro() {
        openPomodoroRequest += 1
        if (route != MigrationRoute.Study) pushRoute(MigrationRoute.Study)
    }

    // QqStyleChatDetailScreen intentionally remains composed behind other routes. This explicit
    // visibility/lifecycle bridge is therefore the source of truth for when a message is actually
    // seen. Background replies and replies received while browsing other Lulu pages remain unread.
    ChatUnreadVisibilityEffect(
        conversationId = selectedConversationId,
        routeVisible = chatSessionStarted && route == MigrationRoute.ChatDetail,
    )

    BackHandler(enabled = routeStack.size > 1) {
        popRoute()
    }

    LaunchedEffect(initialConversationId, initialRouteName, initialTargetCharacterId, initialDiaryTitle, initialReadingTitle) {
        if (!initialConversationId.isNullOrBlank()) {
            selectedConversationId = initialConversationId
            selectedCharacterId = initialTargetCharacterId?.takeIf(String::isNotBlank)
                ?: characterIdForConversation(initialConversationId)
            chatSessionStarted = true
            routeStack = if (deepLinkRoute != null) {
                listOf(
                    MigrationRoute.Home.name,
                    MigrationRoute.Chat.name,
                    MigrationRoute.ChatDetail.name,
                    deepLinkRoute.name,
                )
            } else {
                listOf(MigrationRoute.Home.name, MigrationRoute.Chat.name, MigrationRoute.ChatDetail.name)
            }
        } else if (deepLinkRoute != null) {
            selectedCharacterId = initialTargetCharacterId?.takeIf(String::isNotBlank) ?: "lulu"
            routeStack = listOf(MigrationRoute.Home.name, deepLinkRoute.name)
        }
        lexiconInitialDiaryTitle = initialDiaryTitle
        readingInitialTitle = initialReadingTitle
    }

    LaunchedEffect(studyState.pomodoro.running, studyState.pomodoro.endAtEpochMillis) {
        val studyStore = PostgraduateExamStores.main
        while (studyStore.state.value.pomodoro.running) {
            delay(500)
            val beforeSync = studyStore.state.value.pomodoro
            if (studyStore.syncPomodoroClock()) {
                PomodoroCompanionSessions.handleNaturalCompletion(
                    studyStore = studyStore,
                    actualMinutes = beforeSync.selectedMinutes,
                )
                break
            }
        }
    }

    LaunchedEffect(pomodoroCompanion.countUpRunning, pomodoroCompanion.countUpAnchorEpochMillis) {
        while (PomodoroCompanionSessions.store.state.value.countUpRunning) {
            delay(1_000)
            PomodoroCompanionSessions.syncCountUpClock()
        }
    }

    CompositionLocalProvider(LocalDensity provides preferredDensity) {
        MaterialTheme(
            colorScheme = LuluLightColorScheme,
            typography = LuluTypography,
        ) {
            Surface(Modifier.fillMaxSize(), color = LuluColors.Paper) {
                Box(Modifier.fillMaxSize()) {
                    if (chatSessionStarted) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (route == MigrationRoute.ChatDetail) Modifier.imePadding() else Modifier),
                        ) {
                            key(selectedConversationId) {
                                QqStyleChatDetailScreen(
                                    conversationId = selectedConversationId,
                                    onBack = ::popRoute,
                                    onCharacterSettings = {
                                        selectConversationCharacter()
                                        pushRoute(MigrationRoute.CharacterSettings)
                                    },
                                    onWorldBook = {
                                        selectConversationCharacter()
                                        pushRoute(MigrationRoute.WorldBook)
                                    },
                                    onOpenGame = { gameId ->
                                        initialGameId = gameId
                                        pushRoute(MigrationRoute.Games)
                                    },
                                )
                            }
                        }
                    }

                    if (route != MigrationRoute.ChatDetail) {
                        Surface(Modifier.fillMaxSize(), color = LuluColors.Paper) {
                            when (route) {
                                MigrationRoute.Home -> MigrationHomeV2(
                                    onOpen = { target ->
                                        if (target == MigrationRoute.WorldBook) selectedCharacterId = "lulu"
                                        if (target == MigrationRoute.Wishes) starWishInitialTab = StarWishTab.Scroll.name
                                        if (target == MigrationRoute.Lexicon) {
                                            selectedCharacterId = "lulu"
                                            lexiconInitialDiaryTitle = null
                                        }
                                        if (target == MigrationRoute.Reading) readingInitialTitle = null
                                        pushRoute(target)
                                    },
                                    onOpenConversation = ::openConversation,
                                )

                                MigrationRoute.Chat -> MigratedChatHubScreenV2(
                                    onBack = ::popRoute,
                                    onOpenConversation = ::openConversation,
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

                                MigrationRoute.CharacterSettings -> CharacterSettingsScreenV2(
                                    characterId = selectedCharacterId,
                                    onBack = ::popRoute,
                                    onDeleted = {
                                        selectedCharacterId = "lulu"
                                        replaceTop(MigrationRoute.Chat)
                                    },
                                )

                                MigrationRoute.Memory -> MemoryFeatureScreen(::popRoute)
                                MigrationRoute.Lexicon -> LexiconFeatureScreenV2(
                                    onBack = ::popRoute,
                                    initialCharacterId = selectedCharacterId,
                                    initialDiaryTitle = lexiconInitialDiaryTitle,
                                )
                                MigrationRoute.WorldBook -> CharacterWorldBookScreenV2(
                                    initialCharacterId = selectedCharacterId,
                                    onBack = ::popRoute,
                                )
                                MigrationRoute.Performance -> OptimizedPerformanceFeatureScreen(::popRoute)
                                MigrationRoute.Health -> HealthFeatureScreen(::popRoute)
                                MigrationRoute.Reading -> LuluReadingScreen(
                                    onBack = ::popRoute,
                                    initialBookTitle = readingInitialTitle,
                                )
                                MigrationRoute.Wishes -> StarWishMigratedScreen(
                                    onBack = ::popRoute,
                                    initialTab = runCatching { StarWishTab.valueOf(starWishInitialTab) }.getOrDefault(StarWishTab.Scroll),
                                )
                                MigrationRoute.Study -> PostgraduateExamApp(
                                    onBack = ::popRoute,
                                    onOpenConversation = ::openConversation,
                                    openPomodoroRequest = openPomodoroRequest,
                                    onPomodoroVisibilityChanged = { pomodoroFocusVisible = it },
                                )
                                MigrationRoute.Schedule -> ScheduleFeatureScreen(::popRoute)
                                MigrationRoute.Games -> LuluGamesAppV2(
                                    onBack = {
                                        initialGameId = null
                                        popRoute()
                                    },
                                    initialGameId = initialGameId,
                                )
                                MigrationRoute.ApocalypseSurvival -> ApocalypseSurvivalAppV5(
                                    gameStore = LuluGames.store,
                                    onBack = ::popRoute,
                                )
                                MigrationRoute.Settings -> LuluSettingsHomeScreen(::popRoute)
                                MigrationRoute.ChatDetail -> Unit
                            }
                        }
                    }

                    val pomodoroActive = pomodoroCompanion.activeSessionId.isNotBlank() && !pomodoroCompanion.completionHandled
                    if (pomodoroActive && !pomodoroFocusVisible) {
                        PomodoroMiniWindow(
                            studyState = studyState,
                            companion = pomodoroCompanion,
                            onOpen = ::openActivePomodoro,
                            onPauseResume = {
                                if (pomodoroCompanion.timerMode == PomodoroTimerMode.CountUp) {
                                    PomodoroCompanionSessions.toggleCountUp()
                                } else {
                                    PostgraduateExamStores.main.togglePomodoro()
                                }
                            },
                            onEnd = {
                                PomodoroCompanionSessions.finishActiveSession(
                                    studyStore = PostgraduateExamStores.main,
                                    reason = "用户从番茄钟小窗结束",
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
