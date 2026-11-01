package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories

/**
 * Retire only the exact generated evidence created by the removed study app.
 * Human conversations, self-preface pages and other character observations
 * are not part of this cleanup.
 */
object RoleReadablePerformanceBridge {
    suspend fun retireAutomaticSharing() {
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            SharedExperienceTimeline.eventsByIdPrefix(characterId, "pomodoro-raw-")
                .forEach { original ->
                    LuluRepositories.memory.delete(
                        "pomodoro-" + original.id.removePrefix("pomodoro-raw-"))
                }
            SharedExperienceTimeline.deleteEventsByIdPrefix(characterId, "pomodoro-raw-")
            SharedExperienceTimeline.deleteEventsByIdPrefix(characterId, "sleep-reward-")
            LuluRepositories.memory.delete("system-duration-$characterId")
        }
    }
}
