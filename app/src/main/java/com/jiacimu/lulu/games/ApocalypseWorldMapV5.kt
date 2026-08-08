package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class ApocalypseWorldPlaceV5(
    val id: String,
    val name: String,
    val kind: String,
    val detail: String,
)

internal data class ApocalypseWorldCityV5(
    val id: String,
    val name: String,
    val direction: String,
    val x: Float,
    val y: Float,
    val summary: String,
    val hazard: String,
    val travelFromLinjiang: String,
    val places: List<ApocalypseWorldPlaceV5>,
)

private data class ApocalypseWorldRoadV5(val from: String, val to: String, val label: String)

private val MapNight = Color(0xFF07111F)
private val MapNightSoft = Color(0xFF0D2033)
private val MapLine = Color(0xFF31536F)
private val MapBlue = Color(0xFF4EA8FF)
private val MapBlueSoft = Color(0xFF8CCAFF)
private val MapInk = Color(0xFF0A1726)
private val MapMuted = Color(0xFF607287)
private val MapBorder = Color(0xFFD3E3F2)
private val MapSurface = Color(0xFFF3F8FD)
private val MapCard = Color.White

internal fun apocalypseWorldCitiesV5(): List<ApocalypseWorldCityV5> = listOf(
    ApocalypseWorldCityV5(
        id = "linjiang",
        name = "临江市",
        direction = "东澜中部 · 临江平原",
        x = 0.43f,
        y = 0.48f,
        summary = "东澜地区人口最多的内河城市，也是故事开局地。老城、高架、地铁、江港和西郊物流带把它切成数个完全不同的生存区。",
        hazard = "人口密度极高；主沉降后桥梁、隧道和医院会首先形成拥堵与感染热点。",
        travelFromLinjiang = "当前城市",
        places = listOf(
            ApocalypseWorldPlaceV5("old_home", "旧城区公寓", "居住区", "开局住所。熟悉、隐蔽，但高层储水、防火与楼梯逃生是硬伤。"),
            ApocalypseWorldPlaceV5("south_market", "城南综合市场", "商业区", "食品、水、五金、露营用品集中；灾前采购方便，灾后极易被抢空。"),
            ApocalypseWorldPlaceV5("hospital_2", "临江二院", "医疗区", "药品与急救资源丰富，也是异常神经病例最早聚集的地点之一。"),
            ApocalypseWorldPlaceV5("west_logistics", "西郊物流园", "物流区", "冷库、仓储、货车和燃料集中，灾后会成为补给争夺核心。"),
            ApocalypseWorldPlaceV5("old_metro", "旧城地铁换乘站", "地下设施", "公开图纸只到B4，旧施工档案却留下被抹掉的B8防灾层。"),
            ApocalypseWorldPlaceV5("waterworks", "东江二水厂", "基础设施", "深层取水口、药剂库与备用柴油机决定了数十万人的供水命脉。"),
            ApocalypseWorldPlaceV5("seed_station", "北岸种源站", "科研设施", "城市北缘的小型种源与生态实验站，掌握净生株和种子库资料。"),
        ),
    ),
    ApocalypseWorldCityV5(
        id = "xinchuan",
        name = "新川市",
        direction = "临江以北 · 铁路工业走廊",
        x = 0.46f,
        y = 0.18f,
        summary = "东澜北部的铁路与重工业中心。城南是成片工厂，城北是粮储、编组站和大型变电设施，灾后具有极高战略价值。",
        hazard = "化工泄漏、重型设备事故和铁路尸潮会比普通城区更危险。",
        travelFromLinjiang = "约140公里 · 北环高速/城际铁路",
        places = listOf(
            ApocalypseWorldPlaceV5("rail_hub", "新川高铁枢纽", "交通枢纽", "连接东澜六市的核心铁路节点；秩序崩溃后会迅速成为人口堵塞区。"),
            ApocalypseWorldPlaceV5("grain_depot", "北部中央粮库", "战略仓储", "储粮规模巨大，但仓区封闭、安保严格，灾后必然被大型势力盯上。"),
            ApocalypseWorldPlaceV5("heavy_industry", "临北重工带", "工业区", "机床、钢材、焊接设备和工程车辆齐全，适合中后期基地工业化。"),
            ApocalypseWorldPlaceV5("telecom", "东澜北部通信中心", "通信设施", "拥有卫星链路、应急电源和区域骨干网节点。"),
            ApocalypseWorldPlaceV5("pharma", "新川医药产业园", "医疗工业", "药品原料、无菌车间和冷链仓库集中，但对持续电力依赖极强。"),
        ),
    ),
    ApocalypseWorldCityV5(
        id = "hailing",
        name = "海陵市",
        direction = "东部海岸 · 深水港城市",
        x = 0.77f,
        y = 0.43f,
        summary = "东澜唯一的大型深水港。港区、冷链、船厂和海事设施让它拥有独特的远洋资源，但赤潮对海水与潮间带的影响也最明显。",
        hazard = "海雾、盐雾腐蚀、港区火灾和水生异化体会长期改变行动规则。",
        travelFromLinjiang = "约210公里 · 临海高速",
        places = listOf(
            ApocalypseWorldPlaceV5("port", "海陵深水港", "港口", "集装箱、燃料、船舶和仓库密集，是海上撤离与贸易的关键节点。"),
            ApocalypseWorldPlaceV5("cold_chain", "临港冷链城", "物流区", "大量冻肉、药品和冷藏设备；停电后必须抢时间处理。"),
            ApocalypseWorldPlaceV5("shipyard", "东湾船厂", "工业区", "维修船坞、钢材和柴油设备充足，可发展水上运输。"),
            ApocalypseWorldPlaceV5("marine_uni", "海陵海事大学", "校园/科研", "拥有航海、气象和海洋生态实验设施，适合作为海岸情报中心。"),
            ApocalypseWorldPlaceV5("tidal_plant", "潮汐净水站", "基础设施", "实验性海水淡化与净化设施，赤潮后价值陡增。"),
        ),
    ),
    ApocalypseWorldCityV5(
        id = "lanshan",
        name = "岚山市",
        direction = "西北山地 · 水库与隧道群",
        x = 0.14f,
        y = 0.22f,
        summary = "人口不算多，却控制着东澜西北的水库、山口和风电场。山地天然降低尸潮规模，但道路一旦塌方也会把聚居地彻底孤立。",
        hazard = "滑坡、断路、低温和山谷红雾使救援困难；山区异化动物更活跃。",
        travelFromLinjiang = "约190公里 · 西岭高速",
        places = listOf(
            ApocalypseWorldPlaceV5("reservoir", "岚山一级水库", "水源", "为多座城市提供调水，坝体、净水与输电设施都具有战略意义。"),
            ApocalypseWorldPlaceV5("tunnels", "西岭隧道群", "交通设施", "穿越山脉的唯一快速通道，任何事故都可能切断整片区域。"),
            ApocalypseWorldPlaceV5("windfarm", "高原风电场", "能源设施", "分布式风机在主电网崩溃后仍有恢复价值。"),
            ApocalypseWorldPlaceV5("mine", "旧钨矿区", "矿业区", "地下空间复杂，机械与炸药库存具有价值，同时也极其危险。"),
            ApocalypseWorldPlaceV5("sanatorium", "松岭疗养院", "医疗/避难", "远离市区、拥有独立锅炉和水源，可能成为早期避难点。"),
        ),
    ),
    ApocalypseWorldCityV5(
        id = "yunqi",
        name = "云栖市",
        direction = "西南湖区 · 农业与大学城",
        x = 0.18f,
        y = 0.70f,
        summary = "湖泊、农田与大学城构成的低密度城市。前期看似安全，但长期真正决定价值的是农田、种质、渔业和可持续水源。",
        hazard = "赤潮植物、水体富营养化与大片农田污染会决定这里能否成为长期粮仓。",
        travelFromLinjiang = "约160公里 · 南环国道",
        places = listOf(
            ApocalypseWorldPlaceV5("agri_base", "东澜农科基地", "农业科研", "拥有温室、种子、土壤实验与农业机械，是长期生产的重要节点。"),
            ApocalypseWorldPlaceV5("university", "云栖大学城", "校园区", "实验室、宿舍、体育馆和食堂集中，可容纳大量幸存者。"),
            ApocalypseWorldPlaceV5("lake", "云栖湖东岸", "水域", "水源与渔业价值高，但赤潮后水质和水生生态必须持续监测。"),
            ApocalypseWorldPlaceV5("food_park", "南湖食品工业园", "食品工业", "罐头、粮油、包装和冷库企业密集。"),
            ApocalypseWorldPlaceV5("airport", "云栖支线机场", "交通设施", "跑道、燃油和开阔地适合后期航空与大型避难区建设。"),
        ),
    ),
    ApocalypseWorldCityV5(
        id = "baiyu",
        name = "白榆市",
        direction = "东北高地 · 林业与能源城",
        x = 0.76f,
        y = 0.12f,
        summary = "气候更冷、人口较少的高地城市，林业、天然气储备和抽水蓄能站让它在长期能源体系中非常重要。",
        hazard = "冬季低温、森林异化、长距离补给和人口稀疏会让任何失联都变得致命。",
        travelFromLinjiang = "约320公里 · 经新川转北部干线",
        places = listOf(
            ApocalypseWorldPlaceV5("gas_depot", "白榆燃气储备站", "能源设施", "拥有大型燃气储罐和应急调峰设施，风险与价值同样巨大。"),
            ApocalypseWorldPlaceV5("pumped_storage", "青岳抽水蓄能站", "能源设施", "山体内部电站可在区域电网重建时提供关键调峰能力。"),
            ApocalypseWorldPlaceV5("forestry", "北岭林业基地", "生产基地", "木材、车辆、维修厂与林区道路体系完整。"),
            ApocalypseWorldPlaceV5("observatory", "白榆气象观测站", "科研设施", "高海拔雷达和气象设备适合长期追踪赤潮天气。"),
            ApocalypseWorldPlaceV5("border_depot", "北部应急储备库", "战略仓储", "灾前用于极端天气救灾，拥有保温物资、燃料和工程设备。"),
        ),
    ),
)

