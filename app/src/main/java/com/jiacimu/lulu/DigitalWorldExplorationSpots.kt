package com.jiacimu.lulu

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jiacimu.lulu.data.DigitalWorldPublicPlaces
import com.jiacimu.lulu.data.DigitalWorldStore
import com.jiacimu.lulu.games.WorldVector

/**
 * Discoverable, fixed world landmarks, not random stories or invented inventory items.
 * Their visibility, location and descriptions have a program-defined counterpart in the scene.
 */
internal data class DigitalWorldExplorationSpot(
    val id: String,
    val label: String,
    val position: WorldVector,
    val description: String,
    val radius: Float = 115f,
)

internal object DigitalWorldExplorationSpots {
    fun forScene(sceneCode: String): List<DigitalWorldExplorationSpot> = when {
        sceneCode == DigitalWorldStore.CLOUD_MEADOW -> listOf(
            DigitalWorldExplorationSpot("cloud-edge", "云海边缘", WorldVector(1_210f, 590f),
                "岛的边缘能看见层层云雾，往远处望去没有街道的边界。"),
            DigitalWorldExplorationSpot("cloud-center", "草地中央", WorldVector(680f, 600f),
                "这里是云眠原上的一块宽阔空地，可以停下脚步看看周围。"),
        )
        sceneCode == DigitalWorldPublicPlaces.COURTYARD -> listOf(
            DigitalWorldExplorationSpot("courtyard-water", "水面倒影", WorldVector(1_080f, 740f),
                "庭院的水面映着树影，沿岸的小路通向长椅。"),
            DigitalWorldExplorationSpot("courtyard-bench", "林下长椅", WorldVector(700f, 790f),
                "这张长椅就在步道旁，附近能看到树木与花坛。"),
        )
        sceneCode == DigitalWorldPublicPlaces.CAFE -> listOf(
            DigitalWorldExplorationSpot("cafe-window", "窗边光影", WorldVector(410f, 530f),
                "窗边的位置比较安静，可以观察街景和室内光影。"),
            DigitalWorldExplorationSpot("cafe-counter", "咖啡吧台", WorldVector(1_170f, 420f),
                "吧台是这里的服务区域；能否点单以场所实际活动为准。"),
        )
        sceneCode == DigitalWorldPublicPlaces.GAME_HALL -> listOf(
            DigitalWorldExplorationSpot("arcade-board", "成绩榜", WorldVector(800f, 315f),
                "墙上的成绩榜记录游戏馆的游玩氛围，不代表虚构的真实分数。"),
            DigitalWorldExplorationSpot("arcade-machines", "街机机台", WorldVector(360f, 470f),
                "这里摆着游戏机台，可以在对应的机台交互点选择真实可玩的游戏。"),
        )
        sceneCode.startsWith("resident-home:") -> listOf(
            DigitalWorldExplorationSpot("native-shelf", "主人家的书架", WorldVector(340f, 460f),
                "固定书架属于这户原住民的真实陈设，并不是世界公用图书馆。"),
            DigitalWorldExplorationSpot("home-window", "窗边", WorldVector(1_250f, 470f),
                "窗外的光照进室内；屋内的陈设是房间里真实可见的物件。"),
        )
        sceneCode.startsWith("home:") -> listOf(
            DigitalWorldExplorationSpot("home-window", "窗边", WorldVector(1_250f, 470f),
                "从窗边望出去，可以看见屋外光影；房间里的家具由实际布置决定。"),
            DigitalWorldExplorationSpot("home-entry", "家门附近", WorldVector(800f, 850f),
                "这是屋内的出入区域，停下来可以看看房间目前的布局。"),
        )
        sceneCode == DigitalWorldStore.ARRIVAL -> listOf(
            DigitalWorldExplorationSpot("arrival-view", "四周景象", WorldVector(800f, 535f),
                "这是保留给旧记录的过渡位置，不再作为大世界的独立地图入口。"),
        )
        else -> listOf(
            DigitalWorldExplorationSpot("world-view", "看看四周", WorldVector(810f, 670f),
                "观察当前实际抵达的区域，记住这次探索的位置。"),
        )
    }
}

/** Subtle in-world discovery glyphs with persistent found/unfound styling. */
internal fun DrawScope.drawDigitalExplorationMarkers(
    spots: List<DigitalWorldExplorationSpot>,
    discovered: Set<String>,
    phase: Float,
) {
    spots.forEach { spot ->
        val p = Offset(spot.position.x, spot.position.y)
        val known = spot.id in discovered
        val color = if (known) Color(0xFFD1E5DA) else Color(0xFFF5DFAB)
        drawCircle(Color(0xFF16372D).copy(alpha = .23f), 28f, p.copy(y = p.y + 5f))
        drawCircle(color.copy(alpha = if (known) .08f else .14f + phase * .08f),
            34f + phase * 4f, p)
        drawCircle(color.copy(alpha = if (known) .32f else .78f), 17f, p, style = Stroke(2f))
        drawLine(color.copy(alpha = .86f), Offset(p.x - 8f, p.y),
            Offset(p.x + 8f, p.y), 2f)
        drawLine(color.copy(alpha = .86f), Offset(p.x, p.y - 8f),
            Offset(p.x, p.y + 8f), 2f)
    }
}
