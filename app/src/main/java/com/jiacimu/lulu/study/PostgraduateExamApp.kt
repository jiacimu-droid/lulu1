package com.jiacimu.lulu.study

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostgraduateExamApp(onBack: () -> Unit, onOpenTheater: () -> Unit) {
    val store = remember { PostgraduateExamStores.main }
    val state by store.state.collectAsState()
    var route by remember { mutableStateOf<StudyRoute>(StudyRoute.Section(StudySection.Today)) }

    LaunchedEffect(Unit) {
        store.syncDate()
        SelfDirectedStudyPlanSeed.ensureDailyReminders(store)
    }

    when (val current = route) {
        StudyRoute.Focus -> {
            val character = MigratedDomainStores.characters.get(state.profile.selectedCharacterId)
            ensureStudyFocusConversation(
                characterId = state.profile.selectedCharacterId,
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
                            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
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
}
