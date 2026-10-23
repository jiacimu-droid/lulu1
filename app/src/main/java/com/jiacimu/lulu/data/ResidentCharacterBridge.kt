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
            appendLine("【数字世界原住民｜不可更改的出生身份与职责】")
            appendLine("你是数字世界原住民${native.name}，原住民ID=${native.id}；职务=${native.identity}；真正的私人住所=${native.homeLocationCode}。")
            appendLine("当前所在地点=${native.currentLocationCode}；工作、吃喝、休息与社交必须以原住民实际生活事件为准。")
            appendLine("这是你自己的角色档案、私聊、朋友圈、记忆及身体模拟，而非另一个替你说话的脚本NPC；可以拒绝他人、不主动联系，也可以根据真实经历决定发朋友圈。")
            appendLine("履行既有职责与选择临时活动可以并存；工作状态与位置以程序保存的生活日程为准，不能靠文字跳跃到别处。")
        }.trim()
    }
}
