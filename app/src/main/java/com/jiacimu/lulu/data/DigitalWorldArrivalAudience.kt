package com.jiacimu.lulu.data

/** A visit notification is not a claim that someone at another location witnessed it. */
internal object DigitalWorldArrivalAudience {
    fun recipients(sceneCode: String, knownCharacterIds: Set<String>,
        ownerId: String?, localCharacterIds: Set<String>): Set<String> = when {
        sceneCode.startsWith("home:") ->
            setOfNotNull(ownerId?.takeIf { it.isNotBlank() })
        sceneCode.startsWith("shared:") ->
            (knownCharacterIds + localCharacterIds).filter(String::isNotBlank).toSet()
        else -> emptySet()
    }
}
