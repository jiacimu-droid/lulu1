package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostgraduateExamApp(
    onBack: () -> Unit,
    onOpenTheater: () -> Unit,
    focusRequest: Int = 0,
    onFocusRequestConsumed: () -> Unit = {},
) {
    val store = remember { PostgraduateExamStores.main }
    val context = LocalContext.current
    val state by store.state.collectAsState()
    val focusPreferences by StudyFocusSessions.store.state.collectAsState()
    var route by remember { mutableStateOf<StudyRoute>(StudyRoute.Section(StudySection.Today)) }

    fun stepBack() {
        when (val current = route) {
            StudyRoute.Focus -> route = StudyRoute.Section(StudySection.Today)
            is StudyRoute.Section -> if (current.section == StudySection.Today) {
                onBack()
            } else {
                route = StudyRoute.Section(StudySection.Today)
            }
        }
    }

    BackHandler { stepBack() }

    LaunchedEffect(Unit) {
        store.syncDate()
        SelfDirectedStudyPlanSeed.syncRollingPlan(context, store)
        SelfDirectedStudyPlanSeed.ensureDailyReminders(store)
    }

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            route = StudyRoute.Focus
            onFocusRequestConsumed()
        }
    }

    val timerHasProgress = focusPreferences.activeSessionId.isNotBlank() &&
        !focusPreferences.completionHandled &&
        (state.pomodoro.running || state.pomodoro.remainingSeconds < state.pomodoro.selectedMinutes * 60)

    Box(Modifier.fillMaxSize()) {
        when (val current = route) {
            StudyRoute.Focus -> {
                val activeCharacterId = focusPreferences.activeCharacterId.ifBlank { state.profile.selectedCharacterId }
                val character = MigratedDomainStores.characters.get(activeCharacterId)
                ensureStudyFocusConversation(
                    characterId = activeCharacterId,
                    displayName = character.displayName,
                )
                StudyFocusCompleteScreen(state, store) {
                    route = StudyRoute.Section(StudySection.Today)
                }
            }

            is StudyRoute.Section -> {
                Scaffold(
                    containerColor = StudyDesign.paper,
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("今天也向目标靠近一点", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                    Text(
                                        LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE")),
                                        color = StudyDesign.muted,
                                        fontSize = 12.sp,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = ::stepBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = StudyDesign.paper),
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            StudySectionChips(current.section) { route = StudyRoute.Section(it) }
                        }
                        if (current.section == StudySection.Today) {
                            StudyDailySummaryStrip(state)
                        }
                        Box(Modifier.fillMaxSize()) {
                            when (current.section) {
                                StudySection.Companion -> StudyCompanionScreen(state, store)
                                StudySection.Today -> StudyTodayScreenV2(state, store) { route = StudyRoute.Focus }
                                StudySection.Plan -> StudyPlanScreenV2(state, store)
                                StudySection.Gacha -> StudyGachaScreen(state, store)
                                StudySection.Collection -> StudyCollectionScreen(state, store, onOpenTheater)
                                StudySection.Achievements -> StudyAchievementsScreen(state, store)
                                StudySection.Shop -> StudyShopScreen(state, store)
                                StudySection.Guide -> StudyGuideScreen()
                            }
                        }
                    }
                }
            }
        }

        if (route != StudyRoute.Focus && timerHasProgress) {
            StudyFocusMiniWindow(
                state = state,
                task = focusPreferences.activeTask,
                onOpen = { route = StudyRoute.Focus },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }
    }
}
