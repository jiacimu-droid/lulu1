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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
private val InventoryDarkMutedV5 = Color(0xFFB8C5BD)

private enum class ApocalypseInventoryCategoryV5(
    val label: String,
    val icon: ImageVector,
    val subtitle: String,
) {
    Money("资金", Icons.Outlined.AccountBalanceWallet, "当前可用余额"),
    Food("食物", Icons.Outlined.Restaurant, "主食、即食餐、罐头、零食与其他可食用物资"),
    Water("饮水", Icons.Outlined.WaterDrop, "瓶装水、桶装水、饮料与可直接饮用水"),
    Medicine("药品医疗", Icons.Outlined.MedicalServices, "药物、急救、消毒、防疫与医疗耗材"),
    Household("生活用品", Icons.Outlined.Home, "洗漱、清洁、衣物、床品、餐具与日常消耗品"),
    Combat("战斗", Icons.Outlined.GpsFixed, "枪械、弹药、冷兵器、防暴防弹装备与战术护具"),
    Survival("生存装备", Icons.Outlined.Search, "背包、帐篷、照明、导航、过滤、防护与户外装备"),
    Energy("能源燃料", Icons.Outlined.AutoAwesome, "汽柴油、燃气、燃料、电池、发电与储能物资"),
    Electronics("电子通讯", Icons.Outlined.Description, "手机、对讲机、无线电、电脑、充电与电子设备"),
    Tool("工具", Icons.Outlined.Handyman, "维修、拆装、生产、测量与专业工具"),
    Material("材料", Icons.Outlined.Construction, "木材、金属、建材、线材、零件与加工材料"),
    Vehicle("载具", Icons.Outlined.DirectionsCar, "汽车、摩托、自行车与其他运输载具"),
    Key("钥匙权限", Icons.Outlined.Key, "钥匙、门禁卡、凭证、密码与访问权限"),
    Core("晶核", Icons.Outlined.AutoAwesome, "可安全利用的标准晶核等价量"),
    Document("文件资料", Icons.Outlined.Description, "档案、清单、说明书、日志、图纸与纸面资料"),
    Clue("线索情报", Icons.Outlined.Search, "情报、口供、坐标、编号、发现与调查线索"),
    Other("其他", Icons.Outlined.Home, "暂时无法精确归类的实体物资；识别失败也会保留在这里"),
}

