package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val WorldMapBg = Color(0xFFF3F8FD)
private val WorldMapNight = Color(0xFF07111F)
private val WorldMapNightSoft = Color(0xFF0D2033)
private val WorldMapLine = Color(0xFF31536F)
private val WorldMapLineBright = Color(0xFF4EA8FF)
private val WorldMapBlueSoft = Color(0xFF93CCFF)
private val WorldMapInk = Color(0xFF0A1726)
private val WorldMapMuted = Color(0xFF607287)
private val WorldMapBorder = Color(0xFFD3E3F2)
private val WorldMapWhite = Color.White

private enum class ApocalypseMapLayerV5 { Region, City }

private data class WorldMapRoadV5(val from: String, val to: String, val name: String)

private val worldMapRoadsV5 = listOf(
    WorldMapRoadV5("lanshan", "linjiang", "西岭高速"),
    WorldMapRoadV5("xinchuan", "linjiang", "北环高速"),
    WorldMapRoadV5("xinchuan", "baiyu", "北部干线"),
    WorldMapRoadV5("linjiang", "hailing", "临海高速"),
    WorldMapRoadV5("linjiang", "yunqi", "南环国道"),
    WorldMapRoadV5("yunqi", "hailing", "湖海联络线"),
)

@Composable
internal fun ApocalypseWorldMapPageV5(
    save: ApocalypseV3Save,
    currentLocation: String,
    discoveredLocations: List<ApocalypseV3Location>,
    onBack: () -> Unit,
    onPlan: (ApocalypseV3Location) -> Unit,
) {
    val cities = remember { apocalypseWorldCitiesV5() }
    val currentCity = remember(currentLocation, cities) { resolveWorldMapCityV5(currentLocation, cities) }
    var selectedCityId by remember(currentLocation) { mutableStateOf(currentCity.id) }
    var layer by remember { mutableStateOf(ApocalypseMapLayerV5.Region) }
    val selectedCity = cities.firstOrNull { it.id == selectedCityId } ?: currentCity
    val evolution = remember(save.scene, save.director.dayIndex, save.director.worldFacts) { apocalypseMapEvolutionV5(save) }

    Scaffold(
        containerColor = WorldMapBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("东澜地图", fontWeight = FontWeight.Black)
                        Text("${apocalypseDayLabelV5(evolution.dayIndex)} · ${evolution.eraTitle} · 当前：$currentLocation", color = WorldMapMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WorldMapBg),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = layer == ApocalypseMapLayerV5.Region,
                        onClick = { layer = ApocalypseMapLayerV5.Region },
                        label = { Text("区域总图") },
                        leadingIcon = { Icon(Icons.Outlined.Map, null, Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFD8ECFF)),
                    )
                    FilterChip(
                        selected = layer == ApocalypseMapLayerV5.City,
                        onClick = { layer = ApocalypseMapLayerV5.City },
                        label = { Text("${selectedCity.name}市内图") },
                        leadingIcon = { Icon(Icons.Outlined.LocationCity, null, Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFD8ECFF)),
                    )
                }
            }

            item { ApocalypseMapEraBannerV5(evolution) }

            if (layer == ApocalypseMapLayerV5.Region) {
                item {
                    ApocalypseRegionMapCanvasV5(
                        cities = cities,
                        selectedCityId = selectedCity.id,
                        currentCityId = currentCity.id,
                        evolution = evolution,
                        onSelect = { selectedCityId = it },
                    )
                }
                item {
                    ApocalypseRegionCityCardV5(
                        city = selectedCity,
                        isCurrent = selectedCity.id == currentCity.id,
                        onOpenCity = { layer = ApocalypseMapLayerV5.City },
                        onPlan = { onPlan(cityApproachTargetV5(selectedCity)) },
                    )
                }
                item {
                    Text("六市关键功能", color = WorldMapInk, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
                items(cities, key = { it.id }) { city ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedCityId = city.id
                            layer = ApocalypseMapLayerV5.City
                        },
                        color = WorldMapWhite,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (city.id == currentCity.id) WorldMapBlueSoft else WorldMapBorder),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color(0xFFE4F2FF), shape = RoundedCornerShape(10.dp)) {
                                Icon(Icons.Outlined.LocationCity, null, tint = Color(0xFF287EBE), modifier = Modifier.padding(8.dp).size(19.dp))
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(city.name, color = WorldMapInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    if (city.id == currentCity.id) Text("当前", color = Color(0xFF287EBE), fontSize = 9.sp)
                                }
                                Text(city.direction, color = Color(0xFF287EBE), fontSize = 9.sp)
                                Text(city.summary, color = WorldMapMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Outlined.ChevronRight, null, tint = WorldMapMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            } else {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(cities, key = { it.id }) { city ->
                            FilterChip(
                                selected = city.id == selectedCity.id,
                                onClick = { selectedCityId = city.id },
                                label = { Text(city.name, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFD8ECFF)),
                            )
                        }
                    }
                }
                item {
                    ApocalypseCityInternalMapV5(
                        city = selectedCity,
                        currentLocation = currentLocation,
                        discoveredLocations = discoveredLocations,
                        evolution = evolution,
                        onSelectPlace = { place -> onPlan(placeTargetV5(selectedCity, place)) },
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${selectedCity.name} · 重要地点", color = WorldMapInk, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        Text("点击地点可以直接写入下一步行动；地图是剧情地理图，不代表灾后道路仍可通行。", color = WorldMapMuted, fontSize = 10.sp)
                    }
                }
                items(selectedCity.places, key = { it.id }) { place ->
                    ApocalypseWorldPlaceRowV5(
                        city = selectedCity,
                        place = place,
                        discovered = discoveredLocations.any { it.name.contains(place.name) || place.name.contains(it.name) },
                        current = currentLocation.contains(place.name),
                        status = apocalypsePlaceMapStatusV5(
                            evolution = evolution,
                            city = selectedCity,
                            place = place,
                            currentLocation = currentLocation,
                            discovered = discoveredLocations.any { it.name.contains(place.name) || place.name.contains(it.name) },
                        ),
                        onClick = { onPlan(placeTargetV5(selectedCity, place)) },
                    )
                }
                val dynamic = dynamicLocationsForCityV5(selectedCity, currentCity, discoveredLocations)
                if (dynamic.isNotEmpty()) {
                    item {
                        Text("剧情中新发现", color = WorldMapInk, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    }
                    items(dynamic, key = { it.id }) { location ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onPlan(location) },
                            color = Color(0xFFEAF4FF),
                            shape = RoundedCornerShape(15.dp),
                            border = BorderStroke(1.dp, WorldMapBlueSoft),
                        ) {
                            Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.MyLocation, null, tint = Color(0xFF287EBE), modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(location.name, color = WorldMapInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(location.detail, color = WorldMapMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Outlined.ChevronRight, null, tint = WorldMapMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                item {
                    if (selectedCity.id != currentCity.id) {
                        Button(
                            onClick = { onPlan(cityApproachTargetV5(selectedCity)) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2387E8)),
                        ) {
                            Icon(Icons.Outlined.Route, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("规划前往${selectedCity.name}")
                        }
                    }
                }
            }

            item {
                Text(
                    "地图只展示灾前硬地理、已知基础设施与剧情中已经发现的地点。桥梁、道路、水电和势力控制会随时间变化，必须在剧情中重新侦查。",
                    color = WorldMapMuted,
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                )
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun ApocalypseRegionMapCanvasV5(
    cities: List<ApocalypseWorldCityV5>,
    selectedCityId: String,
    currentCityId: String,
    evolution: ApocalypseMapEvolutionV5,
    onSelect: (String) -> Unit,
) {
    val byId = cities.associateBy { it.id }
    Surface(
        modifier = Modifier.fillMaxWidth().height(390.dp),
        color = WorldMapNight,
        shape = RoundedCornerShape(22.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(WorldMapNight)) {
            Canvas(Modifier.matchParentSize()) {
                if (evolution.ecologyPressure > 0f) {
                    val eco = evolution.ecologyPressure.coerceIn(0f, 1f)
                    drawCircle(Color(0xFF315D4A).copy(alpha = .08f + eco * .16f), size.minDimension * (.12f + eco * .16f), Offset(size.width * .17f, size.height * .68f))
                    drawCircle(Color(0xFF6F3141).copy(alpha = .05f + eco * .13f), size.minDimension * (.10f + eco * .15f), Offset(size.width * .73f, size.height * .38f))
                    drawCircle(Color(0xFF315D4A).copy(alpha = .04f + eco * .12f), size.minDimension * (.08f + eco * .13f), Offset(size.width * .47f, size.height * .22f))
                }
                worldMapRoadsV5.forEachIndexed { index, road ->
                    val from = byId[road.from] ?: return@forEachIndexed
                    val to = byId[road.to] ?: return@forEachIndexed
                    val start = Offset(size.width * from.x, size.height * from.y)
                    val end = Offset(size.width * to.x, size.height * to.y)
                    val bend = if (index % 2 == 0) 28f else -28f
                    val mid = Offset((start.x + end.x) / 2f + bend, (start.y + end.y) / 2f - bend * .35f)
                    val path = Path().apply {
                        moveTo(start.x, start.y)
                        quadraticBezierTo(mid.x, mid.y, end.x, end.y)
                    }
                    val routeStatus = apocalypseRouteMapStatusV5(evolution, road.name)
                    val routeColor = mapConditionColorV5(routeStatus.condition)
                    val routeEffect = when (routeStatus.condition) {
                        ApocalypseMapConditionV5.Blocked -> PathEffect.dashPathEffect(floatArrayOf(8f, 13f))
                        ApocalypseMapConditionV5.Unknown -> PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
                        else -> null
                    }
                    drawPath(path, color = routeColor.copy(alpha = if (evolution.dayIndex < 0) .85f else .68f), style = Stroke(width = 4f, pathEffect = routeEffect))
                }

                val river = Path().apply {
                    moveTo(size.width * .08f, size.height * .34f)
                    cubicTo(size.width * .25f, size.height * .40f, size.width * .30f, size.height * .58f, size.width * .48f, size.height * .54f)
                    cubicTo(size.width * .61f, size.height * .51f, size.width * .68f, size.height * .45f, size.width * .93f, size.height * .56f)
                }
                drawPath(river, color = Color(0xFF1C5279), style = Stroke(width = 7f))
            }

            cities.forEach { city ->
                val selected = city.id == selectedCityId
                val current = city.id == currentCityId
                Surface(
                    modifier = Modifier
                        .offset(x = maxWidth * city.x - 39.dp, y = maxHeight * city.y - 24.dp)
                        .width(82.dp),
                    onClick = { onSelect(city.id) },
                    color = when {
                        current -> WorldMapLineBright
                        selected -> Color(0xFF173D5C)
                        else -> WorldMapNightSoft
                    },
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, if (selected || current) WorldMapBlueSoft else WorldMapLine),
                ) {
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (current) Icons.Outlined.MyLocation else Icons.Outlined.LocationCity, null, tint = if (current) WorldMapNight else WorldMapBlueSoft, modifier = Modifier.size(15.dp))
                        Text(city.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                }
            }

            Text("北 ↑", color = Color(0xFF7894AC), fontSize = 9.sp, modifier = Modifier.align(Alignment.TopEnd).padding(11.dp))
            Text("东江", color = Color(0xFF6FAAD0), fontSize = 8.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 9.dp))
            Text("弯曲线：主要公路/铁路走廊 · 蓝色水系：东江", color = Color(0xFF7894AC), fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
    }
}

@Composable
private fun ApocalypseRegionCityCardV5(
    city: ApocalypseWorldCityV5,
    isCurrent: Boolean,
    onOpenCity: () -> Unit,
    onPlan: () -> Unit,
) {
    Surface(color = WorldMapWhite, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, WorldMapBorder)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(city.name, color = WorldMapInk, fontSize = 19.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (isCurrent) Text("当前位置", color = Color(0xFF287EBE), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text("${city.direction} · ${city.travelFromLinjiang}", color = Color(0xFF287EBE), fontSize = 10.sp)
            Text(city.summary, color = WorldMapMuted, fontSize = 11.sp, lineHeight = 17.sp)
            Text("主要风险：${city.hazard}", color = WorldMapMuted, fontSize = 10.sp, lineHeight = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenCity, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Map, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("查看市内图", fontSize = 11.sp)
                }
                if (!isCurrent) {
                    OutlinedButton(onClick = onPlan, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Route, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("规划前往", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApocalypseCityInternalMapV5(
    city: ApocalypseWorldCityV5,
    currentLocation: String,
    discoveredLocations: List<ApocalypseV3Location>,
    evolution: ApocalypseMapEvolutionV5,
    onSelectPlace: (ApocalypseWorldPlaceV5) -> Unit,
) {
    val positions = listOf(
        .18f to .23f,
        .52f to .16f,
        .80f to .28f,
        .72f to .54f,
        .83f to .76f,
        .48f to .82f,
        .16f to .70f,
        .28f to .48f,
    )

    Surface(
        modifier = Modifier.fillMaxWidth().height(430.dp),
        color = WorldMapNight,
        shape = RoundedCornerShape(22.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(WorldMapNight)) {
            Canvas(Modifier.matchParentSize()) {
                val center = Offset(size.width * .5f, size.height * .5f)
                drawCircle(color = Color(0xFF17334A), radius = size.minDimension * .31f, center = center, style = Stroke(width = 3f))
                drawCircle(color = Color(0xFF10283E), radius = size.minDimension * .20f, center = center, style = Stroke(width = 2f))

                city.places.forEachIndexed { index, _ ->
                    val p = positions[index % positions.size]
                    val end = Offset(size.width * p.first, size.height * p.second)
                    val bend = if (index % 2 == 0) 42f else -42f
                    val path = Path().apply {
                        moveTo(center.x, center.y)
                        quadraticBezierTo((center.x + end.x) / 2f + bend, (center.y + end.y) / 2f, end.x, end.y)
                    }
                    drawPath(
                        path,
                        color = WorldMapLine.copy(alpha = 1f - evolution.infrastructureDecay * .45f),
                        style = Stroke(
                            width = 3f,
                            pathEffect = if (evolution.dayIndex >= 0) PathEffect.dashPathEffect(floatArrayOf(28f, 7f + evolution.infrastructureDecay * 16f)) else null,
                        ),
                    )
                }

                if (city.id == "linjiang" || city.id == "hailing") {
                    val water = Path().apply {
                        moveTo(size.width * .02f, size.height * .58f)
                        cubicTo(size.width * .24f, size.height * .48f, size.width * .55f, size.height * .64f, size.width * .98f, size.height * .52f)
                    }
                    drawPath(water, color = Color(0xFF1C5279), style = Stroke(width = 9f))
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.Center).width(88.dp),
                color = Color(0xFF173D5C),
                shape = RoundedCornerShape(15.dp),
                border = BorderStroke(1.dp, WorldMapBlueSoft),
            ) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.LocationCity, null, tint = WorldMapBlueSoft, modifier = Modifier.size(18.dp))
                    Text(city.name, color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    Text("城区核心", color = Color(0xFF8AA5BC), fontSize = 8.sp)
                }
            }

            city.places.forEachIndexed { index, place ->
                val p = positions[index % positions.size]
                val discovered = discoveredLocations.any { it.name.contains(place.name) || place.name.contains(it.name) }
                val current = currentLocation.contains(place.name)
                val status = apocalypsePlaceMapStatusV5(evolution, city, place, currentLocation, discovered)
                Surface(
                    modifier = Modifier
                        .offset(x = maxWidth * p.first - 39.dp, y = maxHeight * p.second - 25.dp)
                        .width(82.dp),
                    onClick = { onSelectPlace(place) },
                    color = when {
                        current -> WorldMapLineBright
                        status.condition !in setOf(ApocalypseMapConditionV5.Baseline, ApocalypseMapConditionV5.Unknown) -> mapConditionDarkSurfaceV5(status.condition)
                        discovered -> Color(0xFF173D5C)
                        else -> WorldMapNightSoft
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (current || discovered) WorldMapBlueSoft else WorldMapLine),
                ) {
                    Column(Modifier.padding(horizontal = 5.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(placeIconV5(place.kind), null, tint = if (current) WorldMapNight else WorldMapBlueSoft, modifier = Modifier.size(14.dp))
                        Text(place.name, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (evolution.dayIndex >= 0) Text(status.label, color = mapConditionAccentV5(status.condition), fontSize = 7.sp, maxLines = 1)
                    }
                }
            }

            Text("${city.name} · 重要设施关系图", color = Color(0xFF9CB5CA), fontSize = 9.sp, modifier = Modifier.align(Alignment.TopStart).padding(11.dp))
            Text("曲线为城区主干通道示意；具体路况以剧情侦查为准", color = Color(0xFF7894AC), fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
    }
}

@Composable
private fun ApocalypseWorldPlaceRowV5(
    city: ApocalypseWorldCityV5,
    place: ApocalypseWorldPlaceV5,
    discovered: Boolean,
    current: Boolean,
    status: ApocalypseMapStatusV5,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (current) Color(0xFFE4F2FF) else WorldMapWhite,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, if (status.condition !in setOf(ApocalypseMapConditionV5.Baseline, ApocalypseMapConditionV5.Unknown)) mapConditionAccentV5(status.condition) else if (current || discovered) WorldMapBlueSoft else WorldMapBorder),
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(0xFFEAF4FF), shape = RoundedCornerShape(10.dp)) {
                Icon(placeIconV5(place.kind), null, tint = Color(0xFF287EBE), modifier = Modifier.padding(8.dp).size(19.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(place.name, color = WorldMapInk, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (current) Text("当前位置", color = Color(0xFF287EBE), fontSize = 8.sp)
                    else if (status.condition != ApocalypseMapConditionV5.Baseline) Text(status.label, color = mapConditionAccentV5(status.condition), fontSize = 8.sp)
                    else if (discovered) Text("已发现", color = Color(0xFF287EBE), fontSize = 8.sp)
                }
                Text("${city.name} · ${place.kind}", color = Color(0xFF287EBE), fontSize = 9.sp)
                if (evolutionDetailVisibleV5(status)) Text(status.detail, color = mapConditionAccentV5(status.condition), fontSize = 9.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(place.detail, color = WorldMapMuted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = WorldMapMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ApocalypseMapEraBannerV5(evolution: ApocalypseMapEvolutionV5) {
    Surface(
        color = if (evolution.dayIndex < 0) Color(0xFFEAF4FF) else Color(0xFF101E2C),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (evolution.dayIndex < 0) WorldMapBorder else WorldMapLine),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${apocalypseDayLabelV5(evolution.dayIndex)} · ${evolution.eraTitle}", color = if (evolution.dayIndex < 0) WorldMapInk else Color.White, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (evolution.dayIndex >= 0) Text("已确认变化 ${evolution.changes.size}", color = WorldMapBlueSoft, fontSize = 9.sp)
            }
            Text(evolution.eraDetail, color = if (evolution.dayIndex < 0) WorldMapMuted else Color(0xFF9CB5CA), fontSize = 10.sp, lineHeight = 15.sp)
            if (evolution.dayIndex >= 0) Text("未确认的远处变化只显示为未知；地图不会泄露导演掌握但玩家尚未发现的事实。", color = Color(0xFF7894AC), fontSize = 9.sp)
        }
    }
}

private fun evolutionDetailVisibleV5(status: ApocalypseMapStatusV5): Boolean =
    status.condition != ApocalypseMapConditionV5.Baseline && status.detail.isNotBlank()

private fun mapConditionColorV5(condition: ApocalypseMapConditionV5): Color = when (condition) {
    ApocalypseMapConditionV5.Baseline, ApocalypseMapConditionV5.Open -> Color(0xFF4EA8FF)
    ApocalypseMapConditionV5.Unknown -> Color(0xFF607287)
    ApocalypseMapConditionV5.Stressed, ApocalypseMapConditionV5.Offline -> Color(0xFF9B7B52)
    ApocalypseMapConditionV5.Damaged -> Color(0xFFB8654A)
    ApocalypseMapConditionV5.Destroyed, ApocalypseMapConditionV5.Blocked -> Color(0xFFB45159)
    ApocalypseMapConditionV5.Occupied -> Color(0xFF7867A8)
    ApocalypseMapConditionV5.Rebuilt -> Color(0xFF4B8D72)
    ApocalypseMapConditionV5.Overgrown -> Color(0xFF547F60)
    ApocalypseMapConditionV5.Flooded -> Color(0xFF467FA8)
    ApocalypseMapConditionV5.Contaminated -> Color(0xFFA85666)
}

private fun mapConditionAccentV5(condition: ApocalypseMapConditionV5): Color = mapConditionColorV5(condition)

private fun mapConditionDarkSurfaceV5(condition: ApocalypseMapConditionV5): Color = when (condition) {
    ApocalypseMapConditionV5.Destroyed, ApocalypseMapConditionV5.Blocked -> Color(0xFF44252C)
    ApocalypseMapConditionV5.Damaged -> Color(0xFF493128)
    ApocalypseMapConditionV5.Occupied -> Color(0xFF302B4B)
    ApocalypseMapConditionV5.Rebuilt -> Color(0xFF203E34)
    ApocalypseMapConditionV5.Overgrown -> Color(0xFF253E2E)
    ApocalypseMapConditionV5.Flooded -> Color(0xFF20394A)
    ApocalypseMapConditionV5.Contaminated -> Color(0xFF432630)
    ApocalypseMapConditionV5.Offline, ApocalypseMapConditionV5.Stressed -> Color(0xFF3B342B)
    else -> Color(0xFF173D5C)
}

private fun placeIconV5(kind: String) = when (kind) {
    "医疗区", "医疗/避难" -> Icons.Outlined.LocalHospital
    "交通枢纽", "交通设施", "港口" -> Icons.Outlined.Route
    "基础设施", "能源设施", "水源" -> Icons.Outlined.Bolt
    "科研设施", "农业科研" -> Icons.Outlined.Science
    "战略仓储", "物流区", "食品工业" -> Icons.Outlined.Inventory2
    else -> Icons.Outlined.Place
}

private fun resolveWorldMapCityV5(location: String, cities: List<ApocalypseWorldCityV5>): ApocalypseWorldCityV5 {
    return cities.firstOrNull { city ->
        location.contains(city.name) || city.places.any { location.contains(it.name) }
    } ?: cities.first()
}

private fun cityApproachTargetV5(city: ApocalypseWorldCityV5) = ApocalypseV3Location(
    id = "geo_${city.id}_approach",
    name = "${city.name} · 城市外围",
    detail = "跨市行动目标。${city.travelFromLinjiang}；进入前需要侦查道路、燃料、天气、桥隧和沿途落脚点。",
    unlocked = true,
)

private fun placeTargetV5(city: ApocalypseWorldCityV5, place: ApocalypseWorldPlaceV5) = ApocalypseV3Location(
    id = "geo_${city.id}_${place.id}",
    name = "${city.name} · ${place.name}",
    detail = "${place.kind}｜${place.detail}",
    unlocked = true,
)

private fun dynamicLocationsForCityV5(
    city: ApocalypseWorldCityV5,
    currentCity: ApocalypseWorldCityV5,
    discovered: List<ApocalypseV3Location>,
): List<ApocalypseV3Location> {
    val staticNames = city.places.map { it.name }
    return discovered.filter { known ->
        val belongs = known.name.contains(city.name) || known.detail.contains(city.name) ||
            (city.id == currentCity.id && !apocalypseWorldCitiesV5().any { other -> other.id != city.id && (known.name.contains(other.name) || known.detail.contains(other.name)) })
        belongs && staticNames.none { staticName -> known.name.contains(staticName) || staticName.contains(known.name) }
    }.takeLast(12)
}
