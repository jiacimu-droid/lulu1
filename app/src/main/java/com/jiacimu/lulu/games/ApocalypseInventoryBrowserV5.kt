package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val InventoryBgV5 = Color(0xFFF5F6F3)
private val InventoryCardV5 = Color.White
private val InventoryInkV5 = Color(0xFF1B211E)
private val InventoryMutedV5 = Color(0xFF68726C)
private val InventoryBorderV5 = Color(0xFFD9DED9)
private val InventoryAccentV5 = Color(0xFF526D5E)
private val InventoryAccentSoftV5 = Color(0xFFDDE7E0)
private val InventoryDarkV5 = Color(0xFF101714)
private val InventoryDarkLineV5 = Color(0xFF34423B)
private val InventoryDarkTextV5 = Color(0xFFF7F9F6)
private val InventoryDarkMutedV5 = Color(0xFFB8C5BD)

private enum class ApocalypseInventoryCategoryV5(
    val label: String,
    val icon: ImageVector,
    val kinds: Set<ApocalypseV3AssetKind>,
    val subtitle: String,
) {
    Money("资金", Icons.Outlined.AccountBalanceWallet, emptySet(), "当前可用余额"),
    Food("食物", Icons.Outlined.Restaurant, setOf(ApocalypseV3AssetKind.Food), "主食、即食餐、罐头与其他食材"),
    Water("饮水", Icons.Outlined.WaterDrop, setOf(ApocalypseV3AssetKind.Water), "瓶装水、储水与可直接饮用水"),
    Medicine("药品", Icons.Outlined.MedicalServices, setOf(ApocalypseV3AssetKind.Medicine), "药物、急救与医疗耗材"),
    Material("材料", Icons.Outlined.Construction, setOf(ApocalypseV3AssetKind.Material), "建设、维修与加工材料"),
    Weapon("武器", Icons.Outlined.GpsFixed, setOf(ApocalypseV3AssetKind.Weapon), "近战、远程与防卫装备"),
    Tool("工具", Icons.Outlined.Handyman, setOf(ApocalypseV3AssetKind.Tool), "工具、设备与生存用品"),
    Vehicle("载具", Icons.Outlined.DirectionsCar, setOf(ApocalypseV3AssetKind.Vehicle), "车辆与可移动运输资源"),
    Key("钥匙/权限", Icons.Outlined.Key, setOf(ApocalypseV3AssetKind.Key), "门禁、钥匙与访问权限"),
    Core("晶核", Icons.Outlined.AutoAwesome, setOf(ApocalypseV3AssetKind.Core), "可安全利用的标准晶核等价量"),
    Clue("线索", Icons.Outlined.Search, setOf(ApocalypseV3AssetKind.Clue), "已经确认取得的调查线索"),
    Document("文件", Icons.Outlined.Description, setOf(ApocalypseV3AssetKind.Document), "档案、清单、记录与纸面资料"),
}

@Composable
internal fun ApocalypseInventoryBrowserSheetV5(save: ApocalypseV3Save) {
    var selectedCategory by remember(save.id, save.scene) { mutableStateOf<ApocalypseInventoryCategoryV5?>(null) }
    val assets = remember(save.director.assets) { save.director.assets.filterNot { it.kind == ApocalypseV3AssetKind.Map } }
    val mapCount = remember(save.director.assets) { save.director.assets.count { it.kind == ApocalypseV3AssetKind.Map } }
    val categories = remember(save.stats, assets) {
        ApocalypseInventoryCategoryV5.entries.filter { category ->
            category == ApocalypseInventoryCategoryV5.Money ||
                category in setOf(
                    ApocalypseInventoryCategoryV5.Food,
                    ApocalypseInventoryCategoryV5.Water,
                    ApocalypseInventoryCategoryV5.Medicine,
                    ApocalypseInventoryCategoryV5.Material,
                    ApocalypseInventoryCategoryV5.Core,
                ) || assets.any { it.kind in category.kinds }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(.90f)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("物资仓库", color = InventoryInkV5, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("先按类别看总量，再点进去看具体买了什么、拿到了什么。", color = InventoryMutedV5, fontSize = 12.sp, lineHeight = 18.sp)
        }

        ApocalypseInventoryOverviewV5(save)

        if (mapCount > 0) {
            Surface(
                color = InventoryAccentSoftV5,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, InventoryBorderV5),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Map, null, tint = InventoryAccentV5, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("地图资料已归入地图系统", color = InventoryInkV5, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("$mapCount 份地图/施工图不会再混在线索清单里；请从游戏里的“地图”查看地点和子区域。", color = InventoryMutedV5, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                }
            }
        }

        Text("分类", color = InventoryInkV5, fontSize = 18.sp, fontWeight = FontWeight.Black)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(categories.chunked(2)) { rowCategories ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    rowCategories.forEach { category ->
                        ApocalypseInventoryCategoryCardV5(
                            modifier = Modifier.weight(1f),
                            category = category,
                            value = inventoryCategoryValueV5(category, save, assets),
                            itemCount = assets.count { it.kind in category.kinds },
                            onClick = { selectedCategory = category },
                        )
                    }
                    if (rowCategories.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    selectedCategory?.let { category ->
        ModalBottomSheet(
            onDismissRequest = { selectedCategory = null },
            containerColor = InventoryBgV5,
        ) {
            ApocalypseInventoryCategoryDetailV5(
                save = save,
                category = category,
                assets = assets.filter { it.kind in category.kinds },
            )
        }
    }
}

@Composable
private fun ApocalypseInventoryOverviewV5(save: ApocalypseV3Save) {
    Surface(
        color = InventoryDarkV5,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, InventoryDarkLineV5),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${save.director.phase} · ${save.director.location}",
                color = InventoryDarkTextV5,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ApocalypseInventoryOverviewValueV5("资金", "¥${save.stats.money}")
                ApocalypseInventoryOverviewValueV5("食物", save.stats.food.toString())
                ApocalypseInventoryOverviewValueV5("饮水", save.stats.water.toString())
                ApocalypseInventoryOverviewValueV5("药品", save.stats.medicine.toString())
            }
            HorizontalDivider(color = InventoryDarkLineV5)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ApocalypseInventoryOverviewValueV5("材料", save.stats.materials.toString())
                ApocalypseInventoryOverviewValueV5("晶核", save.stats.crystalCores.toString())
                ApocalypseInventoryOverviewValueV5("空间", "Lv.${save.stats.playerAbilityLevel}")
                ApocalypseInventoryOverviewValueV5(
                    "共鸣",
                    if (save.stats.playerAbilityLevel >= 5) "MAX" else "${save.stats.playerAbilityXp}/${abilityXpThresholdV3(save.stats.playerAbilityLevel)}",
                )
            }
        }
    }
}