private val foodWordsV5 = listOf(
    "罐头", "方便面", "泡面", "面饼", "饼干", "压缩粮", "压缩饼干", "大米", "米袋", "面粉", "挂面", "面条",
    "面包", "火腿", "香肠", "午餐肉", "水饺", "饺子", "馒头", "快餐", "盒饭", "点心", "糕点", "蛋糕",
    "巧克力", "糖果", "糖", "能量棒", "肉干", "冻肉", "食品", "粮食", "食用油", "调味料", "蔬菜", "水果",
    "薯片", "零食", "辣条", "坚果", "果干", "海苔", "威化", "曲奇", "果冻", "麦片", "燕麦", "泡芙",
)
private val waterWordsV5 = listOf("矿泉水", "纯净水", "饮用水", "瓶装水", "桶装水", "净水", "饮料", "果汁", "汽水", "苏打水", "运动饮料")
private val medicineWordsV5 = listOf("药品", "药物", "退烧", "止痛", "消炎", "抗生素", "抗过敏", "止泻", "胃药", "感冒药", "维生素", "绷带", "纱布", "创可贴", "碘伏", "医用酒精", "消毒液", "止血", "急救包", "医疗包", "注射器", "口罩", "体温计", "医疗耗材")
private val householdWordsV5 = listOf("卫生纸", "厕纸", "纸巾", "湿巾", "卫生巾", "牙刷", "牙膏", "牙线", "洗面奶", "洗发", "护发", "沐浴", "香皂", "肥皂", "洗衣液", "洗洁精", "清洁剂", "抹布", "拖把", "扫帚", "毛巾", "浴巾", "衣架", "垃圾袋", "收纳箱", "床单", "被套", "被子", "毯子", "枕头", "内衣", "内裤", "袜子", "衣物", "外套", "裤子", "鞋子", "拖鞋", "餐具", "筷子", "勺子", "叉子", "杯子", "饭盒", "餐盒", "梳子", "镜子", "指甲剪", "棉签")
private val combatWordsV5 = listOf(
    "枪械", "枪支", "手枪", "步枪", "冲锋枪", "霰弹枪", "猎枪", "狙击枪", "机枪", "卡宾枪", "防爆弹枪", "防暴弹枪",
    "枪弹", "胶弹", "橡胶弹", "防暴弹", "弹药", "子弹", "弹丸", "弹匣", "弹夹", "砍刀", "开山刀", "折叠刀",
    "战术刀", "匕首", "军刀", "长刀", "短刀", "斧头", "战斧", "弓箭", "弓弩", "弩箭", "甩棍", "防身棍",
    "警棍", "撬棍", "盾牌", "防暴盾", "防爆盾", "防爆装备", "防暴装备", "防弹装备", "防刺装备", "防弹衣",
    "防刺服", "防暴服", "防爆服", "战术头盔", "防弹头盔", "护甲", "战术背心", "防弹背心", "战术护具",
    "战术手套", "护膝", "护肘", "firearm", "ammo", "ammunition", "armor", "riot gear", "tactical gear",
)
private val survivalWordsV5 = listOf("背包", "登山包", "行军包", "帐篷", "天幕", "睡袋", "防潮垫", "折叠床", "手电筒", "手电", "头灯", "营灯", "指南针", "罗盘", "望远镜", "求生哨", "救生衣", "安全绳", "攀岩绳", "登山绳", "登山扣", "滤水器", "净水器", "净水片", "防毒面具", "防护面具", "护目镜", "雨衣", "雨披", "保温毯", "急救毯", "工兵铲", "户外炉", "卡式炉", "露营炉")
private val energyWordsV5 = listOf("汽油", "柴油", "煤油", "燃油", "燃料", "液化气", "煤气", "天然气", "燃气", "气罐", "丁烷", "丙烷", "固体燃料", "酒精燃料", "木炭", "煤炭", "木柴", "发电机", "太阳能板", "蓄电池", "备用电池", "干电池", "锂电池", "电池组", "储能电源", "户外电源")
private val electronicsWordsV5 = listOf("手机", "智能手机", "对讲机", "无线电", "收音机", "卫星电话", "电脑", "笔记本电脑", "平板电脑", "充电宝", "移动电源", "充电器", "数据线", "电源线", "插线板", "电源适配器", "相机", "摄像机", "无人机", "gps", "定位器", "导航仪", "耳机", "麦克风", "监控", "摄像头", "传感器", "探测器", "智能手表", "硬盘", "u盘")
private val toolWordsV5 = listOf("锤子", "铁锤", "榔头", "扳手", "螺丝刀", "改锥", "钳子", "电钻", "手电钻", "电锯", "手锯", "锯子", "工具箱", "卷尺", "水平尺", "测电笔", "万用表", "电烙铁", "焊机", "铁锹", "铁铲", "铲子", "镐", "锄头", "剪刀", "美工刀", "胶枪", "管钳", "套筒", "棘轮", "千斤顶")
private val materialWordsV5 = listOf("木板", "木方", "木材", "胶合板", "钢板", "铁板", "钢管", "铁管", "角钢", "型钢", "钢筋", "铁丝", "钢丝", "铁钉", "钉子", "螺丝", "螺栓", "螺母", "垫片", "水泥", "砂浆", "沙子", "砂石", "砖", "玻璃", "建材", "电线", "线缆", "网线", "铜线", "胶带", "扎带", "绳子", "麻绳", "塑料布", "防水布", "篷布", "管材", "水管", "软管", "零件", "配件", "轴承", "齿轮", "弹簧", "密封圈", "滤芯", "五金件")
private val vehicleWordsV5 = listOf("汽车", "轿车", "越野车", "suv", "面包车", "货车", "卡车", "皮卡", "摩托", "电动车", "自行车", "三轮车", "拖车", "房车", "巴士", "救护车", "工程车", "叉车", "船", "快艇")
private val keyWordsV5 = listOf("钥匙", "门禁卡", "门卡", "房卡", "通行证", "权限卡", "身份卡", "员工卡", "密码", "口令", "验证码", "访问权限")
private val documentWordsV5 = listOf("文件", "档案", "记录", "清单", "报告", "日志", "名单", "图纸", "施工图", "说明书", "手册", "照片", "录音", "录像", "票据", "收据", "合同", "证件", "账本", "笔记", "信件")
private val clueWordsV5 = listOf("线索", "情报", "消息", "口供", "坐标", "位置情报", "编号", "暗号", "标记", "痕迹", "秘密", "传闻", "目击")

