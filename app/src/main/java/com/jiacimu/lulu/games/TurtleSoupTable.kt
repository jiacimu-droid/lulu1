package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object SoupTableColors {
    val background = Color(0xFFF7F4ED)
    val panel = Color(0xFFFFFDF7)
    val border = Color(0xFFE0D7CA)
    val text = Color(0xFF2F3D37)
    val muted = Color(0xFF657970)
    val gold = Color(0xFF946C3C)
    val green = Color(0xFF397C62)
    val paper = Color(0xFFFFF3DA)
    val ink = Color(0xFF273C31)
    val error = Color(0xFFAD4D4C)
}

@Composable
internal fun SoupCaseNote(puzzle: SoupCase, compact: Boolean, onRead: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = onRead,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = SoupTableColors.paper,
        contentColor = SoupTableColors.ink,
    ) {
        Column(
            Modifier.background(Brush.linearGradient(
                listOf(Color(0xFFF6EDD9), Color(0xFFE9D6B6)),
                start = Offset.Zero, end = Offset(850f, 460f),
            )).padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 7.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("汤面  ·  ${puzzle.difficulty}", Modifier.weight(1f), color = Color(0xFF655031),
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Outlined.ExpandMore, contentDescription = "查看完整汤面",
                    Modifier.size(18.dp), tint = Color(0xFF655031))
            }
            Text(puzzle.title, fontSize = if (compact) 19.sp else 25.sp, lineHeight = 29.sp,
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            Text(puzzle.surface, fontSize = 15.sp, lineHeight = 23.sp,
                maxLines = if (compact) 2 else 5, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SoupRoundHeading(
    room: SoupRoom,
    optionsOpen: Boolean,
    onOptions: (Boolean) -> Unit,
    onEvidence: () -> Unit,
    options: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("推理桌", color = SoupTableColors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(if (room.questionBudget > 0) "已问 ${room.questionCount} / ${room.questionBudget}"
                else "已问 ${room.questionCount} 次", color = SoupTableColors.muted, fontSize = 11.sp)
        }
        TextButton(onClick = onEvidence, colors = soupLinkColors()) { Text("线索 ${room.evidence.size}") }
        Box {
            IconButton(onClick = { onOptions(true) }) {
                Icon(Icons.Outlined.MoreHoriz, "本局选项", tint = SoupTableColors.text)
            }
            DropdownMenu(expanded = optionsOpen, onDismissRequest = { onOptions(false) },
                containerColor = SoupTableColors.panel, content = { options() })
        }
    }
}

@Composable
internal fun SoupTranscriptLine(line: SoupLine, related: SoupLine?) {
    val owner = line.speaker == "你" || line.speaker == "你 · 主持"
    if (line.kind == SoupKind.ANSWER) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(color = if (line.text == "是") Color(0xFFE4F3EA) else SoupTableColors.panel,
                shape = CircleShape, border = BorderStroke(1.dp, SoupTableColors.border)) {
                Text(line.text, Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
                    color = if (line.text == "是") SoupTableColors.green else SoupTableColors.gold,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Text(if (related == null) "主持裁定" else "主持 · 回应${related.speaker}的提问",
                color = SoupTableColors.muted, fontSize = 11.sp)
        }
        return
    }
    if (line.speaker == "系统提示") {
        Text("提示 · ${line.text}", Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            color = SoupTableColors.gold, fontSize = 13.sp, lineHeight = 21.sp)
        return
    }
    val detail = when (line.kind) {
        SoupKind.QUESTION -> "  ·  问主持"
        SoupKind.SOLUTION -> "  ·  还原"
        SoupKind.SOLUTION_RESULT -> "  ·  裁定"
        else -> ""
    }
    Column(
        Modifier.fillMaxWidth().padding(start = if (owner) 32.dp else 0.dp, end = if (owner) 0.dp else 32.dp),
        horizontalAlignment = if (owner) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(line.speaker + detail, Modifier.padding(horizontal = 5.dp),
            fontSize = 11.sp, color = if (owner) SoupTableColors.muted else SoupTableColors.gold,
            fontWeight = FontWeight.SemiBold)
        Surface(color = if (owner) Color(0xFFDFEDE5) else SoupTableColors.panel,
            shape = RoundedCornerShape(15.dp, 15.dp, if (owner) 4.dp else 15.dp, if (owner) 15.dp else 4.dp)) {
            Text(line.text, Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                color = SoupTableColors.text, fontSize = 15.sp, lineHeight = 23.sp)
        }
    }
}

@Composable
internal fun SoupTextDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SoupTableColors.panel,
        titleContentColor = SoupTableColors.text,
        textContentColor = SoupTableColors.text,
        title = { Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)) { content() }
        },
        confirmButton = { TextButton(onClick = onDismiss, colors = soupLinkColors()) { Text("回到推理桌") } },
    )
}

