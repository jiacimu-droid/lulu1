package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.LocalLuluAppearance
import com.jiacimu.lulu.design.JournalPressedFlower
import com.jiacimu.lulu.design.isJournal
import com.jiacimu.lulu.design.journalPaper
import com.jiacimu.lulu.design.journalLinen

private val SettingsHomePaper: Color @Composable get() = MaterialTheme.colorScheme.background
private val SettingsHomeCard: Color @Composable get() = MaterialTheme.colorScheme.surface
private val SettingsHomeBorder: Color @Composable get() = MaterialTheme.colorScheme.outline
private val SettingsHomeMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val SettingsHomeInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val SettingsHomeAccent: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val SettingsHomeAccentStrong: Color @Composable get() = MaterialTheme.colorScheme.primary

@Composable
fun LuluSettingsHomeScreen(onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsHomePage.Home) }
    // Keep the search when opening a destination and coming back to settings.
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(page) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    BackHandler(enabled = page != SettingsHomePage.Home) {
        page = SettingsHomePage.Home
    }
    when (page) {
        SettingsHomePage.Api -> LuluSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Capabilities -> LuluCapabilitiesScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Integrations -> LuluIntegrationsSettingsScreen(
            onBack = { page = SettingsHomePage.Home },
            onOpenBrowserProbe = { page = SettingsHomePage.BrowserProbe },
            onOpenXhsNativeReader = { page = SettingsHomePage.XhsNativeReader },
        )
        // A second standalone browser page used to create a competing SPA.
        // Both "login website" and "read" now enter the SAME Activity-scoped
        // reader. Expose its website preview for manual login/verification.
        SettingsHomePage.BrowserProbe -> LuluXhsNativeReaderScreen(
            onBack = { page = SettingsHomePage.Integrations },
            initialShowWebsite = true,
        )
        SettingsHomePage.XhsNativeReader -> LuluXhsNativeReaderScreen(
            onBack = { page = SettingsHomePage.Integrations },
        )
        SettingsHomePage.Voice -> LuluVoiceSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Memory -> LuluMemorySettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Residents -> LuluResidentManagementScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Image -> LuluImageSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Vision -> LuluVisionSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Backup -> LuluBackupScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Home -> SettingsEntryScreen(
            onBack = onBack,
            searchQuery = settingsQuery,
            onSearchQueryChange = { settingsQuery = it },
            onOpenApi = { page = SettingsHomePage.Api },
            onOpenCapabilities = { page = SettingsHomePage.Capabilities },
            onOpenIntegrations = { page = SettingsHomePage.Integrations },
            onOpenVoice = { page = SettingsHomePage.Voice },
            onOpenMemory = { page = SettingsHomePage.Memory },
            onOpenResidents = { page = SettingsHomePage.Residents },
            onOpenImage = { page = SettingsHomePage.Image },
            onOpenVision = { page = SettingsHomePage.Vision },
            onOpenBackup = { page = SettingsHomePage.Backup },
        )
    }
}