private fun apocalypseWorldRoadsV5(): List<ApocalypseWorldRoadV5> = listOf(
    ApocalypseWorldRoadV5("linjiang", "xinchuan", "北环高速"),
    ApocalypseWorldRoadV5("linjiang", "hailing", "临海高速"),
    ApocalypseWorldRoadV5("linjiang", "lanshan", "西岭高速"),
    ApocalypseWorldRoadV5("linjiang", "yunqi", "南环国道"),
    ApocalypseWorldRoadV5("xinchuan", "baiyu", "北部干线"),
    ApocalypseWorldRoadV5("xinchuan", "hailing", "东部货运线"),
    ApocalypseWorldRoadV5("yunqi", "hailing", "南部沿江线"),
)

internal fun apocalypseWorldGeographyPromptV5(): String = buildString {
    append("区域地理硬设定：主要舞台为虚构的‘东澜地区’，包含6个地级市。")
    append("临江市位于中部临江平原，是开局城市和人口中心；")
    append("新川市在北部，是铁路、粮储、重工和通信枢纽；")
    append("海陵市在东海岸，是深水港、冷链和船舶中心；")
    append("岚山市在西北山地，控制水库、山口和风电；")
    append("云栖市在西南湖区，是农业、种质和大学城中心；")
    append("白榆市在东北高地，是林业、燃气与抽蓄能源城市。")
    append("跨市移动以100—320公里为尺度，必须考虑道路、燃料、天气、尸潮、桥隧状态和途中住宿，不能一幕瞬移。")
}

