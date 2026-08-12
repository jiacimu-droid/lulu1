package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFF3F8FD),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(.88f)
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
                    Text("${city.name} · 地点详情", color = Color(0xFF607287), fontSize = 11.sp)
                }
            }

            ApocalypseKnownSubareaMapV5(
                title = intel?.sourceTitle ?: location.name,
                nodes = intel?.nodes ?: extractMapNodesV5(location.name, location.detail, ""),
            )

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
                    Text("只显示你已经取得的地图、地点和剧情资料；没有确认过的房间、通道和出口不会凭空补出来。", color = Color(0xFF607287), fontSize = 10.sp, lineHeight = 15.sp)
                }
            }

            Spacer(Modifier.weight(1f))
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
private fun ApocalypseKnownSubareaMapV5(title: String, nodes: List<String>) {
    val safeNodes = nodes.filter(String::isNotBlank).distinct().take(6).ifEmpty { listOf(title.take(16).ifBlank { "已知位置" }) }
    val positions = listOf(
        .16f to .24f,
        .53f to .18f,
        .82f to .34f,
        .72f to .70f,
        .38f to .78f,
        .15f to .61f,
    )
    Surface(
        modifier = Modifier.fillMaxWidth().height(300.dp),
        color = Color(0xFF07111F),
        shape = RoundedCornerShape(22.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF07111F))) {
            Canvas(Modifier.fillMaxSize()) {
                val points = safeNodes.indices.map { index ->
                    val p = positions[index % positions.size]
                    Offset(size.width * p.first, size.height * p.second)
                }
                if (points.size > 1) {
                    points.zipWithNext().forEach { (from, to) ->
                        val path = Path().apply {
                            moveTo(from.x, from.y)
                            quadraticBezierTo((from.x + to.x) / 2f, (from.y + to.y) / 2f - 18f, to.x, to.y)
                        }
                        drawPath(path, color = Color(0xFF31536F), style = Stroke(width = 4f))
                    }
                }
                points.forEach { point ->
                    drawCircle(Color(0xFF4EA8FF), radius = 6f, center = point)
                }
            }
            safeNodes.forEachIndexed { index, node ->
                val p = positions[index % positions.size]
                Surface(
                    modifier = Modifier.offset(x = maxWidth * p.first - 43.dp, y = maxHeight * p.second - 22.dp).width(88.dp),
                    color = Color(0xFF0D2033),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, Color(0xFF31536F)),
                ) {
                    Text(node, color = Color.White, fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp))
                }
            }
            Text("已知内部图层 · $title", color = Color(0xFF9CB5CA), fontSize = 9.sp, modifier = Modifier.align(Alignment.TopStart).padding(11.dp))
            Text("连线只表示已知区域之间存在关联，不擅自补全未知结构", color = Color(0xFF7894AC), fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
    }
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
        "备用控制区", "控制区", "防灾层", "站厅", "站台", "换乘通道", "设备间", "机房", "柴油机房",
        "药剂库", "取水口", "仓库", "冷库", "入口", "出口", "闸门", "隧道", "地下通道", "避难区",
        "实验室", "温室", "种子库", "配电室", "值班室", "停车区", "装卸区",
    )
    knownLabels.filter { text.contains(it) }.forEach(nodes::add)
    title.takeIf { it.isNotBlank() && nodes.none { node -> title.contains(node) } }?.let { nodes.add(it.take(18)) }
    return nodes.take(6)
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
