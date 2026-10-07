package com.jiacimu.lulu

import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.DigitalWorldStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AvatarPresentation(val characterId: String, val mood: String, val location: String,
    val speaking: Boolean = false, val listening: Boolean = false, val mouthOpen: Float = 0f)

/** Rendering consumes state and audio level; it never invents world actions or calls an LLM per frame. */
object AvatarController {
    private val mutable = MutableStateFlow<Map<String, AvatarPresentation>>(emptyMap())
    val states = mutable.asStateFlow()
    @Synchronized fun playback(characterId: String, speaking: Boolean) {
        val old = mutable.value[characterId]
        mutable.value = mutable.value + (characterId to AvatarPresentation(characterId,
            CompanionPresenceStore.current(characterId)?.mood.orEmpty(), DigitalWorldStore.locationOf(characterId),
            speaking, old?.listening ?: false, 0f))
    }
    @Synchronized fun audio(characterId: String, level: Float) {
        val old = mutable.value[characterId]
        mutable.value = mutable.value + (characterId to AvatarPresentation(characterId,
            CompanionPresenceStore.current(characterId)?.mood.orEmpty(), DigitalWorldStore.locationOf(characterId),
            speaking = level > 0.01f, listening = old?.listening ?: false, mouthOpen = level.coerceIn(0f, 1f)))
    }
    @Synchronized fun listening(characterId: String, listening: Boolean) {
        val old = mutable.value[characterId]
        mutable.value = mutable.value + (characterId to AvatarPresentation(characterId,
            CompanionPresenceStore.current(characterId)?.mood.orEmpty(), DigitalWorldStore.locationOf(characterId),
            old?.speaking ?: false, listening, old?.mouthOpen ?: 0f))
    }
}