private fun cityForLocationV5(currentLocation: String, cities: List<ApocalypseWorldCityV5>): ApocalypseWorldCityV5 =
    cities.firstOrNull { currentLocation.contains(it.name) }
        ?: cities.firstOrNull { city -> city.places.any { currentLocation.contains(it.name) } }
        ?: cities.first()

@Composable
internal fun ApocalypseWorldAtlasSummaryV5() {
    val cities = remember { apocalypseWorldCitiesV5() }
    Surface(
        color = MapNight,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("东澜地区 · 6市区域设定", color = MapBlue, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text("临江不是世界本身，只是故事的起点。六座城市通过高速、铁路、沿江线和山地通道组成一个需要数小时到数日穿越的现实尺度区域。", color = Color(0xFFB7CCDF), fontSize = 11.sp, lineHeight = 17.sp)
            cities.forEach { city ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("•", color = MapBlue, modifier = Modifier.width(14.dp))
                    Column {
                        Text("${city.name} · ${city.direction}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(city.travelFromLinjiang, color = Color(0xFF8FAAC1), fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ApocalypseWorldMapSheetV5(
    currentLocation: String,
    discoveredLocations: List<ApocalypseV3Location>,
    onChoose: (ApocalypseV3Location) -> Unit,
) {
    val cities = remember { apocalypseWorldCitiesV5() }
    val roads = remember { apocalypseWorldRoadsV5() }
    val currentCity = cityForLocationV5(currentLocation, cities)
    var selectedCityId by remember(currentLocation) { mutableStateOf(currentCity.id) }
    val selectedCity = cities.first { it.id == selectedCityId }
    val cityById = cities.associateBy { it.id }

    Column(
        Modifier.fillMaxWidth().fillMaxHeight(.90f).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("东澜地区地图", color = MapInk, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("6市区域总览 · 当前：$currentLocation", color = MapMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(color = Color(0xFFE4F2FF), shape = RoundedCornerShape(10.dp)) {
                Text("现实尺度", color = Color(0xFF287EBE), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            color = MapNight,
            shape = RoundedCornerShape(22.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().background(MapNight)) {
                Canvas(Modifier.matchParentSize()) {
                    roads.forEach { road ->
                        val from = cityById[road.from] ?: return@forEach
                        val to = cityById[road.to] ?: return@forEach
                        drawLine(
                            color = MapLine,
                            start = androidx.compose.ui.geometry.Offset(size.width * from.x, size.height * from.y),
                            end = androidx.compose.ui.geometry.Offset(size.width * to.x, size.height * to.y),
                            strokeWidth = 3f,
                        )
                    }
                }
                cities.forEach { city ->
                    val selected = city.id == selectedCityId
                    val current = city.id == currentCity.id
                    Surface(
                        onClick = { selectedCityId = city.id },
                        modifier = Modifier
                            .offset(x = maxWidth * city.x - 36.dp, y = maxHeight * city.y - 22.dp)
                            .width(78.dp),
                        color = when {
                            current -> MapBlue
                            selected -> Color(0xFF173D5C)
                            else -> MapNightSoft
                        },
                        contentColor = Color.White,
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, if (selected || current) MapBlueSoft else MapLine),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                if (current) Icons.Outlined.MyLocation else Icons.Outlined.LocationCity,
                                null,
                                modifier = Modifier.size(15.dp),
                                tint = if (current) MapNight else MapBlueSoft,
                            )
                            Text(city.name, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        }
                    }
                }
                Text("北 ↑", color = Color(0xFF7894AC), fontSize = 9.sp, modifier = Modifier.align(Alignment.TopEnd).padding(11.dp))
                Text("蓝线表示主要跨市交通走廊，不代表灾后仍畅通", color = Color(0xFF7894AC), fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            }
        }

        Surface(color = MapCard, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, MapBorder)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedCity.name, color = MapInk, fontSize = 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    if (selectedCity.id == currentCity.id) {
                        Text("当前城市", color = Color(0xFF287EBE), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(selectedCity.direction + " · " + selectedCity.travelFromLinjiang, color = Color(0xFF287EBE), fontSize = 10.sp)
                Text(selectedCity.summary, color = MapMuted, fontSize = 11.sp, lineHeight = 16.sp)
                Text("主要风险：${selectedCity.hazard}", color = MapMuted, fontSize = 10.sp, lineHeight = 15.sp)
                if (selectedCity.id != currentCity.id) {
                    OutlinedButton(
                        onClick = {
                            onChoose(
                                ApocalypseV3Location(
                                    id = "geo_${selectedCity.id}_approach",
                                    name = "${selectedCity.name} · 城市外围",
                                    detail = "跨市行动目标。${selectedCity.travelFromLinjiang}；进入前需要侦查道路、燃料、天气、桥隧和沿途落脚点。",
                                    unlocked = true,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Route, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("规划前往${selectedCity.name}")
                    }
                }
            }
        }

        Text("${selectedCity.name} · 关键地点", color = MapInk, fontSize = 14.sp, fontWeight = FontWeight.Black)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(selectedCity.places, key = { it.id }) { place ->
                val discovered = discoveredLocations.any { known -> known.name.contains(place.name) || place.name.contains(known.name) }
                Surface(
                    onClick = {
                        onChoose(
                            ApocalypseV3Location(
                                id = "geo_${selectedCity.id}_${place.id}",
                                name = "${selectedCity.name} · ${place.name}",
                                detail = "${place.kind}｜${place.detail}",
                                unlocked = true,
                            ),
                        )
                    },
                    color = MapCard,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, if (discovered) Color(0xFF8CCAFF) else MapBorder),
                ) {
                    Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = if (discovered) Color(0xFFE4F2FF) else MapSurface, shape = RoundedCornerShape(10.dp)) {
                            Icon(
                                when (place.kind) {
                                    "医疗区", "医疗/避难" -> Icons.Outlined.LocalHospital
                                    "交通枢纽", "交通设施", "港口" -> Icons.Outlined.Route
                                    "基础设施", "能源设施", "水源" -> Icons.Outlined.Bolt
                                    "科研设施", "农业科研" -> Icons.Outlined.Science
                                    "战略仓储", "物流区", "食品工业" -> Icons.Outlined.Inventory2
                                    else -> Icons.Outlined.Place
                                },
                                null,
                                tint = Color(0xFF287EBE),
                                modifier = Modifier.padding(8.dp).size(19.dp),
                            )
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(place.name, color = MapInk, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (discovered) Text("已发现", color = Color(0xFF287EBE), fontSize = 8.sp)
                            }
                            Text(place.kind, color = Color(0xFF287EBE), fontSize = 9.sp)
                            Text(place.detail, color = MapMuted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = MapMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Text("地图表示灾前地理与已知基础设施；灾后道路是否还能走，必须通过剧情侦查确认。", color = MapMuted, fontSize = 9.sp)
        Spacer(Modifier.navigationBarsPadding())
    }
}