private fun inventoryTextV5(asset: ApocalypseV3Asset): String = "${asset.title} ${asset.tag} ${asset.detail}".lowercase()
private fun hasInventoryWordV5(text: String, words: List<String>): Boolean = words.any(text::contains)

private fun apocalypseInventoryCategoryForAssetV5(asset: ApocalypseV3Asset): ApocalypseInventoryCategoryV5 {
    val text = inventoryTextV5(asset)
    val tags = asset.tag.split('；', ';').map { it.trim() }

    // Strong semantic types first. This deliberately outranks a stale “待分类” tag so an old
    // “香辣薯片/防爆盾牌” row repairs itself as soon as the new APK opens.
    if (asset.kind == ApocalypseV3AssetKind.Map) return ApocalypseInventoryCategoryV5.Document
    if (asset.kind == ApocalypseV3AssetKind.Core) return ApocalypseInventoryCategoryV5.Core
    if (asset.kind == ApocalypseV3AssetKind.Key) return ApocalypseInventoryCategoryV5.Key
    if (asset.kind == ApocalypseV3AssetKind.Vehicle) return ApocalypseInventoryCategoryV5.Vehicle
    if (asset.kind == ApocalypseV3AssetKind.Document) return ApocalypseInventoryCategoryV5.Document
    if (asset.kind == ApocalypseV3AssetKind.Clue && hasInventoryWordV5(text, clueWordsV5)) return ApocalypseInventoryCategoryV5.Clue

    if (asset.kind == ApocalypseV3AssetKind.Food || hasInventoryWordV5(text, foodWordsV5)) return ApocalypseInventoryCategoryV5.Food
    if (asset.kind == ApocalypseV3AssetKind.Water || hasInventoryWordV5(text, waterWordsV5)) return ApocalypseInventoryCategoryV5.Water
    if (asset.kind == ApocalypseV3AssetKind.Medicine || hasInventoryWordV5(text, medicineWordsV5)) return ApocalypseInventoryCategoryV5.Medicine
    if (asset.kind == ApocalypseV3AssetKind.Weapon || hasInventoryWordV5(text, combatWordsV5)) return ApocalypseInventoryCategoryV5.Combat
    if (hasInventoryWordV5(text, electronicsWordsV5)) return ApocalypseInventoryCategoryV5.Electronics
    if (hasInventoryWordV5(text, energyWordsV5)) return ApocalypseInventoryCategoryV5.Energy
    if (hasInventoryWordV5(text, survivalWordsV5)) return ApocalypseInventoryCategoryV5.Survival
    if (hasInventoryWordV5(text, householdWordsV5)) return ApocalypseInventoryCategoryV5.Household
    if (hasInventoryWordV5(text, vehicleWordsV5)) return ApocalypseInventoryCategoryV5.Vehicle
    if (hasInventoryWordV5(text, keyWordsV5)) return ApocalypseInventoryCategoryV5.Key
    if (hasInventoryWordV5(text, documentWordsV5)) return ApocalypseInventoryCategoryV5.Document
    if (hasInventoryWordV5(text, clueWordsV5)) return ApocalypseInventoryCategoryV5.Clue
    if (hasInventoryWordV5(text, toolWordsV5)) return ApocalypseInventoryCategoryV5.Tool
    if (hasInventoryWordV5(text, materialWordsV5)) return ApocalypseInventoryCategoryV5.Material

    // Only genuinely unresolved rows fall through to the manual/unknown tags.
    if (tags.any { it == "战斗" }) return ApocalypseInventoryCategoryV5.Combat
    if (tags.any { it == "生活用品" }) return ApocalypseInventoryCategoryV5.Household
    if (tags.any { it == "生存装备" }) return ApocalypseInventoryCategoryV5.Survival
    if (tags.any { it == "能源燃料" }) return ApocalypseInventoryCategoryV5.Energy
    if (tags.any { it == "电子通讯" }) return ApocalypseInventoryCategoryV5.Electronics
    if (tags.any { it == "待分类" || it == "其他" }) return ApocalypseInventoryCategoryV5.Other

    return when (asset.kind) {
        ApocalypseV3AssetKind.Food -> ApocalypseInventoryCategoryV5.Food
        ApocalypseV3AssetKind.Water -> ApocalypseInventoryCategoryV5.Water
        ApocalypseV3AssetKind.Medicine -> ApocalypseInventoryCategoryV5.Medicine
        ApocalypseV3AssetKind.Material -> ApocalypseInventoryCategoryV5.Material
        ApocalypseV3AssetKind.Weapon -> ApocalypseInventoryCategoryV5.Combat
        ApocalypseV3AssetKind.Vehicle -> ApocalypseInventoryCategoryV5.Vehicle
        ApocalypseV3AssetKind.Key -> ApocalypseInventoryCategoryV5.Key
        ApocalypseV3AssetKind.Document -> ApocalypseInventoryCategoryV5.Document
        ApocalypseV3AssetKind.Map -> ApocalypseInventoryCategoryV5.Document
        ApocalypseV3AssetKind.Core -> ApocalypseInventoryCategoryV5.Core
        ApocalypseV3AssetKind.Clue, ApocalypseV3AssetKind.Tool -> ApocalypseInventoryCategoryV5.Other
    }
}

