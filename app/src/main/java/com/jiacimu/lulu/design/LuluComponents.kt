package com.jiacimu.lulu.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluPageScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
        content = content,
    )
}

@Composable
fun LuluSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(LuluRadii.Large),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LuluSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(LuluSpacing.Small),
        ) {
            if (!title.isNullOrBlank()) Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = LuluColors.Muted, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
fun LuluPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LuluRadii.Medium),
        colors = ButtonDefaults.buttonColors(
            containerColor = LuluColors.Wheat,
            contentColor = LuluColors.OnWheat,
        ),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(LuluSizes.IconSmall))
            Spacer(Modifier.width(LuluSpacing.Small))
        }
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LuluTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(LuluRadii.Medium),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LuluColors.Wheat,
            unfocusedBorderColor = LuluColors.Border,
        ),
    )
}

@Composable
fun LuluEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = LuluSpacing.XL, vertical = LuluSpacing.XXL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LuluSpacing.Medium),
    ) {
        Surface(shape = RoundedCornerShape(LuluRadii.Large), color = LuluColors.CardStrong) {
            Icon(icon, null, modifier = Modifier.padding(LuluSpacing.Large).size(34.dp), tint = LuluColors.BlueGray)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = LuluColors.Muted, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
    }
}

@Composable
fun LuluSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    LuluSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = LuluColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}
