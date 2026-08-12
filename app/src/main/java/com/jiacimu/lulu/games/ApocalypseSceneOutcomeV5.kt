package com.jiacimu.lulu.games

import com.jiacimu.lulu.data.CharacterSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val APOCALYPSE_SCENE_STATE_MARKER_V5 = "<<<APOCALYPSE_STATE>>>"
internal const val APOCALYPSE_SCENE_TEXT_MARKER_V5 = "<<<APOCALYPSE_SCENE>>>"

private val APOCALYPSE_DELTA_FIELDS_V5 = setOf(
    "minutesPassed", "moneyDelta", "foodDelta", "waterDelta", "medicineDelta", "materialsDelta",
    "coresFound", "playerAbilityXpGain", "baseDelta", "healthDelta", "staminaDelta",
    "infectionDelta", "moraleDelta", "moneyAfter", "foodAfter", "waterAfter", "medicineAfter",
    "materialsAfter", "coresAfter", "healthAfter", "staminaAfter", "infectionAfter", "moraleAfter",
    "endDayIndex", "endClockMinutes", "discoverAssets", "inventoryChanges",
)

/**
 * A scene is useful only when its prose and its persisted world state agree. The writer returns this
 * compact receipt in the same model call as the prose, so ordinary player actions do not need a
 * separate director request merely to become canonical.
 */
internal data class ApocalypseSceneOutcomeV5(
    val text: String,
    val actionAcknowledged: Boolean = false,
    val actionAcknowledgementReported: Boolean = false,
    val actionOutcome: String = "",
    val continuitySummary: String = "",
    val respondedCharacterIds: List<String> = emptyList(),
    val directorRefreshNeeded: Boolean = false,
    val presentCharactersReported: Boolean = false,
    val receiptParsed: Boolean = false,
    val simulationStateReported: Boolean = false,
    /** Exact fields present in the receipt. Missing means unchanged, never “discard every delta”. */
    val reportedStateFields: Set<String> = emptySet(),
    val castUpdates: List<ApocalypseCharacterDossierV5> = emptyList(),
    val characterStatePatches: List<ApocalypseCharacterStatePatchV5> = emptyList(),
    val storyThreadUpdates: List<ApocalypseStoryThreadV5> = emptyList(),
    val foreshadowPatches: List<ApocalypseForeshadowPatchV5> = emptyList(),
    val inventoryChanges: List<ApocalypseInventoryChangeV5> = emptyList(),
    val delta: ApocalypseSceneDeltaV5 = ApocalypseSceneDeltaV5(),
)

internal data class ApocalypseInventoryChangeV5(
    val id: String = "",
    val kind: ApocalypseV3AssetKind,
    val title: String,
    val quantityDelta: Int,
    val detail: String = "",
    val tag: String = "",
)

internal data class ApocalypseSceneDeltaV5(
    val location: String = "",
    val sceneGoal: String = "",
    val beatType: String = "continuation",
    val emotionalTurn: String = "",
    val worldFactsAdd: List<String> = emptyList(),
    val characterStateAdds: List<String> = emptyList(),
    val presentCharacterIds: List<String> = emptyList(),
    val discoverAssets: List<ApocalypseV3Asset> = emptyList(),
    val weather: String = "",
    val temperatureC: Int? = null,
    val minutesPassed: Int = 20,
    val moneyDelta: Int = 0,
    val foodDelta: Int = 0,
    val waterDelta: Int = 0,
    val medicineDelta: Int = 0,
    val materialsDelta: Int = 0,
    val coresFound: Int = 0,
    val playerAbilityXpGain: Int = 0,
    val baseDelta: Int = 0,
    val healthDelta: Int = 0,
    val staminaDelta: Int = 0,
    val infectionDelta: Int = 0,
    val moraleDelta: Int = 0,
    val moneyAfter: Int? = null,
    val foodAfter: Int? = null,
    val waterAfter: Int? = null,
    val medicineAfter: Int? = null,
    val materialsAfter: Int? = null,
    val coresAfter: Int? = null,
    val healthAfter: Int? = null,
    val staminaAfter: Int? = null,
    val infectionAfter: Int? = null,
    val moraleAfter: Int? = null,
    val endDayIndex: Int? = null,
    val endClockMinutes: Int? = null,
)

