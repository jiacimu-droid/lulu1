package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.LuluAppearance
import com.jiacimu.lulu.data.LuluAppearanceCatalog
import com.jiacimu.lulu.data.LuluAppearanceStore
import com.jiacimu.lulu.design.LuluAppearancePattern
import com.jiacimu.lulu.design.LuluAppearanceTheme
import com.jiacimu.lulu.design.LuluEditorialHeader
import com.jiacimu.lulu.design.LuluEditorialPanel
import com.jiacimu.lulu.design.LuluEditorialSectionHeading
import com.jiacimu.lulu.design.JournalStudioHero
import com.jiacimu.lulu.design.isJournal

/**
 * Theme studio: every app has its own choice and every recipe remains reusable.
 * User-created variants are copies, so editing cannot destroy a built-in recipe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluAppearanceScreen(onBack: () -> Unit) {
    val library by LuluAppearanceStore.state.collectAsState()
    var scope by rememberSaveable { mutableStateOf("Home") }
    var showScopePicker by rememberSaveable { mutableStateOf(false) }
    var scopeSearch by rememberSaveable { mutableStateOf("") }
    var showBasicThemes by remember { mutableStateOf(false) }
    var copySource by remember { mutableStateOf<LuluAppearance?>(null) }
    var copyName by remember { mutableStateOf("") }
    var copyRadius by remember { mutableFloatStateOf(18f) }
    var accentOption by remember { mutableStateOf<Long?>(null) }
    val active = library.active(scope)

    if (copySource != null) {
        val source = copySource!!
        AlertDialog(
            onDismissRequest = { copySource = null },
            title = { Text("另存为我的方案") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("原方案保留不变。调整后的副本可以分配给任意 App，也可以随时切回旧版。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = copyName, onValueChange = { copyName = it },
                        label = { Text("方案名称") }, singleLine = true,
                    )
                    Text("圆角 " + copyRadius.toInt() + " dp")
                    Slider(value = copyRadius, onValueChange = { copyRadius = it }, valueRange = 0f..36f)
                    Text("点缀色", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val colors = listOf(
                            source.palette.accent, 0xFFAA6571L, 0xFF5D7F65L,
                            0xFF526EACL, 0xFFD08E42L,
                        ).distinct()
                        colors.forEach { candidate ->
                            Surface(
                                modifier = Modifier.size(if (accentOption == candidate) 39.dp else 34.dp)
                                    .clickable { accentOption = candidate },
                                shape = CircleShape,
                                color = Color(candidate.toInt()),
                                border = BorderStroke(
                                    if (accentOption == candidate) 3.dp else 1.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {}
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    LuluAppearanceStore.saveCopy(
                        scope, source.id, copyName, accentOption, copyRadius.toInt(),
                    )
                    copySource = null
                }) { Text("保存并应用") }
            },
            dismissButton = { TextButton(onClick = { copySource = null }) { Text("取消") } },
        )
    }


    if (showScopePicker) {
        val visibleScopes = LuluAppearanceCatalog.scopes.filter { (key, title) ->
            scopeSearch.isBlank() ||
                title.contains(scopeSearch.trim(), ignoreCase = true) ||
                key.contains(scopeSearch.trim(), ignoreCase = true)
        }
        ModalBottomSheet(
            onDismissRequest = { showScopePicker = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("选择装扮位置", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = scopeSearch,
                    onValueChange = { scopeSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索页面") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visibleScopes, key = { it.first }) { (key, title) ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope = key
                                scopeSearch = ""
                                showScopePicker = false
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (key == scope) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(title, modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (key == scope) FontWeight.SemiBold
                                        else FontWeight.Normal)
                                if (key == scope) Icon(Icons.Outlined.Check, "当前选中",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (visibleScopes.isEmpty()) {
                        item {
                            Text("没有找到这个页面", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp))
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("美化工坊", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inset),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                LuluEditorialHeader(
                    overline = "LULU  /  APPEARANCE",
                    title = "你的每个世界，都有自己的样子",
                    subtitle = "每个 App 可以单独搭配，随时切回喜欢的方案。",
                )
            }
            item {
                LuluEditorialSectionHeading(title = "装扮位置")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { scopeSearch = ""; showScopePicker = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        LuluAppearanceCatalog.scopes.firstOrNull { it.first == scope }?.second ?: "选择页面",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text("切换", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                }
            }
            item {
                LuluEditorialPanel(emphasized = true) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("当前搭配 · " + (LuluAppearanceCatalog.scopes.firstOrNull { it.first == scope }?.second ?: scope),
                                style = MaterialTheme.typography.labelMedium)
                            Text(active.name, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.size(30.dp).clip(CircleShape)
                            .background(Color(active.palette.accent.toInt())))
                    }
                }
            }
            if (library.recent.isNotEmpty()) {
                item {
                    LuluEditorialSectionHeading(title = "最近使用")
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        library.recent.forEach { id ->
                            library.find(id)?.let { scheme ->
                                AssistChip(
                                    onClick = { LuluAppearanceStore.apply(scope, scheme.id) },
                                    label = { Text(scheme.name) },
                                )
                            }
                        }
                    }
                }
            }
            item {
                LuluEditorialSectionHeading(title = "精选风格")
            }
            // A curated studio, not a wall of nine unpolished 'finished' themes.
            // All existing choices remain reachable; only hand-finished themes are showcased.
            items(library.all.filter { it.isJournal() }, key = { it.id }) { profile ->
                AppearanceRecipeCard(
                    profile = profile,
                    selected = active.id == profile.id,
                    onApply = { LuluAppearanceStore.apply(scope, profile.id) },
                    onSaveCopy = {
                        copySource = profile
                        copyName = profile.name + " · 我的版本"
                        copyRadius = profile.corner.toFloat()
                        accentOption = null
                    },
                    onDelete = { LuluAppearanceStore.deleteUnused(profile.id) },
                    canDelete = !profile.builtIn && library.selections.values.none { it == profile.id },
                )
            }
            item {
                OutlinedButton(
                    onClick = { showBasicThemes = !showBasicThemes },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showBasicThemes) "收起基础方案"
                         else "查看其他基础方案 · ${library.all.count { !it.isJournal() }} 套（保留旧搭配）")
                }
            }
            if (showBasicThemes) {
                items(library.all.filterNot { it.isJournal() }, key = { it.id }) { profile ->
                    AppearanceRecipeCard(
                        profile = profile,
                        selected = active.id == profile.id,
                        onApply = { LuluAppearanceStore.apply(scope, profile.id) },
                        onSaveCopy = {
                            copySource = profile
                            copyName = profile.name + " · 我的版本"
                            copyRadius = profile.corner.toFloat()
                            accentOption = null
                        },
                        onDelete = { LuluAppearanceStore.deleteUnused(profile.id) },
                        canDelete = !profile.builtIn && library.selections.values.none { it == profile.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceRecipeCard(
    profile: LuluAppearance,
    selected: Boolean,
    onApply: () -> Unit,
    onSaveCopy: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
) {
    val radius = RoundedCornerShape(profile.corner.coerceIn(2, 30).dp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LuluAppearanceTheme(profile) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = radius,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(Modifier.fillMaxWidth().height(if (profile.isJournal()) 210.dp else 134.dp)) {
                    if (profile.isJournal()) {
                        JournalStudioHero(Modifier.fillMaxSize())
                    } else {
                    LuluAppearancePattern(Modifier.fillMaxSize())
                    Row(
                        Modifier.fillMaxSize().padding(17.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.width(92.dp).fillMaxHeight(),
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape((profile.corner / 2).coerceAtLeast(2).dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Column(
                                Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.primary))
                                Box(Modifier.fillMaxWidth(0.7f).height(4.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .45f)))
                                Spacer(Modifier.weight(1f))
                                Box(Modifier.size(28.dp).background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(5.dp)))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("LULU / THEME", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall)
                            Text(profile.name, color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(profile.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    profile.palette.paper, profile.palette.cardStrong,
                                    profile.palette.accent, profile.palette.ink,
                                ).forEach { argb ->
                                    Box(Modifier.size(15.dp).clip(CircleShape)
                                        .background(Color(argb.toInt())))
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
        if (profile.isJournal()) {
            Text("✿  精装修样板 · 手账布艺", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        } else {
            Text("基础主题 · 后续逐套精修",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                FilledTonalButton(onClick = onApply, modifier = Modifier.weight(1f)) { Text("✓ 正在使用") }
            } else {
                Button(onClick = onApply, modifier = Modifier.weight(1f)) { Text("应用此方案") }
            }
            OutlinedButton(onClick = onSaveCopy) {
                Icon(Icons.Outlined.Save, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("另存")
            }
            if (canDelete) {
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除未使用的自定义方案") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .5f))
    }
}