@Composable
private fun ApocalypseInventoryOverviewValueV5(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color(0xFFB7CDBF), fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(label, color = InventoryDarkMutedV5, fontSize = 10.sp)
    }
}

@Composable
private fun ApocalypseInventoryCategoryCardV5(
    modifier: Modifier,
    category: ApocalypseInventoryCategoryV5,
    value: String,
    itemCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.heightIn(min = 96.dp),
        onClick = onClick,
        color = InventoryCardV5,
        shape = RoundedCornerShape(19.dp),
        border = BorderStroke(1.dp, InventoryBorderV5),
    ) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = InventoryAccentSoftV5, shape = RoundedCornerShape(11.dp)) {
                    Icon(category.icon, null, tint = InventoryAccentV5, modifier = Modifier.padding(8.dp).size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.ChevronRight, null, tint = InventoryMutedV5, modifier = Modifier.size(19.dp))
            }
            Column {
                Text(value, color = InventoryAccentV5, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text(category.label, color = InventoryInkV5, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (category == ApocalypseInventoryCategoryV5.Money) "查看余额" else "$itemCount 条具体记录",
                    color = InventoryMutedV5,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun ApocalypseInventoryCategoryDetailV5(
    save: ApocalypseV3Save,
    category: ApocalypseInventoryCategoryV5,
    assets: List<ApocalypseV3Asset>,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(.84f)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = InventoryAccentSoftV5, shape = RoundedCornerShape(13.dp)) {
                Icon(category.icon, null, tint = InventoryAccentV5, modifier = Modifier.padding(10.dp).size(23.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(category.label, color = InventoryInkV5, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text(category.subtitle, color = InventoryMutedV5, fontSize = 11.sp)
            }
            Text(inventoryCategoryValueV5(category, save, save.director.assets), color = InventoryAccentV5, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }

        if (category == ApocalypseInventoryCategoryV5.Money) {
            Surface(color = InventoryCardV5, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, InventoryBorderV5)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("当前可用资金", color = InventoryInkV5, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("¥${save.stats.money}", color = InventoryAccentV5, fontWeight = FontWeight.Black, fontSize = 26.sp)
                    Text("资金是余额字段，不会把每张纸币做成物品条目。购买、付款、出售、报酬和退款只在剧情真实发生时改变余额。", color = InventoryMutedV5, fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
        } else if (assets.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("这一类目前还没有具体物品记录。", color = InventoryMutedV5, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(assets, key = { it.id }) { asset ->
                    Surface(color = InventoryCardV5, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, InventoryBorderV5)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(category.icon, null, tint = InventoryAccentV5, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(asset.title, color = InventoryInkV5, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("×${asset.quantity}", color = InventoryAccentV5, fontSize = 12.sp, fontWeight = FontWeight.Black)
                            }
                            asset.tag.takeIf(String::isNotBlank)?.let { tag ->
                                Text(tag, color = InventoryAccentV5, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(asset.detail.ifBlank { "暂无更多说明" }, color = InventoryMutedV5, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

private fun inventoryCategoryValueV5(
    category: ApocalypseInventoryCategoryV5,
    save: ApocalypseV3Save,
    assets: List<ApocalypseV3Asset>,
): String = when (category) {
    ApocalypseInventoryCategoryV5.Money -> "¥${save.stats.money}"
    ApocalypseInventoryCategoryV5.Food -> save.stats.food.toString()
    ApocalypseInventoryCategoryV5.Water -> save.stats.water.toString()
    ApocalypseInventoryCategoryV5.Medicine -> save.stats.medicine.toString()
    ApocalypseInventoryCategoryV5.Material -> save.stats.materials.toString()
    ApocalypseInventoryCategoryV5.Core -> save.stats.crystalCores.toString()
    else -> assets.filter { it.kind in category.kinds }.sumOf { it.quantity }.toString()
}