internal fun parseApocalypseSceneOutcomeV5(raw: String): ApocalypseSceneOutcomeV5? = runCatching {
    val normalized = raw.trim()
    val stateStart = normalized.indexOf(APOCALYPSE_SCENE_STATE_MARKER_V5)
    val sceneStart = normalized.indexOf(APOCALYPSE_SCENE_TEXT_MARKER_V5)
    val stateBlock: String
    val text: String
    if (stateStart >= 0 && sceneStart >= 0 && stateStart < sceneStart) {
        stateBlock = normalized.substring(stateStart + APOCALYPSE_SCENE_STATE_MARKER_V5.length, sceneStart)
        text = normalized.substring(sceneStart + APOCALYPSE_SCENE_TEXT_MARKER_V5.length).trim()
    } else if (stateStart >= 0 && sceneStart >= 0) {
        text = normalized.substring(sceneStart + APOCALYPSE_SCENE_TEXT_MARKER_V5.length, stateStart).trim()
        stateBlock = normalized.substring(stateStart + APOCALYPSE_SCENE_STATE_MARKER_V5.length)
    } else {
        // Some compatible models preserve the JSON and visual-novel tags but omit our sentinel
        // lines. Recover either JSON-first or scene-first output without spending a second request.
        val markerless = normalized
            .replace(APOCALYPSE_SCENE_STATE_MARKER_V5, "")
            .replace(APOCALYPSE_SCENE_TEXT_MARKER_V5, "")
            .trim()
        val objectStart = markerless.indexOf('{')
        val objectEnd = markerless.lastIndexOf('}')
        if (objectStart < 0 || objectEnd <= objectStart) return@runCatching null
        val storyTags = listOf("【旁白】", "【玩家】", "【角色:")
        val storyBefore = storyTags.map { markerless.indexOf(it) }.filter { it in 0 until objectStart }.minOrNull()
        val storyAfter = storyTags
            .map { markerless.indexOf(it, startIndex = objectEnd + 1) }
            .filter { it > objectEnd }
            .minOrNull()
        stateBlock = markerless.substring(objectStart, objectEnd + 1)
        text = when {
            storyBefore != null -> markerless.substring(storyBefore, objectStart).trim()
            storyAfter != null -> markerless.substring(storyAfter).trim()
            else -> return@runCatching null
        }
    }
    val cleanedState = stateBlock
        .trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val objectStart = cleanedState.indexOf('{')
    val objectEnd = cleanedState.lastIndexOf('}')
    if (objectStart < 0 || objectEnd <= objectStart) return@runCatching null
    val json = JSONObject(cleanedState.substring(objectStart, objectEnd + 1))
    val reportedStateFields = APOCALYPSE_DELTA_FIELDS_V5.filterTo(mutableSetOf(), json::has)
    val visibleText = text.trim().removePrefix("```text").removePrefix("```").removeSuffix("```").trim()
    if (visibleText.isBlank()) return@runCatching null
    ApocalypseSceneOutcomeV5(
        text = visibleText,
        actionAcknowledged = json.optBoolean("actionAcknowledged", false),
        actionAcknowledgementReported = json.has("actionAcknowledged"),
        actionOutcome = json.optString("actionOutcome").trim().take(240),
        continuitySummary = json.optString("continuitySummary").trim().take(360),
        respondedCharacterIds = json.optJSONArray("respondedCharacterIds").sceneStringsV5().distinct().take(8),
        directorRefreshNeeded = json.optBoolean("directorRefreshNeeded", false),
        presentCharactersReported = json.has("presentCharacterIds"),
        receiptParsed = true,
        simulationStateReported = reportedStateFields.isNotEmpty(),
        reportedStateFields = reportedStateFields,
        castUpdates = decodeApocalypseCastUpdatesV5(json.optJSONArray("castUpdates")),
        characterStatePatches = decodeApocalypseCharacterStatePatchesV5(json.optJSONArray("characterStatePatches")),
        storyThreadUpdates = decodeApocalypseStoryThreadsV5(json.optJSONArray("storyThreadUpdates")),
        foreshadowPatches = decodeApocalypseForeshadowPatchesV5(json.optJSONArray("foreshadowPatches")),
        inventoryChanges = json.optJSONArray("inventoryChanges").sceneObjectsV5 { item ->
            ApocalypseInventoryChangeV5(
                id = item.optString("id").trim(),
                kind = parseSceneAssetKindV5(item.optString("kind")),
                title = item.optString("title").trim().take(70),
                quantityDelta = item.optInt("quantityDelta").coerceIn(-999, 999),
                detail = item.optString("detail").trim().take(360),
                tag = item.optString("tag").trim().take(40),
            )
        }.filter { it.quantityDelta != 0 && (it.id.isNotBlank() || it.title.isNotBlank()) }.take(8),
        delta = ApocalypseSceneDeltaV5(
            location = json.optString("location").trim().take(100),
            sceneGoal = json.optString("sceneGoal").trim().take(260),
            beatType = json.optString("beatType", "continuation").trim().ifBlank { "continuation" }.take(40),
            emotionalTurn = json.optString("emotionalTurn").trim().take(320),
            worldFactsAdd = json.optJSONArray("worldFactsAdd").sceneStringsV5().distinct().take(4),
            characterStateAdds = json.optJSONArray("characterStateAdds").sceneStringsV5().distinct().take(3),
            presentCharacterIds = json.optJSONArray("presentCharacterIds").sceneStringsV5().distinct().take(10),
            discoverAssets = json.optJSONArray("discoverAssets").sceneObjectsV5 { item ->
                ApocalypseV3Asset(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    kind = parseSceneAssetKindV5(item.optString("kind")),
                    title = item.optString("title").ifBlank { "新发现" }.take(70),
                    detail = item.optString("detail").take(360),
                    quantity = item.optInt("quantity", 1).coerceIn(1, 999),
                    tag = item.optString("tag").take(40),
                )
            }.take(4),
            weather = json.optString("weather").trim().take(40),
            temperatureC = json.optInt("temperatureC").takeIf { json.has("temperatureC") }?.coerceIn(-35, 55),
            minutesPassed = json.optInt("minutesPassed", 20).coerceIn(1, 10_080),
            moneyDelta = json.optInt("moneyDelta"),
            foodDelta = json.optInt("foodDelta"),
            waterDelta = json.optInt("waterDelta"),
            medicineDelta = json.optInt("medicineDelta"),
            materialsDelta = json.optInt("materialsDelta"),
            coresFound = json.optInt("coresFound"),
            playerAbilityXpGain = json.optInt("playerAbilityXpGain"),
            baseDelta = json.optInt("baseDelta"),
            healthDelta = json.optInt("healthDelta"),
            staminaDelta = json.optInt("staminaDelta"),
            infectionDelta = json.optInt("infectionDelta"),
            moraleDelta = json.optInt("moraleDelta"),
            moneyAfter = json.optIntOrNullV5("moneyAfter")?.coerceIn(0, 9_999_999),
            foodAfter = json.optIntOrNullV5("foodAfter")?.coerceIn(0, 999),
            waterAfter = json.optIntOrNullV5("waterAfter")?.coerceIn(0, 999),
            medicineAfter = json.optIntOrNullV5("medicineAfter")?.coerceIn(0, 999),
            materialsAfter = json.optIntOrNullV5("materialsAfter")?.coerceIn(0, 999),
            coresAfter = json.optIntOrNullV5("coresAfter")?.coerceIn(0, 9999),
            healthAfter = json.optIntOrNullV5("healthAfter")?.coerceIn(0, 100),
            staminaAfter = json.optIntOrNullV5("staminaAfter")?.coerceIn(0, 100),
            infectionAfter = json.optIntOrNullV5("infectionAfter")?.coerceIn(0, 100),
            moraleAfter = json.optIntOrNullV5("moraleAfter")?.coerceIn(0, 100),
            endDayIndex = json.optIntOrNullV5("endDayIndex")?.coerceIn(-9999, 9999),
            endClockMinutes = json.optIntOrNullV5("endClockMinutes")?.coerceIn(0, 1439),
        ),
    )
}.getOrNull()

