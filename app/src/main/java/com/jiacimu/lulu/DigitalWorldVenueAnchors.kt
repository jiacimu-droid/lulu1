package com.jiacimu.lulu

import com.jiacimu.lulu.data.DigitalWorldPublicPlaces
import com.jiacimu.lulu.games.WorldVector

/**
 * Physical activity anchors for public venues.
 *
 * Public-place activities should happen at visible spots instead of through a global menu. These
 * anchors give the renderer, foreground explorer and autonomous characters the same spatial truth.
 */
internal data class DigitalVenueAnchor(
    val id: String,
    val label: String,
    val position: WorldVector,
    val activityIds: List<String>,
    val radius: Float = 118f,
)

internal object DigitalWorldVenueAnchors {
    fun forScene(sceneCode: String): List<DigitalVenueAnchor> = when (sceneCode) {
        DigitalWorldPublicPlaces.GAME_HALL -> listOf(
            DigitalVenueAnchor(
                id = "arcade-left",
                label = "左侧机台区",
                position = WorldVector(360f, 470f),
                activityIds = listOf("browse_games", "choose_arcade"),
            ),
            DigitalVenueAnchor(
                id = "arcade-score",
                label = "成绩榜",
                position = WorldVector(800f, 315f),
                activityIds = listOf("check_scoreboard"),
                radius = 105f,
            ),
            DigitalVenueAnchor(
                id = "arcade-right",
                label = "右侧机台区",
                position = WorldVector(1_250f, 470f),
                activityIds = listOf("browse_games", "choose_arcade"),
            ),
            DigitalVenueAnchor(
                id = "arcade-lounge",
                label = "中央休息岛",
                position = WorldVector(800f, 700f),
                activityIds = listOf("sit_arcade", "arcade_linger"),
                radius = 145f,
            ),
        )
        DigitalWorldPublicPlaces.READING_LOUNGE -> listOf(
            DigitalVenueAnchor(
                id = "reading-left-shelf",
                label = "西侧阅读架",
                position = WorldVector(330f, 505f),
                activityIds = listOf("browse_reading", "reading_wander"),
            ),
            DigitalVenueAnchor(
                id = "reading-window",
                label = "窗边阅读位",
                position = WorldVector(1_125f, 455f),
                activityIds = listOf("window_read", "quiet_read"),
                radius = 135f,
            ),
            DigitalVenueAnchor(
                id = "reading-center",
                label = "中央阅读岛",
                position = WorldVector(800f, 670f),
                activityIds = listOf("quiet_read"),
                radius = 145f,
            ),
            DigitalVenueAnchor(
                id = "reading-soft-seat",
                label = "软座区",
                position = WorldVector(1_080f, 845f),
                activityIds = listOf("reading_rest"),
            ),
        )
        DigitalWorldPublicPlaces.CAFE -> listOf(
            DigitalVenueAnchor(
                id = "cafe-counter",
                label = "吧台",
                position = WorldVector(1_170f, 420f),
                activityIds = listOf("order_drink", "order_snack"),
                radius = 120f,
            ),
            DigitalVenueAnchor(
                id = "cafe-window",
                label = "靠窗座位",
                position = WorldVector(410f, 530f),
                activityIds = listOf("window_cafe", "slow_drink"),
                radius = 130f,
            ),
            DigitalVenueAnchor(
                id = "cafe-center",
                label = "中央座位",
                position = WorldVector(760f, 720f),
                activityIds = listOf("cafe_sit", "people_watch", "slow_drink"),
                radius = 150f,
            ),
            DigitalVenueAnchor(
                id = "cafe-corner",
                label = "植物角",
                position = WorldVector(1_310f, 820f),
                activityIds = listOf("cafe_linger", "people_watch"),
                radius = 130f,
            ),
        )
        DigitalWorldPublicPlaces.COURTYARD -> listOf(
            DigitalVenueAnchor(
                id = "courtyard-path",
                label = "弯曲步道",
                position = WorldVector(560f, 760f),
                activityIds = listOf("courtyard_walk", "courtyard_linger"),
                radius = 170f,
            ),
            DigitalVenueAnchor(
                id = "courtyard-bench",
                label = "林下长椅",
                position = WorldVector(700f, 790f),
                activityIds = listOf("courtyard_sit", "watch_sky"),
                radius = 120f,
            ),
            DigitalVenueAnchor(
                id = "courtyard-water",
                label = "水边",
                position = WorldVector(1_080f, 740f),
                activityIds = listOf("water_edge", "watch_sky"),
                radius = 145f,
            ),
            DigitalVenueAnchor(
                id = "courtyard-garden",
                label = "植物缓坡",
                position = WorldVector(1_260f, 470f),
                activityIds = listOf("garden_pause", "courtyard_linger"),
                radius = 130f,
            ),
        )
        else -> emptyList()
    }

    fun anchorForActivity(sceneCode: String, activityId: String): DigitalVenueAnchor? =
        forScene(sceneCode).firstOrNull { activityId in it.activityIds }
}
