package com.jiacimu.lulu.data

/** Explicit authored facts for daily scene adapters, never another person's private memory. */
internal object WorldSceneParticipantProfile {
    data class Profile(val id: String, val name: String, val gender: String = "",
        val publicIdentity: String = "", val authoredIdentity: String = "", val persona: String = "")

    fun snapshot(id: String): Profile {
        val character = MigratedDomainStores.characters.settings.value[id]
        val native = ResidentCharacterBridge.nativeResident(id)
        return Profile(id, character?.displayName ?: native?.name ?: id,
            native?.gender.orEmpty(), native?.identity.orEmpty(),
            CharacterIdentityStore.identities.value[id].orEmpty(), CharacterRuntime.personaConstraintSnapshot(id))
    }

    fun format(profile: Profile, forNarrator: Boolean): String = buildString {
        appendLine("人物ID=${profile.id}；姓名=${profile.name}")
        appendLine("明确性别=${profile.gender.ifBlank { "未单列；依本人明确设定，不凭姓名或职业猜测" }}")
        if (profile.publicIdentity.isNotBlank()) appendLine("登记的公开身份=${profile.publicIdentity}")
        if (forNarrator) {
            appendLine("本人明确身份设定（写作约束，不代表他人已经知道这些背景）=${profile.authoredIdentity}")
            appendLine("完整稳定人设（不是私人心声或新事件）=${profile.persona}")
        }
    }.trim()

    fun context(ids: Collection<String>, forNarrator: Boolean): String =
        ids.distinct().joinToString("\n\n") { format(snapshot(it), forNarrator) }

    const val instruction = "姓名、明确性别和身份以人物资料为准，不根据职业或名字补成男性或使用相冲突的先生/女士称谓；未知则保持未知。叙述可以遵循设定，人物本人只知道真实见闻与公开信息，不能因为写作资料出现背景就声称自己已经获知。"
}
