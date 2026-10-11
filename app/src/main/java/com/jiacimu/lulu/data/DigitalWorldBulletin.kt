package com.jiacimu.lulu.data

import java.time.Instant

/** News is rendered exclusively from PERSISTED world objects and events.
 * Proposals, model descriptions and unverified resident ideas never appear.
 */
internal data class DigitalWorldNotice(
    val id: String,
    val title: String,
    val detail: String,
    val occurredAt: Instant,
    val category: String,
)

internal object DigitalWorldBulletin {
    fun collect(
        world: DigitalWorldState,
        residents: List<DigitalWorldResident>,
        places: List<DigitalWorldDiscoveredPlace>,
    ): List<DigitalWorldNotice> {
        val notices = mutableListOf<DigitalWorldNotice>()
        residents.forEach { resident ->
            notices += DigitalWorldNotice(
                id = "resident:${resident.id}",
                title = "新居民 · ${resident.name}",
                detail = "数字世界迎来了有固定身份的居民「${resident.name}」：${resident.identity}。可以在世界里真正遇见他/她。",
                occurredAt = resident.createdAt,
                category = "居民",
            )
        }
        places.forEach { place ->
            notices += DigitalWorldNotice(
                id = "place:${place.code}",
                title = "新地点 · ${place.label}",
                detail = "新增可持续探索的地点「${place.label}」。${place.purpose.take(150)}",
                occurredAt = place.createdAt,
                category = "地点",
            )
        }
        world.events.asSequence()
            .filter { event ->
                event.kind in setOf(
                    "home_created", "build_home_item", "home_item_built",
                    "item_built", "item_created", "item_removed",
                    "remove_home_item", "visit_received", "visit_character_home",
                    "handle_incident",
                    "location_discovered", "new_location",
                ) || event.kind.startsWith("resident_") ||
                    event.kind.startsWith("world_expansion")
            }
            .forEach { event ->
                notices += DigitalWorldNotice(
                    id = "event:${event.id}",
                    title = when {
                        event.kind == "home_created" -> "新家落成"
                        event.kind.contains("resident") -> "世界居民动态"
                        event.kind.contains("location") -> "世界地点变化"
                        else -> "世界建设动态"
                    },
                    detail = event.summary.take(230),
                    occurredAt = event.occurredAt,
                    category = "世界",
                )
            }
        return notices.distinctBy(DigitalWorldNotice::id)
            .sortedWith(compareByDescending<DigitalWorldNotice> { it.occurredAt }.thenBy { it.id })
            .take(80)
    }
}