internal fun fallbackApocalypseSceneOutcomeV5(raw: String, action: String = ""): ApocalypseSceneOutcomeV5 {
    val afterSceneMarker = raw.substringAfter(APOCALYPSE_SCENE_TEXT_MARKER_V5, raw)
    val withoutTrailingState = afterSceneMarker.substringBefore(APOCALYPSE_SCENE_STATE_MARKER_V5)
    val firstStoryTag = listOf("【旁白】", "【玩家】", "【角色:")
        .map { withoutTrailingState.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull()
    val text = (firstStoryTag?.let { withoutTrailingState.substring(it) } ?: withoutTrailingState)
        .trim()
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
        .ifBlank { "【旁白】这一幕没有生成出有效正文。" }
    val plain = text.replace(Regex("【[^】]+】"), "").trim()
    return ApocalypseSceneOutcomeV5(
        text = text,
        actionOutcome = action.takeIf(String::isNotBlank)?.let {
            "玩家行动“${it.take(90)}”已经进入本幕；具体结果以正文为准。"
        }.orEmpty(),
        continuitySummary = compactApocalypseSceneExcerptV5(plain),
    )
}

/** Hides the JSON-first receipt and exposes only prose that has actually arrived from the stream. */
internal fun apocalypseStreamingSceneTextV5(raw: String): String {
    val sceneStart = raw.indexOf(APOCALYPSE_SCENE_TEXT_MARKER_V5)
    if (sceneStart < 0) return ""
    val afterMarker = raw.substring(sceneStart + APOCALYPSE_SCENE_TEXT_MARKER_V5.length)
        .substringBefore(APOCALYPSE_SCENE_STATE_MARKER_V5)
        .trim()
        .removePrefix("```text")
        .removePrefix("```")
        .trim()
    val firstStoryTag = listOf("【旁白】", "【玩家】", "【角色:")
        .map { marker -> afterMarker.indexOf(marker) }
        .filter { it >= 0 }
        .minOrNull()
        ?: return ""
    return afterMarker.substring(firstStoryTag).take(8_000)
}

private fun JSONObject.optIntOrNullV5(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

internal fun apocalypseSceneOutcomeNeedsRepairV5(
    action: String,
    outcome: ApocalypseSceneOutcomeV5,
    party: List<CharacterSettings>,
    dossiers: List<ApocalypseCharacterDossierV5>,
    presentCharacterIds: List<String>,
): Boolean {
    if (outcome.text.isBlank()) return true
    val tagged = outcome.text.contains("【旁白】") || outcome.text.contains("【玩家】") || outcome.text.contains("【角色:")
    if (!tagged) return true
    // Receipt omissions are recoverable metadata defects, not proof that the prose ignored the
    // player. Retrying a whole scene for one missing delta/summary made a normal action wait for two
    // long model calls and could still discard both usable drafts. Only an explicit rejection from
    // a parsed receipt is strong enough to trigger a content rewrite here.
    if (outcome.actionAcknowledgementReported && !outcome.actionAcknowledged) return true
    // Legacy saves may still need best-effort display recovery, but newly generated prose must
    // never canonize an anonymous placeholder as a character identity.
    val speakerTokens = apocalypseStorySpeakerTokensV5(outcome.text)
    val effectiveDossiers = mergeApocalypseCharacterDossiersV5(dossiers, outcome.castUpdates, party)
    if (speakerTokens.any { token ->
            isApocalypseGenericCastLabelV5(token) && !resolveApocalypseSpeakerTokenV5(
                rawToken = token,
                party = party,
                dossiers = effectiveDossiers,
                presentCharacterIds = presentCharacterIds + outcome.delta.presentCharacterIds,
                visibleText = outcome.text,
            ).knownCharacter
        }
    ) {
        return true
    }
    // A concrete Chinese name or role such as “养父” may be a first-appearance NPC. The cast
    // expander below persists it with a stable id, so forcing the model to rewrite it first only
    // adds latency and often produces the same valid speaker again.
    if (!apocalypseActionLooksLikeSpeechV5(action)) return false

    val availableIds = (party.map { it.characterId } + effectiveDossiers.map { it.id }).toSet()
    val present = normalizeApocalypseCharacterRefsV5(
        values = presentCharacterIds + outcome.delta.presentCharacterIds,
        party = party,
        dossiers = effectiveDossiers,
        presentCharacterIds = presentCharacterIds,
    ).filter(availableIds::contains).toSet()
    if (present.isEmpty()) return false
    val namedTargets = buildSet {
        party.filter { action.contains(it.displayName, ignoreCase = true) }.forEach { add(it.characterId) }
        effectiveDossiers.filter { dossier ->
            (listOf(dossier.name, dossier.relationshipLabel) + dossier.aliases)
                .filter(String::isNotBlank)
                .any { action.contains(it, ignoreCase = true) }
        }.forEach { add(it.id) }
    }.intersect(present)
    val expected = namedTargets.ifEmpty { present }
    val earlyText = outcome.text.take((outcome.text.length * 0.55f).toInt().coerceAtLeast(240))
    return expected.none { earlyText.contains("【角色:$it】") }
}

private fun resolveApocalypseElapsedMinutesV5(
    save: ApocalypseV3Save,
    outcome: ApocalypseSceneOutcomeV5,
    fallbackMinutes: Int,
): Int {
    val delta = outcome.delta
    if (
        "endDayIndex" in outcome.reportedStateFields &&
        "endClockMinutes" in outcome.reportedStateFields &&
        delta.endDayIndex != null && delta.endClockMinutes != null
    ) {
        val currentAbsolute = save.director.dayIndex.toLong() * 1440L + save.director.clockMinutes
        val endingAbsolute = delta.endDayIndex.toLong() * 1440L + delta.endClockMinutes
        val statedElapsed = endingAbsolute - currentAbsolute
        if (statedElapsed in 1L..10_080L) return statedElapsed.toInt()
    }
    return if ("minutesPassed" in outcome.reportedStateFields) {
        delta.minutesPassed.coerceIn(1, 10_080)
    } else {
        fallbackMinutes.coerceIn(1, 10_080)
    }
}

private fun resolveApocalypseStateDeltaV5(
    outcome: ApocalypseSceneOutcomeV5,
    deltaField: String,
    afterField: String,
    reportedDelta: Int,
    reportedAfter: Int?,
    current: Int,
    fallbackDelta: Int,
): Int = when {
    afterField in outcome.reportedStateFields && reportedAfter != null -> reportedAfter - current
    deltaField in outcome.reportedStateFields -> reportedDelta
    else -> fallbackDelta
}

private fun inferredApocalypseAssetDeltaV5(
    outcome: ApocalypseSceneOutcomeV5,
    kind: ApocalypseV3AssetKind,
): Int? {
    val additions = outcome.delta.discoverAssets
        .takeIf { "discoverAssets" in outcome.reportedStateFields }
        ?.filter { it.kind == kind }
        ?.sumOf { it.quantity }
        ?: 0
    val changes = outcome.inventoryChanges
        .takeIf { "inventoryChanges" in outcome.reportedStateFields }
        ?.filter { it.kind == kind }
        ?.sumOf { it.quantityDelta }
        ?: 0
    return (additions + changes).takeIf {
        "discoverAssets" in outcome.reportedStateFields || "inventoryChanges" in outcome.reportedStateFields
    }
}

private fun mergeApocalypseAcquiredAssetsV5(
    previous: List<ApocalypseV3Asset>,
    additions: List<ApocalypseV3Asset>,
    changes: List<ApocalypseInventoryChangeV5> = emptyList(),
): List<ApocalypseV3Asset> {
    val merged = previous.toMutableList()
    additions.forEach { addition ->
        val existingIndex = merged.indexOfFirst { existing ->
            existing.id == addition.id ||
                (existing.kind == addition.kind && existing.title.equals(addition.title, ignoreCase = true))
        }
        if (existingIndex < 0) {
            merged += addition
        } else {
            val existing = merged[existingIndex]
            val replaceOnly = addition.kind in setOf(
                ApocalypseV3AssetKind.Key,
                ApocalypseV3AssetKind.Document,
                ApocalypseV3AssetKind.Clue,
                ApocalypseV3AssetKind.Map,
            )
            merged[existingIndex] = existing.copy(
                detail = addition.detail.ifBlank { existing.detail },
                quantity = if (replaceOnly) maxOf(existing.quantity, addition.quantity) else {
                    (existing.quantity + addition.quantity).coerceAtMost(999)
                },
                tag = addition.tag.ifBlank { existing.tag },
            )
        }
    }
    changes.forEach { change ->
        val existingIndex = merged.indexOfFirst { existing ->
            (change.id.isNotBlank() && existing.id == change.id) ||
                (existing.kind == change.kind && existing.title.equals(change.title, ignoreCase = true))
        }
        if (existingIndex < 0) {
            if (change.quantityDelta > 0) {
                merged += ApocalypseV3Asset(
                    id = change.id.ifBlank { UUID.randomUUID().toString() },
                    kind = change.kind,
                    title = change.title.ifBlank { "新增${change.kind.label}" },
                    detail = change.detail,
                    quantity = change.quantityDelta,
                    tag = change.tag,
                )
            }
        } else {
            val existing = merged[existingIndex]
            val nextQuantity = (existing.quantity + change.quantityDelta).coerceAtLeast(0)
            if (nextQuantity == 0) {
                merged.removeAt(existingIndex)
            } else {
                merged[existingIndex] = existing.copy(
                    quantity = nextQuantity.coerceAtMost(999),
                    detail = change.detail.ifBlank { existing.detail },
                    tag = change.tag.ifBlank { existing.tag },
                )
            }
        }
    }
    return merged.takeLast(90)
}

/** Apply writer-owned local consequences only when the expensive director was skipped. */
internal fun applyApocalypseSceneOutcomeV5(
    save: ApocalypseV3Save,
    plannedBeat: ApocalypseV3Beat,
    outcome: ApocalypseSceneOutcomeV5,
    usedDirector: Boolean,
    party: List<CharacterSettings>,
): ApocalypseV3Beat {
    if (usedDirector) {
        val expandedDossiers = expandApocalypseSceneCastV5(
            base = plannedBeat.nextDirector.characterDossiers,
            outcome = outcome,
            party = party,
            location = outcome.delta.location.ifBlank { plannedBeat.nextDirector.location },
            scene = save.scene + 1,
        )
        val expandedDirector = plannedBeat.nextDirector.copy(characterDossiers = expandedDossiers)
        val validIds = validApocalypsePresentIdsV5(expandedDirector, party)
        val present = normalizeApocalypseCharacterRefsV5(
            values = outcome.delta.presentCharacterIds,
            party = party,
            dossiers = expandedDossiers,
            presentCharacterIds = plannedBeat.nextDirector.presentCharacterIds,
        ).filter(validIds::contains)
        val elapsed = resolveApocalypseElapsedMinutesV5(save, outcome, plannedBeat.minutesPassed)
        val absoluteMinutes = save.director.clockMinutes + elapsed
        val nextDayIndex = (save.director.dayIndex + absoluteMinutes / 1440).coerceAtMost(9999)
        val patchedDossiers = mergeApocalypseCharacterStatePatchesV5(
            previous = expandedDossiers,
            patches = normalizeApocalypseCharacterStatePatchesV5(
                patches = outcome.characterStatePatches,
                party = party,
                dossiers = expandedDossiers,
                presentCharacterIds = plannedBeat.nextDirector.presentCharacterIds,
            ).filter { it.id in validIds },
            scene = save.scene + 1,
        )
        val finalFacts = sanitizePrematureWorldFactsV5(
            nextDayIndex,
            mergeApocalypseWorldFactsV5(
                plannedBeat.nextDirector.worldFacts,
                outcome.delta.worldFactsAdd,
            ),
        )
        return plannedBeat.copy(
            nextDirector = plannedBeat.nextDirector.copy(
                phase = apocalypsePhaseForDayV5(nextDayIndex),
                location = outcome.delta.location.ifBlank { plannedBeat.nextDirector.location },
                sceneGoal = outcome.delta.sceneGoal.ifBlank { plannedBeat.nextDirector.sceneGoal },
                worldFacts = finalFacts,
                characterArcs = (plannedBeat.nextDirector.characterArcs + outcome.delta.characterStateAdds)
                    .filter(String::isNotBlank)
                    .distinct()
                    .takeLast(14),
                storyThreads = mergeApocalypseStoryThreadsV5(
                    plannedBeat.nextDirector.storyThreads,
                    outcome.storyThreadUpdates,
                ),
                characterDossiers = patchedDossiers,
                foreshadowLedger = mergeApocalypseForeshadowPatchesV5(
                    plannedBeat.nextDirector.foreshadowLedger,
                    outcome.foreshadowPatches,
                    save.scene + 1,
                ),
                assets = if (
                    "discoverAssets" in outcome.reportedStateFields ||
                    "inventoryChanges" in outcome.reportedStateFields
                ) {
                    mergeApocalypseAcquiredAssetsV5(save.director.assets, outcome.delta.discoverAssets, outcome.inventoryChanges)
                } else {
                    plannedBeat.nextDirector.assets
                },
                recentBeatTypes = (plannedBeat.nextDirector.recentBeatTypes + outcome.delta.beatType)
                    .filter(String::isNotBlank)
                    .takeLast(8),
                recentEmotionalTurns = (plannedBeat.nextDirector.recentEmotionalTurns + outcome.delta.emotionalTurn)
                    .filter(String::isNotBlank)
                    .takeLast(8),
                weather = outcome.delta.weather.ifBlank { plannedBeat.nextDirector.weather },
                temperatureC = outcome.delta.temperatureC ?: plannedBeat.nextDirector.temperatureC,
                presentCharacterIds = if (outcome.presentCharactersReported) {
                    present
                } else {
                    plannedBeat.nextDirector.presentCharacterIds
                },
                presentCharacterStateKnown = outcome.presentCharactersReported ||
                    plannedBeat.nextDirector.presentCharacterStateKnown,
                // This scene already received a director pass. Do not let the writer immediately
                // schedule another one and recreate a director-every-scene loop.
                directorRefreshNeeded = false,
                dayIndex = nextDayIndex,
                clockMinutes = absoluteMinutes % 1440,
            ),
            moneyDelta = resolveApocalypseStateDeltaV5(outcome, "moneyDelta", "moneyAfter", outcome.delta.moneyDelta, outcome.delta.moneyAfter, save.stats.money, plannedBeat.moneyDelta).coerceIn(-save.stats.money, 1_000_000),
            foodDelta = resolveApocalypseStateDeltaV5(outcome, "foodDelta", "foodAfter", outcome.delta.foodDelta, outcome.delta.foodAfter, save.stats.food, inferredApocalypseAssetDeltaV5(outcome, ApocalypseV3AssetKind.Food) ?: plannedBeat.foodDelta).coerceIn(-save.stats.food, 999),
            waterDelta = resolveApocalypseStateDeltaV5(outcome, "waterDelta", "waterAfter", outcome.delta.waterDelta, outcome.delta.waterAfter, save.stats.water, inferredApocalypseAssetDeltaV5(outcome, ApocalypseV3AssetKind.Water) ?: plannedBeat.waterDelta).coerceIn(-save.stats.water, 999),
            medicineDelta = resolveApocalypseStateDeltaV5(outcome, "medicineDelta", "medicineAfter", outcome.delta.medicineDelta, outcome.delta.medicineAfter, save.stats.medicine, inferredApocalypseAssetDeltaV5(outcome, ApocalypseV3AssetKind.Medicine) ?: plannedBeat.medicineDelta).coerceIn(-save.stats.medicine, 999),
            materialsDelta = resolveApocalypseStateDeltaV5(outcome, "materialsDelta", "materialsAfter", outcome.delta.materialsDelta, outcome.delta.materialsAfter, save.stats.materials, inferredApocalypseAssetDeltaV5(outcome, ApocalypseV3AssetKind.Material) ?: plannedBeat.materialsDelta).coerceIn(-save.stats.materials, 999),
            coresFound = resolveApocalypseStateDeltaV5(outcome, "coresFound", "coresAfter", outcome.delta.coresFound, outcome.delta.coresAfter, save.stats.crystalCores, inferredApocalypseAssetDeltaV5(outcome, ApocalypseV3AssetKind.Core) ?: plannedBeat.coresFound).coerceIn(-save.stats.crystalCores, 99),
            playerAbilityXpGain = if ("playerAbilityXpGain" in outcome.reportedStateFields) outcome.delta.playerAbilityXpGain.coerceIn(0, 10) else plannedBeat.playerAbilityXpGain,
            baseDelta = if ("baseDelta" in outcome.reportedStateFields) outcome.delta.baseDelta.coerceIn(-1, 1) else plannedBeat.baseDelta,
            healthDelta = resolveApocalypseStateDeltaV5(outcome, "healthDelta", "healthAfter", outcome.delta.healthDelta, outcome.delta.healthAfter, save.stats.health, plannedBeat.healthDelta).coerceIn(-35, 20),
            staminaDelta = resolveApocalypseStateDeltaV5(outcome, "staminaDelta", "staminaAfter", outcome.delta.staminaDelta, outcome.delta.staminaAfter, save.stats.stamina, plannedBeat.staminaDelta).coerceIn(-45, 40),
            infectionDelta = resolveApocalypseStateDeltaV5(outcome, "infectionDelta", "infectionAfter", outcome.delta.infectionDelta, outcome.delta.infectionAfter, save.stats.infection, plannedBeat.infectionDelta).coerceIn(-15, 30),
            moraleDelta = resolveApocalypseStateDeltaV5(outcome, "moraleDelta", "moraleAfter", outcome.delta.moraleDelta, outcome.delta.moraleAfter, save.stats.morale, plannedBeat.moraleDelta).coerceIn(-30, 30),
            minutesPassed = elapsed,
        )
    }

    val delta = outcome.delta
    val elapsed = resolveApocalypseElapsedMinutesV5(save, outcome, plannedBeat.minutesPassed)
    val absoluteMinutes = save.director.clockMinutes + elapsed
    val nextDayIndex = (save.director.dayIndex + absoluteMinutes / 1440).coerceAtMost(9999)
    val expandedDossiers = expandApocalypseSceneCastV5(
        base = save.director.characterDossiers,
        outcome = outcome,
        party = party,
        location = delta.location.ifBlank { save.director.location },
        scene = save.scene + 1,
    )
    val expandedDirector = save.director.copy(characterDossiers = expandedDossiers)
    val validIds = validApocalypsePresentIdsV5(expandedDirector, party)
    val nextPresent = normalizeApocalypseCharacterRefsV5(
        values = delta.presentCharacterIds,
        party = party,
        dossiers = expandedDossiers,
        presentCharacterIds = save.director.presentCharacterIds,
    ).filter(validIds::contains).distinct().take(10)
    val patchedDossiers = mergeApocalypseCharacterStatePatchesV5(
        previous = expandedDossiers,
        patches = normalizeApocalypseCharacterStatePatchesV5(
            patches = outcome.characterStatePatches,
            party = party,
            dossiers = expandedDossiers,
            presentCharacterIds = save.director.presentCharacterIds,
        ).filter { it.id in validIds },
        scene = save.scene + 1,
    )
    val facts = sanitizePrematureWorldFactsV5(
        nextDayIndex,
        mergeApocalypseWorldFactsV5(save.director.worldFacts, delta.worldFactsAdd),
    )
    val nextDirector = save.director.copy(
        phase = apocalypsePhaseForDayV5(nextDayIndex),
        location = delta.location.ifBlank { save.director.location },
        sceneGoal = delta.sceneGoal.ifBlank { plannedBeat.nextDirector.sceneGoal },
        worldFacts = facts,
        characterArcs = (save.director.characterArcs + delta.characterStateAdds)
            .filter(String::isNotBlank)
            .distinct()
            .takeLast(14),
        storyThreads = mergeApocalypseStoryThreadsV5(save.director.storyThreads, outcome.storyThreadUpdates),
        characterDossiers = patchedDossiers,
        foreshadowLedger = mergeApocalypseForeshadowPatchesV5(
            save.director.foreshadowLedger,
            outcome.foreshadowPatches,
            save.scene + 1,
        ),
        assets = mergeApocalypseAcquiredAssetsV5(save.director.assets, delta.discoverAssets, outcome.inventoryChanges),
        recentBeatTypes = (save.director.recentBeatTypes + delta.beatType).takeLast(8),
        recentEmotionalTurns = (save.director.recentEmotionalTurns + delta.emotionalTurn)
            .filter(String::isNotBlank)
            .takeLast(8),
        presentCharacterIds = if (outcome.presentCharactersReported) {
            nextPresent
        } else {
            if (save.director.presentCharacterStateKnown) save.director.presentCharacterIds else party.map { it.characterId }
        },
        presentCharacterStateKnown = outcome.presentCharactersReported || save.director.presentCharacterStateKnown,
        directorRefreshNeeded = outcome.directorRefreshNeeded,
        dayIndex = nextDayIndex,
        clockMinutes = absoluteMinutes % 1440,
        weather = delta.weather.ifBlank { save.director.weather },
        temperatureC = delta.temperatureC ?: save.director.temperatureC,
    )
    return plannedBeat.copy(
        nextDirector = nextDirector,
        beatType = delta.beatType,
        worldDelta = outcome.actionOutcome,
        emotionalTurn = delta.emotionalTurn,
        moneyDelta = resolveApocalypseStateDeltaV5(outcome, "moneyDelta", "moneyAfter", delta.moneyDelta, delta.moneyAfter, save.stats.money, 0).coerceIn(-save.stats.money, 1_000_000),
        foodDelta = resolveApocalypseStateDeltaV5(outcome, "foodDelta", "foodAfter", delta.foodDelta, delta.foodAfter, save.stats.food, inferredApocalypseAssetDeltaV5(outcome, ApocalypseV3AssetKind.Food) ?: 0).coerceIn(-save.stats.food, 999),
        waterDelta = resolveApocalypseStateDeltaV5(outcome, "waterDelta", "waterAfter", delta.waterDelta, delta.waterAfter, save.stats.water, inferredApocalypseAssetDeltaV5(outcome, ApocalypseV3AssetKind.Water) ?: 0).coerceIn(-save.stats.water, 999),
        medicineDelta = resolveApocalypseStateDeltaV5(outcome, "medicineDelta", "medicineAfter", delta.medicineDelta, delta.medicineAfter, save.stats.medicine, inferredApocalypseAssetDeltaV5(outcome, ApocalypseV3AssetKind.Medicine) ?: 0).coerceIn(-save.stats.medicine, 999),
        materialsDelta = resolveApocalypseStateDeltaV5(outcome, "materialsDelta", "materialsAfter", delta.materialsDelta, delta.materialsAfter, save.stats.materials, inferredApocalypseAssetDeltaV5(outcome, ApocalypseV3AssetKind.Material) ?: 0).coerceIn(-save.stats.materials, 999),
        coresFound = resolveApocalypseStateDeltaV5(outcome, "coresFound", "coresAfter", delta.coresFound, delta.coresAfter, save.stats.crystalCores, inferredApocalypseAssetDeltaV5(outcome, ApocalypseV3AssetKind.Core) ?: 0).coerceIn(-save.stats.crystalCores, 99),
        playerAbilityXpGain = if ("playerAbilityXpGain" in outcome.reportedStateFields) delta.playerAbilityXpGain.coerceIn(0, 10) else 0,
        baseDelta = if ("baseDelta" in outcome.reportedStateFields) delta.baseDelta.coerceIn(-1, 1) else 0,
        healthDelta = resolveApocalypseStateDeltaV5(outcome, "healthDelta", "healthAfter", delta.healthDelta, delta.healthAfter, save.stats.health, 0).coerceIn(-35, 20),
        staminaDelta = resolveApocalypseStateDeltaV5(outcome, "staminaDelta", "staminaAfter", delta.staminaDelta, delta.staminaAfter, save.stats.stamina, 0).coerceIn(-45, 40),
        infectionDelta = resolveApocalypseStateDeltaV5(outcome, "infectionDelta", "infectionAfter", delta.infectionDelta, delta.infectionAfter, save.stats.infection, 0).coerceIn(-15, 30),
        moraleDelta = resolveApocalypseStateDeltaV5(outcome, "moraleDelta", "moraleAfter", delta.moraleDelta, delta.moraleAfter, save.stats.morale, 0).coerceIn(-30, 30),
        minutesPassed = elapsed,
    )
}

internal fun compactApocalypseSceneExcerptV5(text: String, edgeChars: Int = 220): String {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.length <= edgeChars * 2 + 1) return clean
    return clean.take(edgeChars) + "…" + clean.takeLast(edgeChars)
}

private fun validApocalypsePresentIdsV5(
    director: ApocalypseV3Director,
    party: List<CharacterSettings>,
): Set<String> = (party.map { it.characterId } + director.characterDossiers.map { it.id }).toSet()

private fun expandApocalypseSceneCastV5(
    base: List<ApocalypseCharacterDossierV5>,
    outcome: ApocalypseSceneOutcomeV5,
    party: List<CharacterSettings>,
    location: String,
    scene: Int,
): List<ApocalypseCharacterDossierV5> {
    val reported = outcome.castUpdates.map { update ->
        update.copy(
            currentLocation = update.currentLocation.ifBlank { location },
            lastAdvancedScene = update.lastAdvancedScene.takeIf { it > 0 } ?: scene,
            lastSeenScene = update.lastSeenScene.takeIf { it > 0 } ?: scene,
        )
    }
    val withReported = mergeApocalypseCharacterDossiersV5(base, reported, party)
    val recoveredFromTags = synthesizeApocalypseSpeakerDossiersV5(
        text = outcome.text,
        party = party,
        existing = withReported,
        location = location,
        scene = scene,
    )
    return mergeApocalypseCharacterDossiersV5(withReported, recoveredFromTags, party)
}

private fun normalizeApocalypseCharacterRefsV5(
    values: List<String>,
    party: List<CharacterSettings>,
    dossiers: List<ApocalypseCharacterDossierV5>,
    presentCharacterIds: List<String>,
): List<String> = values.mapNotNull { raw ->
    resolveApocalypseSpeakerTokenV5(
        rawToken = raw,
        party = party,
        dossiers = dossiers,
        presentCharacterIds = presentCharacterIds,
    ).characterId
}.distinct()

private fun normalizeApocalypseCharacterStatePatchesV5(
    patches: List<ApocalypseCharacterStatePatchV5>,
    party: List<CharacterSettings>,
    dossiers: List<ApocalypseCharacterDossierV5>,
    presentCharacterIds: List<String>,
): List<ApocalypseCharacterStatePatchV5> = patches.mapNotNull { patch ->
    val id = resolveApocalypseSpeakerTokenV5(
        rawToken = patch.id,
        party = party,
        dossiers = dossiers,
        presentCharacterIds = presentCharacterIds,
    ).characterId ?: return@mapNotNull null
    patch.copy(id = id)
}.distinctBy { it.id }

private fun JSONArray?.sceneStringsV5(): List<String> = buildList {
    val array = this@sceneStringsV5 ?: return@buildList
    for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun <T> JSONArray?.sceneObjectsV5(mapper: (JSONObject) -> T): List<T> = buildList {
    val array = this@sceneObjectsV5 ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { item -> runCatching { mapper(item) }.getOrNull()?.let(::add) }
    }
}

private fun parseSceneAssetKindV5(raw: String): ApocalypseV3AssetKind = when (raw.lowercase()) {
    "food" -> ApocalypseV3AssetKind.Food
    "water" -> ApocalypseV3AssetKind.Water
    "medicine" -> ApocalypseV3AssetKind.Medicine
    "material" -> ApocalypseV3AssetKind.Material
    "tool", "item" -> ApocalypseV3AssetKind.Tool
    "weapon" -> ApocalypseV3AssetKind.Weapon
    "vehicle" -> ApocalypseV3AssetKind.Vehicle
    "key" -> ApocalypseV3AssetKind.Key
    "document" -> ApocalypseV3AssetKind.Document
    "map" -> ApocalypseV3AssetKind.Map
    "core" -> ApocalypseV3AssetKind.Core
    else -> ApocalypseV3AssetKind.Clue
}
