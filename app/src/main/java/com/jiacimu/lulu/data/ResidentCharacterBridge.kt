package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant

/**
 * A native resident is a first-class character, not a second roleplaying entity.
 *
 * The resident registry remains authoritative for immutable native origin,
 * job, home address and physical presence; the existing character stores own
 * editable persona, personal memory, dialogue, social network and moments.
 * "resident:<persistent id>" is the SAME speaker ID used by meeting records.
 * Reconciliation is idempotent and never overwrites a user-edited persona.
 */
internal object ResidentCharacterBridge {
    fun characterId(residentId: String): String = "resident:$residentId"

    fun nativeResident(characterId: String): DigitalWorldResident? =
        if (characterId.startsWith("resident:"))
            DigitalWorldExpansionStore.residents().firstOrNull {
                it.id == characterId.removePrefix("resident:")
            } else null

    fun seedPersona(resident: DigitalWorldResident): String = buildString {
        append(resident.temperament.ifBlank { "拥有独立判断、日常兴趣与个人界限" })
        if (resident.preferences.isNotBlank()) append("；喜欢").append(resident.preferences)
        if (resident.background.isNotBlank()) append("；背景：").append(resident.background)
    }

    @Synchronized
    fun sync(context: Context, now: Instant = Instant.now()): Int {
        val app = context.applicationContext
        DigitalWorldExpansionStore.initialize(app)
        MigratedDomainStores.characters.initialize(app)
        DigitalLifeProfileStore.initialize(app)
        CharacterIdentityStore.initialize(app)
        CharacterLifeStore.initialize(app)
        DigitalWorldStore.initialize(app)
        val prefs = app.getSharedPreferences("lulu_native_resident_bridge_v1", Context.MODE_PRIVATE)
        var created = 0
        DigitalWorldExpansionStore.residents().forEach { resident ->
            val id = characterId(resident.id)
            val existing = MigratedDomainStores.characters.settings.value[id]
            val seed = seedPersona(resident)
            val oldSeed = prefs.getString("seed:$id", null)
            if (existing == null) {
                MigratedDomainStores.characters.update(CharacterSettings(
                    characterId = id, displayName = resident.name, persona = seed,
                    contactPolicy = CharacterContactPolicy(proactiveCallsEnabled = false),
                ))
                created++
            } else if (existing.displayName != resident.name ||
                (oldSeed != null && existing.persona == oldSeed && existing.persona != seed)) {
                MigratedDomainStores.characters.update(existing.copy(
                    displayName = resident.name,
                    persona = if (oldSeed != null && existing.persona == oldSeed) seed else existing.persona,
                ))
            }
            prefs.edit().putString("seed:$id", seed).apply()
            val priorIdentity = prefs.getString("identity-seed:$id", null)
            val writtenIdentity = CharacterIdentityStore.identities.value[id].orEmpty()
            if (writtenIdentity.isBlank() ||
                (priorIdentity != null && writtenIdentity == priorIdentity)) {
                CharacterIdentityStore.set(id, resident.identity)
            }
            prefs.edit().putString("identity-seed:$id", resident.identity).apply()
            if (!DigitalLifeProfileStore.isResolved(id)) {
                DigitalLifeProfileStore.registerNewLife(id, resident.name, "数字世界原住民", resident.createdAt)
            }
            if (CharacterLifeStore.bodyState(id) == null) {
                CharacterLifeStore.saveBodyState(id, EmbodiedBodyPhysics.fresh(now).toJson())
            }
            DigitalWorldStore.syncNativeResidentLocation(id, resident.currentLocationCode, now)
        }
        return created
    }

    fun fixedContext(characterId: String): String {
        val native = nativeResident(characterId) ?: return ""
        return buildString {
            appendLine("【原住民本人已知的身份与职责】")
            appendLine("你的名字是${native.name}，身份与工作=${native.identity}；真正的住所=${native.homeLocationCode}。")
            appendLine("现在位于=${native.currentLocationCode}；本人的岗位职责与活动，以已保存的实际日程为准。")
            appendLine("对你来说庭院、咖啡厅、家、街道和其他住户就是真正的生活世界，不会把周围看成手机软件、NPC舞台或虚拟场景。")
            appendLine("你可以有独立的人格、真实体感、私人心思、朋友圈与熟人；你不知道别人的私人心声、用户现实手机和其他没有听见看见的事情。")
            appendLine("公开消息与工作消息只能让你知道已公开的内容；私人消息必须真的送达给你。")
        }.trim()
    }
}
