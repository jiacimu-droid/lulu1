package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterLifeStore
import com.jiacimu.lulu.data.CharacterInnerLifeStore
import com.jiacimu.lulu.data.CharacterProfileSchema
import com.jiacimu.lulu.data.MigratedDomainStores

@Composable
internal fun CharacterLifeSettings(characterId: String) {
    val states by CharacterLifeStore.states.collectAsState()
    val innerRevision by CharacterInnerLifeStore.revisions.collectAsState()
    val innerRoot = remember(characterId, innerRevision) { CharacterInnerLifeStore.snapshot(characterId) }
    val presenceStates by com.jiacimu.lulu.data.CompanionPresenceStore.states.collectAsState()
    val presence = presenceStates[characterId]
    val growthRevision by com.jiacimu.lulu.data.CharacterDevelopmentStore.revisions.collectAsState()
    val interests = remember(characterId, growthRevision, states) {
        com.jiacimu.lulu.data.CharacterDevelopmentStore.active(characterId).filter {
            it.kind == com.jiacimu.lulu.data.DevelopmentKind.Interest }
    }
    val root = remember(states, characterId) { CharacterLifeStore.state(characterId) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(false) }
    val name = MigratedDomainStores.characters.get(characterId).displayName
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("人格与行为", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("原有角色设定是基础。这里只补充你确定的部分；经历、当前心情与后来形成的习惯由角色运行记录。", style = MaterialTheme.typography.bodySmall)
        Text("此刻", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        presence?.let { moment ->
            if (moment.mood.isNotBlank()) Text(moment.mood, style = MaterialTheme.typography.bodyMedium)
            Text(moment.innerThought.ifBlank { "这一刻没有留下心声" }, style = MaterialTheme.typography.bodyMedium)
            if (moment.statusText.isNotBlank()) Text(moment.statusText, style = MaterialTheme.typography.bodySmall)
        } ?: Text("还没有留下这一刻的想法", style = MaterialTheme.typography.bodySmall)
        if (interests.isNotEmpty()) {
            Text("经历中形成的兴趣", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            interests.forEach { Text(it.content, style = MaterialTheme.typography.bodyMedium) }
        }
        HorizontalDivider()
        Text("内在生活 · 正在牵挂的几件事", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("角色可以同时在意多件事、暂停或调整，行动结果来自实际工具回执；它们不是系统强制待办。", style = MaterialTheme.typography.bodySmall)
        val motives = innerRoot.optJSONArray("motives")
        if (motives == null || motives.length() == 0) {
            Text("还没有从真实经历中形成多个具体愿望", style = MaterialTheme.typography.bodySmall)
        } else {
            for (i in 0 until motives.length()) {
                val goal = motives.optJSONObject(i) ?: continue
                val id = goal.optString("id")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(goal.optString("aim"), fontWeight = FontWeight.Medium)
                    Text(goal.optString("why"), style = MaterialTheme.typography.bodySmall)
                    Text(if (goal.optString("status") == "paused") "暂时搁置" else "仍在意 · 优先级${goal.optInt("priority", 2)}", style = MaterialTheme.typography.bodySmall)
                    goal.optString("changedBecause").takeIf(String::isNotBlank)?.let {
                        Text("变化原因：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    val outcomes = goal.optJSONArray("outcomes")
                    outcomes?.optJSONObject(outcomes.length() - 1)?.let { outcome ->
                        Text("最近实际行动：${if (outcome.optBoolean("success")) "成功" else "未完成"} · ${outcome.optString("summary")}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { CharacterInnerLifeStore.stopMotive(characterId, id) }) {
                        Text("结束这件事")
                    }
                }
            }
        }
        val subjectiveEmotion = innerRoot.optJSONObject("emotion")
        subjectiveEmotion?.let { feeling ->
            Text("情绪 · 正在发生的感受", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(feeling.optString("feeling"), style = MaterialTheme.typography.bodyMedium)
            Text("缘由：${feeling.optString("cause")}", style = MaterialTheme.typography.bodySmall)
            feeling.optString("otherFeeling").takeIf(String::isNotBlank)?.let {
                Text("同时也有：$it", style = MaterialTheme.typography.bodySmall)
            }
            feeling.optString("impulse").takeIf(String::isNotBlank)?.let {
                Text("一瞬间想：$it", style = MaterialTheme.typography.bodySmall)
            }
            feeling.optString("restraint").takeIf(String::isNotBlank)?.let {
                Text("克制 / 犹豫：$it", style = MaterialTheme.typography.bodySmall)
            }
            feeling.optString("outwardCue").takeIf(String::isNotBlank)?.let {
                Text("表现：$it", style = MaterialTheme.typography.bodySmall)
            }
        }
        innerRoot.optJSONArray("emotionHistory")?.let { history ->
            if (history.length() > 0) {
                Text("最近的情绪变化", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                for (i in maxOf(0, history.length() - 3) until history.length()) {
                    val item = history.optJSONObject(i) ?: continue
                    Text("${item.optString("feeling")} · ${item.optString("cause")}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val innerVoices = innerRoot.optJSONArray("innerVoices")
        if (innerVoices != null && innerVoices.length() > 0) {
            Text("内心的声音 · 没说出口的想法", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            for (i in maxOf(0, innerVoices.length() - 3) until innerVoices.length()) {
                val thought = innerVoices.optJSONObject(i) ?: continue
                Text(thought.optString("thought"), style = MaterialTheme.typography.bodySmall)
            }
            Text("只是角色当时的主观心声，不表示相关行动已经发生。", style = MaterialTheme.typography.bodySmall)
        }
        val bonds = innerRoot.optJSONObject("bonds")
        if (bonds != null && bonds.length() > 0) {
            Text("逐渐形成的主观看法", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            bonds.keys().asSequence().take(6).forEach { id ->
                val opinion = bonds.optJSONObject(id) ?: return@forEach
                val whom = if (id == "user") "对你" else "对其他角色"
                Text("$whom：${opinion.optString("interpretation")}", style = MaterialTheme.typography.bodySmall)
                opinion.optString("reason").takeIf(String::isNotBlank)?.let {
                    Text("缘由：$it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val corrections = innerRoot.optJSONArray("corrections")
        corrections?.optJSONObject(corrections.length() - 1)?.let { insight ->
            Text("最近的一次自我修正", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(insight.optString("realization"), style = MaterialTheme.typography.bodyMedium)
            Text("准备换种做法：${insight.optString("nextTime")}", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text("角色自己的社交称呼", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val socialNames = root.optJSONObject("socialNames")
        val userRemark = socialNames?.optString("userRemark").orEmpty()
        val selfNickname = socialNames?.optString("selfNickname").orEmpty()
        Text("给你的私人备注：" + userRemark.ifBlank { "还没有设置" }, style = MaterialTheme.typography.bodyMedium)
        if (userRemark.isNotBlank()) TextButton(onClick = { CharacterLifeStore.setSocialName(characterId, "userRemark", "") }) { Text("清除这条备注") }
        Text("自己的聊天网名：" + selfNickname.ifBlank { "沿用角色原名" }, style = MaterialTheme.typography.bodyMedium)
        if (selfNickname.isNotBlank()) TextButton(onClick = { CharacterLifeStore.setSocialName(characterId, "selfNickname", "") }) { Text("恢复原网名") }
        Text("这两项可由角色真实主动修改；聊天网名不会覆盖原始角色身份。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        if (name in setOf("江渡", "江都")) OutlinedButton(onClick = { preset = true }) { Text("填入江渡设定") }
        CharacterProfileSchema.fields.groupBy { it.group }.forEach { (group, fields) ->
            Text(group, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            fields.forEach { field ->
                val value = root.optJSONObject("profile")?.optString(field.key).orEmpty()
                Column(Modifier.fillMaxWidth().clickable { editing = field.key; draft = value }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(field.label, fontWeight = FontWeight.Medium)
                    Text(value.ifBlank { if (field.key == "interests") interests.joinToString("；") { it.content }.ifBlank { "尚未从反复经历中形成稳定兴趣" } else "未限定 · 点击编辑" },
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
        root.optJSONObject("intention")?.let { intention ->
            Text("正在在意的事", fontWeight = FontWeight.Bold)
            Text(intention.optString("aim")); Text(intention.optString("motive"))
            intention.optString("changeReason").takeIf(String::isNotBlank)?.let {
                Text("最近的变化 · $it", style = MaterialTheme.typography.bodySmall)
            }
            val outcomes = intention.optJSONArray("outcomes")
            if (outcomes != null) for (i in maxOf(0, outcomes.length() - 3) until outcomes.length()) {
                val outcome = outcomes.getJSONObject(i)
                Text("${if (outcome.optBoolean("success")) "已执行" else "未完成"} · ${outcome.optString("summary")}")
            }
            TextButton(onClick = { CharacterLifeStore.stopIntention(characterId) }) { Text("结束这件事") }
        }
    }
    editing?.let { key ->
        val field = CharacterProfileSchema.fields.first { it.key == key }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(field.label) },
            text = { OutlinedTextField(draft, { draft = it }, placeholder = { Text(field.hint) }, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ CharacterLifeStore.setProfile(characterId, key, draft); editing = null }) { Text("保存") } },
            dismissButton = { TextButton({ editing = null }) { Text("取消") } })
    }
    if (preset) AlertDialog(onDismissRequest = { preset = false }, title = { Text("填入江渡设定") },
        text = { Text("应用江渡的数字生命身份、先有恋人关系再形成感情的起点及完整性格。已有设定先备份；本版本只应用一次，之后的编辑会保留。") },
        confirmButton = { TextButton({
            CharacterLifeStore.applyJiangDuPreset(characterId)
            preset = false
        }) { Text("填入") } }, dismissButton = { TextButton({ preset = false }) { Text("取消") } })
}
