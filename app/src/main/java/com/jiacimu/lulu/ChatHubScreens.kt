package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HubPaper = Color(0xFFFFFDF7)
private val HubCard = Color(0xFFFFFBF1)
private val HubWheat = Color(0xFFF4D57D)
private val HubInk = Color(0xFF343434)
private val HubBlueGray = Color(0xFF6D7888)
private val HubBorder = Color(0xFFEAE0CC)

private data class CharacterPreview(
    val name: String,
    val status: String,
    val initial: String,
    val active: Boolean,
)

@Composable
fun CharacterHubScreen() {
    val characters = listOf(
        CharacterPreview("露露", "当前角色 · 正在陪伴主人", "露", true),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("角色", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = HubInk)
                    Text("管理生活在露露机里的角色", color = HubBlueGray, fontSize = 14.sp)
                }
                FilledIconButton(
                    onClick = {},
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = HubWheat),
                ) { Icon(Icons.Outlined.Add, "新建角色", tint = HubInk) }
            }
        }
        items(characters) { character ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { },
                colors = CardDefaults.cardColors(containerColor = HubCard),
                border = BorderStroke(1.dp, HubBorder),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HubAvatar(character.initial, 58)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(character.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                if (character.active) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(color = Color(0xFFFFF2C7), shape = RoundedCornerShape(12.dp)) {
                                        Text("使用中", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp)
                                    }
                                }
                            }
                            Text(character.status, color = HubBlueGray, fontSize = 13.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = HubBlueGray)
                    }
                    HorizontalDivider(color = HubBorder)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        CharacterAction(Icons.Outlined.Tune, "角色设置")
                        CharacterAction(Icons.Outlined.Public, "世界书")
                        CharacterAction(Icons.Outlined.NotificationsNone, "主动联系")
                        CharacterAction(Icons.Outlined.Call, "来电设置")
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, HubBorder),
            ) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("新建角色")
            }
        }
    }
}

@Composable
private fun CharacterAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(
        modifier = Modifier.clickable { }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, tint = HubBlueGray, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 11.sp, color = HubBlueGray)
    }
}

@Composable
fun MomentsPlaceholderScreen() {
    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = HubCard),
            border = BorderStroke(1.dp, HubBorder),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.DynamicFeed, null, tint = HubBlueGray, modifier = Modifier.size(40.dp))
                Text("朋友圈", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("入口已经保留。等主人确定发布、互动和角色动态规则后再正式实现。", color = HubBlueGray)
            }
        }
    }
}

private data class ProfileEntry(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun MyProfileScreen() {
    val entries = listOf(
        ProfileEntry("个人资料", "头像、名字和角色称呼", Icons.Outlined.PersonOutline),
        ProfileEntry("成就与收藏", "查看已获得的成就和珍藏内容", Icons.Outlined.EmojiEvents),
        ProfileEntry("账号与数据", "本地数据、同步状态与导出入口", Icons.Outlined.CloudQueue),
        ProfileEntry("隐私与安全", "密钥保护、日志与敏感信息", Icons.Outlined.Shield),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = HubCard),
                border = BorderStroke(1.dp, HubBorder),
                shape = RoundedCornerShape(24.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    HubAvatar("主", 66)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("主人", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("露露机的拥有者", color = HubBlueGray)
                    }
                    Icon(Icons.Outlined.Edit, "编辑资料", tint = HubBlueGray)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileStat("0", "成就", Modifier.weight(1f))
                ProfileStat("0", "收藏", Modifier.weight(1f))
                ProfileStat("1", "角色", Modifier.weight(1f))
            }
        }
        items(entries) { entry ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { },
                colors = CardDefaults.cardColors(containerColor = HubCard),
                border = BorderStroke(1.dp, HubBorder),
                shape = RoundedCornerShape(19.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0xFFFFF3D0), shape = CircleShape) {
                        Icon(entry.icon, null, tint = HubBlueGray, modifier = Modifier.padding(10.dp).size(23.dp))
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, fontWeight = FontWeight.Bold)
                        Text(entry.subtitle, color = HubBlueGray, fontSize = 12.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = HubBlueGray)
                }
            }
        }
    }
}

@Composable
private fun ProfileStat(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = HubCard),
        border = BorderStroke(1.dp, HubBorder),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(label, color = HubBlueGray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HubAvatar(text: String, size: Int) {
    Surface(modifier = Modifier.size(size.dp), color = HubWheat, shape = CircleShape) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontWeight = FontWeight.Bold, color = HubInk, fontSize = (size / 2.5).sp)
        }
    }
}
