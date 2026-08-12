package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class ApocalypseMapIntelV5(
    val assetId: String,
    val cityId: String,
    val parentPlaceName: String,
    val sourceTitle: String,
    val sourceDetail: String,
    val location: ApocalypseV3Location,
    val nodes: List<String>,
)

private data class ApocalypseFloorRoomV5(
    val label: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val primary: Boolean = false,
)

private data class ApocalypseFloorPlanV5(
    val levelLabel: String,
    val rooms: List<ApocalypseFloorRoomV5>,
    val corridorLabels: List<String>,
    val routeSteps: List<String>,
    val routePoints: List<Pair<Float, Float>>,
    val hasExplicitDirections: Boolean,
)

internal fun apocalypseMapIntelForCityV5(
    save: ApocalypseV3Save,
    city: ApocalypseWorldCityV5,
    currentCity: ApocalypseWorldCityV5,
): List<ApocalypseMapIntelV5> {
    return save.director.assets
        .filter { it.kind == ApocalypseV3AssetKind.Map }
        .mapNotNull { asset ->
            val text = "${asset.title} ${asset.detail} ${asset.tag}"
            val parent = bestMapParentPlaceV5(text, city)
            val explicitlyOtherCity = apocalypseWorldCitiesV5().any { other ->
                other.id != city.id && text.contains(other.name)
            }
            val belongs = text.contains(city.name) || parent != null || (!explicitlyOtherCity && city.id == currentCity.id)
            if (!belongs) return@mapNotNull null
            val parentName = parent?.name.orEmpty()
            val displayName = when {
                parentName.isNotBlank() && asset.title.contains(parentName) -> asset.title
                parentName.isNotBlank() -> "$parentName · ${asset.title}"
                else -> asset.title.ifBlank { "剧情地图资料" }
            }
            ApocalypseMapIntelV5(
                assetId = asset.id,
                cityId = city.id,
                parentPlaceName = parentName,
                sourceTitle = asset.title.ifBlank { "地图资料" },
                sourceDetail = asset.detail,
                location = ApocalypseV3Location(
                    id = "map_asset_${asset.id}",
                    name = "${city.name} · $displayName",
                    detail = buildString {
                        if (parentName.isNotBlank()) append("所属地点：$parentName｜")
                        append(asset.detail.ifBlank { "已取得地图资料，但暂时没有更多可确认细节。" })
                    },
                    unlocked = true,
                ),
                nodes = extractMapNodesV5(displayName, asset.detail, parentName),
            )
        }
        .distinctBy { it.assetId }
}

internal fun apocalypseBestMapIntelForLocationV5(
    save: ApocalypseV3Save,
    city: ApocalypseWorldCityV5,
    currentCity: ApocalypseWorldCityV5,
    location: ApocalypseV3Location,
): ApocalypseMapIntelV5? {
    val locationText = "${location.name} ${location.detail}"
    return apocalypseMapIntelForCityV5(save, city, currentCity)
        .maxByOrNull { intel ->
            mapTextOverlapScoreV5(locationText, "${intel.location.name} ${intel.sourceTitle} ${intel.sourceDetail} ${intel.parentPlaceName}")
        }
        ?.takeIf { intel ->
            mapTextOverlapScoreV5(locationText, "${intel.location.name} ${intel.sourceTitle} ${intel.sourceDetail} ${intel.parentPlaceName}") > 0
        }
}

