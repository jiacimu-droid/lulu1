package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories

/**
 * One-time compatibility cleanup for the retired automatic study-data sharing.
 *
 * User-owned focus and exam statistics remain in their original stores. Only
 * the old system-generated, pinned per-character summaries are removed. The
 * timer is no longer a source of character memories or unsolicited messaging.
 */
object RoleReadablePerformanceBridge {
    suspend fun retireAutomaticSharing() {
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            LuluRepositories.memory.delete("system-duration-$characterId")
        }
    }
}