@Composable
internal fun ApocalypseInventoryBrowserSheetV5(save: ApocalypseV3Save) {
    val context = LocalContext.current
    val storage = remember(context) { ApocalypseSurvivalV3Store(context) }
    val latestStored = remember(save.id, save.scene, save.updatedAt) {
        storage.loadSave()?.takeIf { it.id == save.id } ?: save
    }
    val initialWorking = remember(latestStored.id, latestStored.scene, latestStored.updatedAt) {
        repairApocalypseCurrentSceneInventoryV5(context, latestStored)
    }
    var workingSave by remember(save.id, save.scene, initialWorking.updatedAt) { mutableStateOf(initialWorking) }
    var selectedCategory by remember(save.id, save.scene) { mutableStateOf<ApocalypseInventoryCategoryV5?>(null) }
    var deleteTarget by remember { mutableStateOf<ApocalypseV3Asset?>(null) }

    LaunchedEffect(initialWorking.updatedAt) {
        if (initialWorking != latestStored) storage.save(initialWorking)
    }

    val assets = remember(workingSave.director.assets) {
        workingSave.director.assets.filterNot { it.kind == ApocalypseV3AssetKind.Map }
    }
    val categorized = remember(assets) { assets.groupBy(::apocalypseInventoryCategoryForAssetV5) }
    val alwaysVisible = remember {
        setOf(
            ApocalypseInventoryCategoryV5.Money,
            ApocalypseInventoryCategoryV5.Food,
            ApocalypseInventoryCategoryV5.Water,
            ApocalypseInventoryCategoryV5.Medicine,
            ApocalypseInventoryCategoryV5.Household,
            ApocalypseInventoryCategoryV5.Combat,
            ApocalypseInventoryCategoryV5.Tool,
            ApocalypseInventoryCategoryV5.Material,
            ApocalypseInventoryCategoryV5.Core,
            ApocalypseInventoryCategoryV5.Other,
        )
    }
    val categories = remember(categorized) {
        ApocalypseInventoryCategoryV5.entries.filter { it in alwaysVisible || categorized[it].orEmpty().isNotEmpty() }
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(.90f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("物资仓库", color = InventoryInkV5, fontSize = 26.sp, fontWeight = FontWeight.Black)
        ApocalypseInventoryOverviewV5(workingSave)
        Text("分类", color = InventoryInkV5, fontSize = 18.sp, fontWeight = FontWeight.Black)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(categories.chunked(2)) { rowCategories ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    rowCategories.forEach { category ->
                        val categoryAssets = categorized[category].orEmpty()
                        ApocalypseInventoryCategoryCardV5(
                            modifier = Modifier.weight(1f),
                            category = category,
                            value = inventoryCategoryValueV5(category, workingSave, categorized),
                            itemCount = categoryAssets.size,
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
        ModalBottomSheet(onDismissRequest = { selectedCategory = null }, containerColor = InventoryBgV5) {
            ApocalypseInventoryCategoryDetailV5(
                save = workingSave,
                category = category,
                assets = categorized[category].orEmpty(),
                categorized = categorized,
                onDelete = { deleteTarget = it },
            )
        }
    }

    deleteTarget?.let { asset ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除“${asset.title}”？") },
            text = {
                Text("这会从当前末世存档的具体物资账本中永久删除这条记录。若它本身属于食物、饮水、药品、材料或晶核，对应总量也会同步扣除；剧情正文不会被删除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = deleteApocalypseInventoryAssetV5(context, workingSave, asset)
                    storage.save(next)
                    workingSave = next
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ApocalypseInventoryOverviewV5(save: ApocalypseV3Save) {
    Surface(color = InventoryDarkV5, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, InventoryDarkLineV5)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ApocalypseInventoryOverviewValueV5("生命", save.stats.health.toString())
                ApocalypseInventoryOverviewValueV5("体力", save.stats.stamina.toString())
                ApocalypseInventoryOverviewValueV5("感染", save.stats.infection.toString())
                ApocalypseInventoryOverviewValueV5("士气", save.stats.morale.toString())
            }
            HorizontalDivider(color = InventoryDarkLineV5)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ApocalypseInventoryOverviewValueV5("空间", "Lv.${save.stats.playerAbilityLevel}")
                ApocalypseInventoryOverviewValueV5("共鸣", if (save.stats.playerAbilityLevel >= 5) "MAX" else "${save.stats.playerAbilityXp}/${abilityXpThresholdV3(save.stats.playerAbilityLevel)}")
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
    Surface(modifier = modifier.heightIn(min = 96.dp), onClick = onClick, color = InventoryCardV5, shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, InventoryBorderV5)) {
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
                Text(if (category == ApocalypseInventoryCategoryV5.Money) "查看余额" else "$itemCount 条具体记录", color = InventoryMutedV5, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ApocalypseInventoryCategoryDetailV5(
    save: ApocalypseV3Save,
    category: ApocalypseInventoryCategoryV5,
    assets: List<ApocalypseV3Asset>,
    categorized: Map<ApocalypseInventoryCategoryV5, List<ApocalypseV3Asset>>,
    onDelete: (ApocalypseV3Asset) -> Unit,
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(.84f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = InventoryAccentSoftV5, shape = RoundedCornerShape(13.dp)) {
                Icon(category.icon, null, tint = InventoryAccentV5, modifier = Modifier.padding(10.dp).size(23.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(category.label, color = InventoryInkV5, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text(category.subtitle, color = InventoryMutedV5, fontSize = 11.sp)
            }
            Text(inventoryCategoryValueV5(category, save, categorized), color = InventoryAccentV5, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        if (category == ApocalypseInventoryCategoryV5.Money) {
            Surface(color = InventoryCardV5, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, InventoryBorderV5)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("当前可用资金", color = InventoryInkV5, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("¥${save.stats.money}", color = InventoryAccentV5, fontWeight = FontWeight.Black, fontSize = 26.sp)
                    Text("购买、付款、出售、报酬和退款只在剧情真实发生时改变余额。", color = InventoryMutedV5, fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
        } else if (assets.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (category == ApocalypseInventoryCategoryV5.Other) "目前没有待分类物资。以后任何已经获得但暂时识别不了的实体物品都会出现在这里。" else "这一类目前还没有具体物品记录。",
                    color = InventoryMutedV5,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(assets, key = { it.id }) { asset ->
                    Surface(color = InventoryCardV5, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, InventoryBorderV5)) {
                        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 12.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(category.icon, null, tint = InventoryAccentV5, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(asset.title, color = InventoryInkV5, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("×${asset.quantity}${apocalypseInventoryAssetUnitV5(asset)}", color = InventoryAccentV5, fontSize = 12.sp, fontWeight = FontWeight.Black)
                                IconButton(onClick = { onDelete(asset) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Outlined.DeleteOutline, "删除这条物资", tint = InventoryMutedV5, modifier = Modifier.size(18.dp))
                                }
                            }
                            asset.tag
                                .split('；', ';')
                                .map(String::trim)
                                .filter { it.isNotBlank() && !it.startsWith("单位=") }
                                .joinToString(" · ")
                                .takeIf(String::isNotBlank)
                                ?.let { Text(it, color = InventoryAccentV5, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                            Text(asset.detail.ifBlank { "暂无更多说明" }, color = InventoryMutedV5, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

private fun apocalypseInventoryAssetUnitV5(asset: ApocalypseV3Asset): String {
    Regex("(?:^|[；;])单位=([^；;]+)").find(asset.tag)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    return when (asset.kind) {
        ApocalypseV3AssetKind.Water -> if (asset.title.contains("桶装")) "桶" else "瓶"
        ApocalypseV3AssetKind.Food -> when {
            asset.title.contains("罐头") || asset.title.contains("罐装") -> "罐"
            asset.title.contains("方便面") || asset.title.contains("泡面") || asset.title.contains("饼干") || asset.title.contains("薯片") || asset.title.contains("零食") -> "包"
            asset.title.contains("盒") || asset.title.contains("快餐") -> "盒"
            else -> "份"
        }
        ApocalypseV3AssetKind.Weapon -> when {
            asset.title.contains("盾") -> "面"
            asset.title.contains("子弹") || asset.title.contains("弹药") || asset.title.contains("枪弹") || asset.title.contains("胶弹") -> "发"
            asset.title.contains("枪") -> "支"
            else -> "件"
        }
        ApocalypseV3AssetKind.Vehicle -> "辆"
        ApocalypseV3AssetKind.Key -> "枚"
        ApocalypseV3AssetKind.Document, ApocalypseV3AssetKind.Clue -> "份"
        ApocalypseV3AssetKind.Core -> "枚"
        else -> "件"
    }
}

private fun inventoryCategoryValueV5(
    category: ApocalypseInventoryCategoryV5,
    save: ApocalypseV3Save,
    categorized: Map<ApocalypseInventoryCategoryV5, List<ApocalypseV3Asset>>,
): String = when (category) {
    ApocalypseInventoryCategoryV5.Money -> "¥${save.stats.money}"
    ApocalypseInventoryCategoryV5.Food -> save.stats.food.toString()
    ApocalypseInventoryCategoryV5.Water -> save.stats.water.toString()
    ApocalypseInventoryCategoryV5.Medicine -> save.stats.medicine.toString()
    ApocalypseInventoryCategoryV5.Material -> save.stats.materials.toString()
    ApocalypseInventoryCategoryV5.Core -> save.stats.crystalCores.toString()
    else -> categorized[category].orEmpty().sumOf { it.quantity }.toString()
}
