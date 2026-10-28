package com.jiacimu.lulu.games

import kotlin.math.ceil

/** World coordinates are simulation units, not metres. Keep pickup and return checks separate. */
internal const val DEEP_SEA_PLAYER_RADIUS = 36f
internal const val DEEP_SEA_PICKUP_RADIUS = 86f
internal const val DEEP_SEA_RETURN_RADIUS = 88f
internal const val DEEP_SEA_SONAR_RADIUS = 620f

internal data class DeepSeaSite(
    val id: String,
    val name: String,
    val position: WorldVector,
    val discovery: String,
    val alternateDiscovery: String,
)

// The same dive sees the same event after restore; a new dive can reveal another detail.
internal fun deepSeaSiteStory(site: DeepSeaSite, runStartedAt: Long): String =
    if ((((runStartedAt / 1_000L) + site.id.hashCode().toLong()).and(1L)) == 0L)
        site.discovery else site.alternateDiscovery

internal val deepSeaSites = listOf(
    DeepSeaSite("kelp-garden", "荧光珊瑚林", WorldVector(685f, 1_135f), "珊瑚间有一群透明小鱼，顺着潜航器的灯光游成了星河。", "一只发光的海兔躲进珊瑚枝间，又悄悄探出了头。"),
    DeepSeaSite("wreck-log", "沉船航海日志", WorldVector(1_195f, 865f), "你在沉船外舷找到了刻着旧航线的铜牌，记下了一段失落航程。", "沉船舷窗里透出蓝色反光，一本旧日志正随着水流轻轻翻页。"),
    DeepSeaSite("hidden-cave", "海沟秘洞", WorldVector(1_255f, 1_170f), "礁壁背后藏着一处安静的洞口，岩层中闪着蓝色微光。", "秘洞深处飘出细小的光点，海流将它们卷成了旋涡。"),
    DeepSeaSite("whale-song", "鲸歌回廊", WorldVector(730f, 700f), "远处鲸鸣穿过岩壁，海水像琴弦一样轻轻震动。", "鲸群的低鸣让声呐短暂重叠，随后化成一段柔和的旋律。"),
)

/** Verify a clear approach for the vessel's collision body, not just the item sprite. */
internal fun deepSeaClearApproach(from: WorldVector, to: WorldVector, obstacles: List<WorldObstacle>): Boolean {
    val steps = ceil(from.distanceTo(to) / 8f).toInt().coerceAtLeast(1)
    return (0..steps).all { step ->
        val point = from.lerp(to, step.toFloat() / steps)
        obstacles.none { circleIntersects(point, DEEP_SEA_PLAYER_RADIUS, it.bounds) }
    }
}

internal fun deepSeaNearbyEcho(
    map: DeepSeaMap,
    player: WorldVector,
    collected: Set<String>,
    obstacles: List<WorldObstacle>,
): DeepSeaEcho? = map.echoes.asSequence()
    .filterNot { it.id in collected }
    .filter { player.distanceTo(it.position) <= DEEP_SEA_PICKUP_RADIUS && deepSeaClearApproach(player, it.position, obstacles) }
    .minByOrNull { player.distanceTo(it.position) }

internal fun deepSeaNearbySite(
    player: WorldVector,
    visited: Set<String>,
    obstacles: List<WorldObstacle>,
): DeepSeaSite? = deepSeaSites.asSequence()
    .filterNot { it.id in visited }
    .filter { player.distanceTo(it.position) <= 100f && deepSeaClearApproach(player, it.position, obstacles) }
    .minByOrNull { player.distanceTo(it.position) }

internal fun deepSeaCanReturn(
    map: DeepSeaMap,
    player: WorldVector,
    collected: Set<String>,
    obstacles: List<WorldObstacle>,
): Boolean = map.echoes.all { it.id in collected } &&
    player.distanceTo(map.gate) <= DEEP_SEA_RETURN_RADIUS &&
    deepSeaClearApproach(player, map.gate, obstacles)

internal fun deepSeaSonarFind(map: DeepSeaMap, player: WorldVector, collected: Set<String>, radius: Float = DEEP_SEA_SONAR_RADIUS): Set<String> =
    map.echoes.filter { it.id !in collected && player.distanceTo(it.position) <= radius }
        .mapTo(mutableSetOf()) { it.id }

internal fun deepSeaRouteDistance(from: WorldVector, path: List<WorldVector>): Float? =
    path.takeIf { it.isNotEmpty() }?.let { route ->
        (listOf(from) + route).zipWithNext().sumOf { (a, b) -> a.distanceTo(b).toDouble() }.toFloat()
    }