private enum class SettingsHomePage { Home, Api, Capabilities, Integrations, BrowserProbe, XhsNativeReader, Voice, Memory, Residents, Image, Vision, Backup }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsEntryScreen(
    onBack: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenApi: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenResidents: () -> Unit,
    onOpenImage: () -> Unit,
    onOpenVision: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val journal = LocalLuluAppearance.current.isJournal()
    val searchFocus = androidx.compose.ui.platform.LocalFocusManager.current
    val searchKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var showUpdateNotes by rememberSaveable { mutableStateOf(false) }
    if (showUpdateNotes) LuluUpdateNotesDialog { showUpdateNotes = false }

    // A local, zero-cost filter. Only the matching real destinations remain clickable.
    val visible = remember(searchQuery) {
        filterSettingsHomeEntries(settingsHomeEntries, searchQuery)
    }
    val groups = remember(visible) { visible.groupBy { it.section } }

    Scaffold(
        modifier = if (journal) Modifier.journalLinen(LocalLuluAppearance.current) else Modifier,
        containerColor = if (journal) Color.Transparent else SettingsHomePaper,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("设置", fontWeight = FontWeight.SemiBold, color = SettingsHomeInk)
                            if (journal) JournalPressedFlower(Modifier.padding(start = 8.dp).size(22.dp))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, "返回", tint = SettingsHomeInk)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showUpdateNotes = true }) {
                            Icon(Icons.Outlined.Info, contentDescription = "查看更新内容")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SettingsHomePaper),
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                    label = { Text("搜索设置") },
                    placeholder = { Text("模型、音色、通知、备份…") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        searchFocus.clearFocus()
                        searchKeyboard?.hide()
                    }),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (searchQuery.isNotBlank()) {
                item(key = "result-count") {
                    Text(
                        "找到 ${visible.size} 项设置",
                        color = SettingsHomeMuted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            if (visible.isEmpty()) {
                item(key = "no-results") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null,
                            tint = SettingsHomeMuted, modifier = Modifier.size(28.dp))
                        Text("没有找到相关设置",
                            style = MaterialTheme.typography.titleMedium, color = SettingsHomeInk)
                        Text("换个关键词试试，或查看全部设置",
                            style = MaterialTheme.typography.bodyMedium, color = SettingsHomeMuted)
                        TextButton(onClick = { onSearchQueryChange("") }) { Text("查看全部设置") }
                    }
                }
            } else {
                settingsHomeSections.forEach { section ->
                    val entries = groups[section].orEmpty()
                    if (entries.isNotEmpty()) {
                        item(key = "section-$section") {
                            Text(
                                section,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = SettingsHomeMuted,
                                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                            )
                        }
                        items(entries, key = { it.id }) { entry ->
                            val icon = when (entry.id) {
                                "api" -> Icons.Outlined.Api
                                "integrations" -> Icons.Outlined.Extension
                                "voice" -> Icons.Outlined.RecordVoiceOver
                                "memory" -> Icons.Outlined.Psychology
                                "residents" -> Icons.Outlined.Groups
                                "vision" -> Icons.Outlined.Visibility
                                "image" -> Icons.Outlined.Image
                                "capabilities" -> Icons.Outlined.Security
                                else -> Icons.Outlined.Backup
                            }
                            SettingsEntryRow(
                                icon = icon,
                                title = entry.title,
                                subtitle = entry.subtitle,
                                onClick = {
                                    when (entry.id) {
                                        "api" -> onOpenApi()
                                        "integrations" -> onOpenIntegrations()
                                        "voice" -> onOpenVoice()
                                        "memory" -> onOpenMemory()
                                        "residents" -> onOpenResidents()
                                        "vision" -> onOpenVision()
                                        "image" -> onOpenImage()
                                        "capabilities" -> onOpenCapabilities()
                                        "backup" -> onOpenBackup()
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (searchQuery.isBlank()) {
                item(key = "version") {
                    Text(
                        "露露机 ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_REVISION}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SettingsHomeMuted,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        shape = if (LocalLuluAppearance.current.isJournal())
            RoundedCornerShape(8.dp, 20.dp, 9.dp, 18.dp) else
                RoundedCornerShape(LocalLuluAppearance.current.corner.coerceIn(12, 26).dp),
        color = SettingsHomeCard,
        border = BorderStroke(1.dp, SettingsHomeBorder.copy(alpha = 0.68f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(if (LocalLuluAppearance.current.isJournal())
                    Modifier.journalPaper(LocalLuluAppearance.current) else Modifier)
                .padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(13.dp),
                color = SettingsHomeAccent.copy(alpha = 0.7f)) {
                Icon(
                    icon,
                    null,
                    modifier = Modifier.padding(11.dp).size(21.dp),
                    tint = SettingsHomeAccentStrong,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SettingsHomeInk)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = SettingsHomeMuted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = SettingsHomeMuted)
        }
    }
}