@Composable
internal fun SoupComposer(
    room: SoupRoom,
    live: GameLiveSession,
    kind: SoupKind,
    onKind: (SoupKind) -> Unit,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (() -> Unit)?,
    onVerdict: (String) -> Unit,
    onSolutionVerdict: (Boolean) -> Unit,
    busy: Boolean,
    error: String,
    keyboardRequest: Int,
) {
    var typing by remember(room.id, keyboardRequest) { mutableStateOf(keyboardRequest > 0) }
    var showInputModes by remember(room.id) { mutableStateOf(false) }
    val voicePanel = (live.mic.active || live.companionAudioEnabled) && !typing
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(typing, keyboardRequest) {
        if (typing) {
            inputFocus.requestFocus()
            keyboard?.show()
        }
    }
    val hasRuling = room.pendingQuestion >= 0 || room.pendingSolution >= 0
    val canSend = !busy && !hasRuling && room.pendingSpeech.isEmpty() && input.isNotBlank() &&
        (kind != SoupKind.QUESTION || room.canAsk)
    Surface(color = SoupTableColors.panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = SoupTableColors.border)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (error.isNotBlank()) {
                    Text(error, color = SoupTableColors.error, fontSize = 12.sp, lineHeight = 17.sp)
                }
                if (live.playbackError.isNotBlank() && live.companionAudioEnabled) {
                    Text(live.playbackError, color = SoupTableColors.error, fontSize = 12.sp, maxLines = 2)
                }
                if (onRetry != null) TextButton(onClick = onRetry, colors = soupLinkColors()) { Text("重试处理") }
                if (room.host == SoupHost.USER && hasRuling) {
                    Text(if (room.pendingQuestion >= 0) "轮到你裁定" else "队友提交了还原",
                        color = SoupTableColors.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (room.pendingQuestion >= 0) listOf("是", "否", "部分是", "无关").forEach { verdict ->
                            TextButton(onClick = { onVerdict(verdict) }, enabled = !busy,
                                colors = soupLinkColors(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(verdict, fontSize = 13.sp)
                            }
                        } else {
                            TextButton(onClick = { onSolutionVerdict(true) }, colors = soupLinkColors()) { Text("还原成功") }
                            TextButton(onClick = { onSolutionVerdict(false) }, colors = soupLinkColors()) { Text("还差一点") }
                        }
                    }
                }
                if (live.mic.partial.isNotBlank()) {
                    val partial = live.mic.partial
                    Text("你 · $partial", Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        color = SoupTableColors.text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (live.mic.recoverablePartial) TextButton(onClick = live.mic::sendRecoveredPartial,
                        colors = soupLinkColors()) { Text("发送已识别的文字") }
                }
                if (!voicePanel && room.host != SoupHost.USER) {
                    Box {
                        TextButton(onClick = { showInputModes = true }, colors = soupLinkColors(),
                            contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(kind.composerLabel(), fontSize = 13.sp)
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(17.dp))
                        }
                        DropdownMenu(expanded = showInputModes, onDismissRequest = { showInputModes = false },
                            containerColor = SoupTableColors.panel) {
                            listOf(SoupKind.DISCUSSION, SoupKind.QUESTION, SoupKind.SOLUTION).forEach { choice ->
                                DropdownMenuItem(text = { Text(choice.composerLabel(), color = SoupTableColors.text) },
                                    enabled = choice != SoupKind.QUESTION || room.canAsk,
                                    onClick = { onKind(choice); showInputModes = false })
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val micActive = live.mic.active
                    val speakerOn = live.companionAudioEnabled
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            onClick = {
                                if (!micActive) typing = false
                                live.setMicrophoneEnabled(!micActive)
                            },
                            shape = CircleShape,
                            color = if (micActive) SoupTableColors.gold else SoupTableColors.background,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (micActive) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                                    if (micActive) "关闭我的麦克风" else "开启我的麦克风",
                                    tint = if (micActive) Color.White else SoupTableColors.gold)
                            }
                        }
                        Text("我说话", color = SoupTableColors.muted, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            onClick = { live.changeCompanionAudioEnabled(!speakerOn) },
                            shape = CircleShape,
                            color = if (speakerOn) SoupTableColors.green else SoupTableColors.background,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (speakerOn) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                                    if (speakerOn) "关闭角色语音，只显示文字" else "开启角色语音播放",
                                    tint = if (speakerOn) Color.White else SoupTableColors.green)
                            }
                        }
                        Text(if (speakerOn) "角色语音" else "角色文字",
                            color = SoupTableColors.muted, fontSize = 10.sp)
                    }
                    if (voicePanel) {
                        Column(Modifier.weight(1f)) {
                            Text(when {
                                live.companionSpeaking -> "正在说话"
                                micActive -> "我正在说话"
                                speakerOn -> "角色语音已开启"
                                else -> "麦克风已静音"
                            }, color = SoupTableColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            val detail = when {
                                live.companionSpeaking -> if (micActive) "语音播放结束后恢复收音" else "麦克风已静音"
                                room.pendingSpeech.isNotEmpty() && !busy -> live.mic.status
                                busy -> "正在处理这轮对话…"
                                live.mic.status.isNotBlank() && live.mic.status != "连麦中 · 直接说话" &&
                                    live.mic.status != "麦克风已关闭" -> live.mic.status
                                micActive -> if (room.guessers.isEmpty()) "直接说出你的问题" else "和队友聊，或直接问主持人"
                                else -> "点一下麦克风继续说"
                            }
                            Text(detail, color = SoupTableColors.muted, fontSize = 12.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { typing = true }) {
                            Icon(Icons.Outlined.Keyboard, "打开文字输入", tint = SoupTableColors.text)
                        }
                    } else {
                        OutlinedTextField(
                            value = input, onValueChange = onInput,
                            modifier = Modifier.weight(1f).focusRequester(inputFocus),
                            placeholder = { Text(when (kind) {
                                SoupKind.QUESTION -> "输入是／否问题"
                                SoupKind.SOLUTION -> "写下完整推理"
                                else -> "说说你的想法"
                            }, fontSize = 13.sp) },
                            maxLines = 3, shape = RoundedCornerShape(16.dp), colors = soupFieldColors(),
                        )
                        IconButton(enabled = canSend, onClick = onSend) {
                            Icon(Icons.AutoMirrored.Outlined.Send, "发送", tint =
                                if (canSend) SoupTableColors.gold else SoupTableColors.muted)
                        }
                    }
                }
                if (!voicePanel && busy) {
                    Text("正在处理这轮对话…", Modifier.padding(start = 56.dp, top = 3.dp),
                        color = SoupTableColors.muted, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun SoupKind.composerLabel(): String = when (this) {
    SoupKind.QUESTION -> "问主持"
    SoupKind.SOLUTION -> "还原真相"
    else -> "和队友聊"
}

@Composable
internal fun soupLinkColors() = ButtonDefaults.textButtonColors(contentColor = SoupTableColors.gold)

@Composable
private fun soupFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = SoupTableColors.text,
    unfocusedTextColor = SoupTableColors.text,
    focusedLabelColor = SoupTableColors.gold,
    unfocusedLabelColor = SoupTableColors.muted,
    focusedPlaceholderColor = SoupTableColors.muted,
    unfocusedPlaceholderColor = SoupTableColors.muted,
    focusedBorderColor = SoupTableColors.gold,
    unfocusedBorderColor = SoupTableColors.border,
    cursorColor = SoupTableColors.gold,
)

@Composable
internal fun SoupSetup(
    room: SoupRoom,
    players: String,
    showOptions: Boolean,
    onShowOptions: (Boolean) -> Unit,
    onChange: (SoupRoom) -> Boolean,
    onRandom: () -> Unit,
    error: String,
) {
    var caseSearch by remember(room.id) { mutableStateOf("") }
    var showCases by remember(room.id) { mutableStateOf(false) }
    var custom by remember(room.id) { mutableStateOf(false) }
    var customTitle by remember(room.id) { mutableStateOf(room.customTitle) }
    var customSurface by remember(room.id) { mutableStateOf(room.customSurface) }
    var customTruth by remember(room.id) { mutableStateOf(room.customTruth) }
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = SoupTableColors.paper, selectedLabelColor = SoupTableColors.ink,
        labelColor = SoupTableColors.text,
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(players, color = SoupTableColors.muted, fontSize = 13.sp, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { showCases = true }, colors = soupLinkColors()) { Text("选一碗") }
            TextButton(onClick = onRandom, colors = soupLinkColors()) { Text("随机换题") }
            TextButton(onClick = { onShowOptions(!showOptions) }, colors = soupLinkColors()) { Text("规则") }
        }
        if (showOptions) {
            Text("主持人", color = SoupTableColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(SoupHost.AI to "固定主持", SoupHost.CHARACTER to "角色主持", SoupHost.USER to "我主持")
                    .forEach { (host, label) ->
                        FilterChip(selected = room.host == host, onClick = { onChange(room.copy(host = host)) },
                            enabled = host != SoupHost.CHARACTER || room.companions.isNotEmpty(),
                            colors = chipColors, label = { Text(label, fontSize = 12.sp) })
                    }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(0 to "不限问", 12 to "12 问挑战").forEach { (budget, label) ->
                    FilterChip(selected = room.questionBudget == budget, onClick = {
                        onChange(room.copy(questionBudget = budget))
                    }, colors = chipColors, label = { Text(label, fontSize = 12.sp) })
                }
            }
            TextButton(onClick = { custom = !custom }, colors = soupLinkColors()) {
                Text(if (custom) "收起自定义题目" else "自己出题")
            }
            if (custom) {
                OutlinedTextField(customTitle, { customTitle = it }, Modifier.fillMaxWidth(),
                    label = { Text("题目名称") }, singleLine = true, colors = soupFieldColors())
                OutlinedTextField(customSurface, { customSurface = it }, Modifier.fillMaxWidth(),
                    label = { Text("汤面") }, minLines = 2, maxLines = 5, colors = soupFieldColors())
                OutlinedTextField(customTruth, { customTruth = it }, Modifier.fillMaxWidth(),
                    label = { Text("汤底 · 仅主持人可见") }, minLines = 2, maxLines = 5, colors = soupFieldColors())
                TextButton(
                    onClick = {
                        if (onChange(room.copy(caseId = CUSTOM_CASE_ID, customTitle = customTitle.trim(),
                            customSurface = customSurface.trim(), customTruth = customTruth.trim()))) custom = false
                    },
                    enabled = customTitle.isNotBlank() && customSurface.trim().length >= 20 && customTruth.trim().length >= 35,
                    colors = soupLinkColors(),
                ) { Text("选用这道题") }
            }
        }
        if (error.isNotBlank()) Text(error, color = SoupTableColors.error, fontSize = 12.sp)
        Button(
            onClick = { onChange(room.copy(started = true)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SoupTableColors.gold, contentColor = Color.White),
        ) { Text("开始推理", fontWeight = FontWeight.SemiBold) }
    }
    if (showCases) {
        SoupTextDialog("选一碗汤", onDismiss = { showCases = false }) {
            OutlinedTextField(caseSearch, { caseSearch = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜题名、类型或难度") }, colors = soupFieldColors())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部", "简单", "中等", "较难").forEach { label ->
                    FilterChip(selected = if (label == "全部") caseSearch.isBlank() else caseSearch == label,
                        onClick = { caseSearch = if (label == "全部") "" else label },
                        colors = chipColors, label = { Text(label, fontSize = 11.sp) })
                }
            }
            val cases = SoupLibrary.cases.filter {
                caseSearch.isBlank() || caseSearch in it.title || caseSearch in it.category || caseSearch in it.difficulty
            }.sortedBy { it.difficulty != "简单" }
            if (cases.isEmpty()) Text("没有找到这类题目", color = SoupTableColors.muted)
            cases.forEach { candidate ->
                Surface(
                    onClick = {
                        if (onChange(room.copy(caseId = candidate.id))) showCases = false
                    },
                    modifier = Modifier.fillMaxWidth(), color = SoupTableColors.background,
                    shape = RoundedCornerShape(9.dp),
                    border = if (candidate.id == room.caseId) BorderStroke(1.dp, SoupTableColors.gold) else null,
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(candidate.title, color = SoupTableColors.text, fontWeight = FontWeight.SemiBold)
                        Text(candidate.category + " · " + candidate.difficulty, color = SoupTableColors.muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
