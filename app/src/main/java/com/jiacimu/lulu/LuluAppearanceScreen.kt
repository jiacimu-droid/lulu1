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
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

/**
 * Theme studio: every app has its own choice and every recipe remains reusable.
 * User-created variants are copies, so editing cannot destroy a built-in recipe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluAppearanceScreen(onBack: () -> Unit) {
    val library by LuluAppearanceStore.state.collectAsState()
    var scope by remember { mutableStateOf("Home") }
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
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("你的每个世界，都有自己的样子。",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "先选要装扮的 App，再选择风格。各 App 独立保存，方案可以反复应用；" +
                            "换新衣服，不会丢掉昨天喜欢的那一套。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Text("装扮位置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    LuluAppearanceCatalog.scopes.forEach { (key, title) ->
                        FilterChip(
                            selected = scope == key,
                            onClick = { scope = key },
                            label = { Text(title) },
                        )
                    }
                }
            }
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(15.dp),
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
                    Text("最近使用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                Text("主题画廊", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("从简洁到浓烈，不只是换配色。纹理、轮廓、字体和构图一起变化。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            items(library.all, key = { it.id }) { profile ->
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
                Box(Modifier.fillMaxWidth().height(134.dp)) {
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