@Composable
internal fun ApocalypseLocationDetailSheetV5(
    location: ApocalypseV3Location,
    city: ApocalypseWorldCityV5,
    intel: ApocalypseMapIntelV5?,
    onDismiss: () -> Unit,
    onPlan: (ApocalypseV3Location) -> Unit,
) {
    val mapTitle = intel?.sourceTitle ?: location.name
    val mapDetail = intel?.sourceDetail?.ifBlank { location.detail } ?: location.detail
    val mapNodes = intel?.nodes ?: extractMapNodesV5(location.name, location.detail, "")
    val plan = buildApocalypseFloorPlanV5(mapTitle, mapDetail, mapNodes, intel?.parentPlaceName.orEmpty())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFF3F8FD),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(.92f)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFD8ECFF), shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Outlined.Map, null, tint = Color(0xFF287EBE), modifier = Modifier.padding(10.dp).size(23.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(location.name, color = Color(0xFF0A1726), fontSize = 21.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${city.name} · 具体区域地图", color = Color(0xFF607287), fontSize = 11.sp)
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ApocalypseKnownFloorPlanV5(title = mapTitle, plan = plan)

                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFFD3E3F2)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("已知路线", color = Color(0xFF0A1726), fontWeight = FontWeight.Black, fontSize = 15.sp)
                        if (plan.routeSteps.isEmpty()) {
                            Text(
                                "这份资料确认了区域和通路，但没有写清每一个转向。图上会画出房间、主走廊和已知连接；没有方向依据的相对位置会标成示意，后续取得更完整平面图后自动更新。",
                                color = Color(0xFF607287),
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                            )
                        } else {
                            plan.routeSteps.forEachIndexed { index, step ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Surface(color = Color(0xFFD8ECFF), shape = RoundedCornerShape(8.dp)) {
                                        Text("${index + 1}", color = Color(0xFF287EBE), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(step, color = Color(0xFF42566C), fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFFD3E3F2)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("已知资料", color = Color(0xFF0A1726), fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text(location.detail.ifBlank { "目前只确认了这个地点的位置，还没有更多内部资料。" }, color = Color(0xFF607287), fontSize = 11.sp, lineHeight = 17.sp)
                        intel?.let {
                            if (it.sourceDetail.isNotBlank() && !location.detail.contains(it.sourceDetail)) {
                                Text("地图来源：${it.sourceTitle}", color = Color(0xFF287EBE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(it.sourceDetail, color = Color(0xFF607287), fontSize = 11.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                }

                Surface(
                    color = Color(0xFFEAF4FF),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF93CCFF)),
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.MyLocation, null, tint = Color(0xFF287EBE), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (plan.hasExplicitDirections) {
                                "蓝色路线按已经取得的方向描述绘制；房间和走廊只使用已知资料，不会提前画出未知区域。"
                            } else {
                                "房间和通路已经按平面图方式展开；资料没说明具体方位的部分只做结构示意，不会把示意位置当成剧情正史。"
                            },
                            color = Color(0xFF607287),
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Button(
                onClick = { onPlan(location) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2387E8)),
            ) {
                Icon(Icons.Outlined.Route, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("把这里写入下一步行动")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ApocalypseKnownFloorPlanV5(title: String, plan: ApocalypseFloorPlanV5) {
    val wall = Color(0xFF4E6B82)
    val roomFill = Color(0xFF10263A)
    val roomPrimary = Color(0xFF163D5C)
    val hallFloor = Color(0xFF152C40)
    val route = Color(0xFF55B7FF)
    val unknown = Color(0xFF60788C)

    Surface(
        modifier = Modifier.fillMaxWidth().height(390.dp),
        color = Color(0xFF07111F),
        shape = RoundedCornerShape(22.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF07111F))) {
            Canvas(Modifier.fillMaxSize()) {
                val hallWidth = size.minDimension * .085f
                val mainStart = Offset(size.width * .08f, size.height * .52f)
                val mainEnd = Offset(size.width * .92f, size.height * .52f)

                // Main corridor: thick wall outline plus a lighter walkable floor, so this reads as a
                // real passage instead of a graph edge.
                drawLine(wall, mainStart, mainEnd, strokeWidth = hallWidth + 8f, cap = StrokeCap.Square)
                drawLine(hallFloor, mainStart, mainEnd, strokeWidth = hallWidth, cap = StrokeCap.Square)

                plan.rooms.forEach { room ->
                    val left = size.width * room.x
                    val top = size.height * room.y
                    val width = size.width * room.width
                    val height = size.height * room.height
                    val centerX = left + width / 2f
                    val roomTouchesTop = room.y < .5f
                    val roomDoorY = if (roomTouchesTop) top + height else top
                    val hallY = size.height * .52f

                    // Branch corridor from the main passage to each confirmed room.
                    drawLine(
                        wall,
                        Offset(centerX, hallY),
                        Offset(centerX, roomDoorY),
                        strokeWidth = hallWidth * .62f + 7f,
                        cap = StrokeCap.Square,
                    )
                    drawLine(
                        hallFloor,
                        Offset(centerX, hallY),
                        Offset(centerX, roomDoorY),
                        strokeWidth = hallWidth * .62f,
                        cap = StrokeCap.Square,
                    )

                    drawRect(if (room.primary) roomPrimary else roomFill, topLeft = Offset(left, top), size = Size(width, height))
                    drawRect(wall, topLeft = Offset(left, top), size = Size(width, height), style = Stroke(width = 3f))

                    // A short bright threshold marks the actual doorway into the room.
                    drawLine(
                        Color(0xFF93CCFF),
                        Offset(centerX - width * .12f, roomDoorY),
                        Offset(centerX + width * .12f, roomDoorY),
                        strokeWidth = 5f,
                    )
                }

                if (plan.routePoints.size >= 2) {
                    val routePath = Path().apply {
                        val first = plan.routePoints.first()
                        moveTo(size.width * first.first, size.height * first.second)
                        plan.routePoints.drop(1).forEach { point -> lineTo(size.width * point.first, size.height * point.second) }
                    }
                    drawPath(routePath, route.copy(alpha = .28f), style = Stroke(width = 12f, cap = StrokeCap.Round))
                    drawPath(routePath, route, style = Stroke(width = 4f, cap = StrokeCap.Round))
                    plan.routePoints.forEach { point ->
                        drawCircle(route, radius = 5f, center = Offset(size.width * point.first, size.height * point.second))
                    }
                } else {
                    // No explicit direction wording: keep the unexplored ends visibly uncertain.
                    drawLine(
                        unknown,
                        Offset(size.width * .03f, size.height * .52f),
                        mainStart,
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 8f)),
                    )
                    drawLine(
                        unknown,
                        mainEnd,
                        Offset(size.width * .97f, size.height * .52f),
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 8f)),
                    )
                }
            }

            plan.rooms.forEach { room ->
                Column(
                    modifier = Modifier
                        .offset(x = maxWidth * room.x, y = maxHeight * room.y)
                        .width(maxWidth * room.width)
                        .height(maxHeight * room.height)
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        room.label,
                        color = Color.White,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (plan.corridorLabels.isNotEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF0B1D2D).copy(alpha = .92f),
                    shape = RoundedCornerShape(7.dp),
                ) {
                    Text(
                        plan.corridorLabels.joinToString(" / ").take(28),
                        color = Color(0xFFA9C8E1),
                        fontSize = 8.sp,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }

            Text("平面图 · $title", color = Color(0xFFC1D5E5), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(11.dp))
            if (plan.levelLabel.isNotBlank()) {
                Surface(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp), color = Color(0xFF173D5C), shape = RoundedCornerShape(8.dp)) {
                    Text(plan.levelLabel, color = Color(0xFF93CCFF), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Text(
                if (plan.hasExplicitDirections) "蓝线＝资料中可确认的行进方向" else "虚线＝尚未确认的延伸方向",
                color = Color(0xFF7894AC),
                fontSize = 8.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
            Text("北 ↑", color = Color(0xFF7894AC), fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
        }
    }
}

private fun buildApocalypseFloorPlanV5(
    title: String,
    detail: String,
    nodes: List<String>,
    parentPlace: String,
): ApocalypseFloorPlanV5 {
    val cleanNodes = nodes.filter(String::isNotBlank).distinct().take(10)
    val levelLabel = Regex("(?:B\\d{1,2}|负\\d{1,2}层|地下\\d{1,2}层|\\d{1,2}层)", RegexOption.IGNORE_CASE)
        .find("$title $detail")
        ?.value
        ?.uppercase()
        .orEmpty()

    val corridorWords = listOf("走廊", "过道", "通道", "换乘通道", "地下通道", "隧道", "连廊")
    val corridorLabels = cleanNodes.filter { node -> corridorWords.any(node::contains) }.take(3)
    val nonRoom = cleanNodes.filter { node ->
        node.equals(levelLabel, ignoreCase = true) || corridorWords.any(node::contains)
    }.toSet()
    val candidates = cleanNodes.filterNot { it in nonRoom }.toMutableList()
    if (candidates.size > 1 && parentPlace.isNotBlank()) candidates.remove(parentPlace)
    if (candidates.isEmpty()) candidates += title.take(18).ifBlank { "已知区域" }

    val slots = listOf(
        ApocalypseFloorRoomV5("", .10f, .13f, .19f, .20f, true),
        ApocalypseFloorRoomV5("", .35f, .10f, .20f, .21f),
        ApocalypseFloorRoomV5("", .62f, .13f, .20f, .20f),
        ApocalypseFloorRoomV5("", .73f, .65f, .19f, .19f),
        ApocalypseFloorRoomV5("", .46f, .69f, .20f, .19f),
        ApocalypseFloorRoomV5("", .18f, .67f, .20f, .20f),
        ApocalypseFloorRoomV5("", .77f, .35f, .16f, .15f),
        ApocalypseFloorRoomV5("", .05f, .39f, .16f, .15f),
    )
    val rooms = candidates.take(slots.size).mapIndexed { index, label -> slots[index].copy(label = label) }
    val routeSteps = extractDirectionalRouteV5(detail)
    return ApocalypseFloorPlanV5(
        levelLabel = levelLabel,
        rooms = rooms,
        corridorLabels = corridorLabels.ifEmpty { listOf("主走廊") },
        routeSteps = routeSteps,
        routePoints = if (routeSteps.isEmpty()) emptyList() else buildDirectionalPolylineV5(detail),
        hasExplicitDirections = routeSteps.isNotEmpty(),
    )
}

private fun extractDirectionalRouteV5(detail: String): List<String> {
    if (detail.isBlank()) return emptyList()
    val directionRegex = Regex("直走|向前|左转|左拐|右转|右拐|掉头|尽头|上楼|下楼|上行|下行|进入|穿过|经过|出口|入口")
    return detail
        .split(Regex("[。；;！!\\n]+"))
        .map(String::trim)
        .filter { clause -> clause.length in 2..120 && directionRegex.containsMatchIn(clause) }
        .distinct()
        .take(6)
}

private fun buildDirectionalPolylineV5(detail: String): List<Pair<Float, Float>> {
    val tokens = Regex("直走|向前|左转|左拐|右转|右拐|掉头")
        .findAll(detail)
        .map { it.value }
        .take(8)
        .toList()
    if (tokens.isEmpty()) return emptyList()
    var x = .10f
    var y = .52f
    var direction = 0 // 0 east, 1 south, 2 west, 3 north
    val points = mutableListOf(x to y)
    tokens.forEach { token ->
        direction = when {
            token.contains("右") -> (direction + 1) % 4
            token.contains("左") -> (direction + 3) % 4
            token == "掉头" -> (direction + 2) % 4
            else -> direction
        }
        val step = .15f
        when (direction) {
            0 -> x += step
            1 -> y += step
            2 -> x -= step
            else -> y -= step
        }
        x = x.coerceIn(.08f, .92f)
        y = y.coerceIn(.18f, .82f)
        points += x to y
    }
    return points.distinct()
}

private fun bestMapParentPlaceV5(text: String, city: ApocalypseWorldCityV5): ApocalypseWorldPlaceV5? {
    return city.places
        .map { place -> place to mapNameStemsV5(place.name).count { stem -> stem.length >= 2 && text.contains(stem, ignoreCase = true) } }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}

private fun mapNameStemsV5(name: String): List<String> {
    val suffixes = listOf("换乘站", "综合市场", "物流园", "产业园", "工业园", "种源站", "水厂", "医院", "公寓", "基地", "中心", "站")
    return buildList {
        add(name)
        suffixes.forEach { suffix ->
            if (name.endsWith(suffix)) name.removeSuffix(suffix).takeIf { it.length >= 2 }?.let(::add)
        }
        name.split('·', ' ', '（', '(', '/', '｜').map(String::trim).filter { it.length >= 2 }.forEach(::add)
    }.distinct()
}

private fun extractMapNodesV5(title: String, detail: String, parentPlace: String): List<String> {
    val text = "$title $detail"
    val nodes = linkedSetOf<String>()
    parentPlace.takeIf(String::isNotBlank)?.let(nodes::add)
    Regex("B\\d{1,2}", RegexOption.IGNORE_CASE).findAll(text).map { it.value.uppercase() }.forEach(nodes::add)
    val knownLabels = listOf(
        "备用控制区", "控制区", "防灾层", "站厅", "站台", "换乘通道", "主走廊", "走廊", "过道", "设备间", "机房", "柴油机房",
        "药剂库", "取水口", "仓库", "冷库", "入口", "出口", "闸门", "隧道", "地下通道", "避难区", "楼梯间", "电梯厅",
        "实验室", "温室", "种子库", "配电室", "值班室", "停车区", "装卸区", "办公室", "储藏室", "检修间", "泵房",
    )
    knownLabels.filter { text.contains(it) }.forEach(nodes::add)
    title.takeIf { it.isNotBlank() && nodes.none { node -> title.contains(node) } }?.let { nodes.add(it.take(18)) }
    return nodes.take(10)
}

private fun mapTextOverlapScoreV5(left: String, right: String): Int {
    val tokens = Regex("[\\p{L}\\p{N}]{2,}").findAll(left).map { it.value }.toSet()
    var score = 0
    tokens.forEach { token -> if (right.contains(token, ignoreCase = true)) score += 2 }
    Regex("B\\d{1,2}", RegexOption.IGNORE_CASE).findAll(left).forEach { match ->
        if (right.contains(match.value, ignoreCase = true)) score += 5
    }
    return score
}
