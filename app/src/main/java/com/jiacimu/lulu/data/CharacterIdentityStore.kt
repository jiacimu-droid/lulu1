package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores world-bound character identity separately from personality/persona.
 *
 * Default rule: any normal Lulu model context that references a character uses identity together
 * with persona. The only cross-world exceptions are roleplay campaigns and Apocalypse Survival;
 * those keep persona but deliberately omit the original-world identity so a different setting can
 * assign its own job, era, faction, species and background without changing who the character is.
 */
object CharacterIdentityStore {
    private const val PREFS = "lulu_character_identity"
    private const val KEY_PREFIX = "identity_"

    private val mutable = MutableStateFlow<Map<String, String>>(emptyMap())
    val identities: StateFlow<Map<String, String>> = mutable.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            mutable.value = prefs?.all.orEmpty()
                .mapNotNull { (key, value) ->
                    if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
                    val characterId = key.removePrefix(KEY_PREFIX).trim()
                    val identity = (value as? String).orEmpty().trim()
                    if (characterId.isBlank() || identity.isBlank()) null else characterId to identity
                }
                .toMap()
        }
    }

    fun get(characterId: String): String = mutable.value[characterId].orEmpty()

    fun set(characterId: String, identity: String) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return
        val clean = identity.trim()
        synchronized(lock) {
            mutable.value = if (clean.isBlank()) mutable.value - cleanId else mutable.value + (cleanId to clean)
            prefs?.edit()?.apply {
                if (clean.isBlank()) remove(KEY_PREFIX + cleanId) else putString(KEY_PREFIX + cleanId, clean)
            }?.apply()
        }
    }

    fun delete(characterId: String) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return
        synchronized(lock) {
            mutable.value = mutable.value - cleanId
            prefs?.edit()?.remove(KEY_PREFIX + cleanId)?.apply()
        }
    }
}
