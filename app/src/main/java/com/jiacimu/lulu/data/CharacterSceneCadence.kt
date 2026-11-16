package com.jiacimu.lulu.data

/**
 * One PERSON, several clocks. These are scheduling/energy rules only:
 * they never choose a character's emotion, words, activity, or tool.
 *
 * All original-world actor decisions still enter CompanionModelGateway Full
 * with the same memory sections, working-set limits and causal protocol.
 * A scene may supply a new observation or a different communication medium.
 * A perception opportunity is NOT a mandatory spoken response.
 *
 * Do not use this to trigger a paid model request for each animation frame,
 * planned game tile or microphone VAD partial.
 */
internal object CharacterSceneCadence {
    /** User messages wake immediately; quiet foreground chat remains alive for a window. */
    const val PRIVATE_ONLINE_PULSE_SECONDS = 300

    /** A connected phone can think again during genuine silence without speaking. */
    const val PHONE_MIN_USER_SILENCE_MS = 60_000L
    const val PHONE_MIN_AUDIO_GAP_MS = 45_000L
    const val PHONE_INITIAL_REVIEW_MS = 2 * 60_000L
    const val PHONE_LONG_SILENCE_REVIEW_MS = 5 * 60_000L
    const val PHONE_FAILURE_BACKOFF_MS = 10 * 60_000L

    /**
     * Game actors reconsider on a real changed turn or committed player
     * speech. An already selected multi-step physical plan advances locally
     * at this cadence; it never purchases a new model decision per tile.
     */
    const val GAME_PLANNED_STEP_MS = 6_000L

    /**
     * A foreground meeting observes physical touch locally (~1.7 s) and
     * offers occasional unprompted character decisions without a fabricated
     * user message. Model chooses action, thought, speech OR silence.
     * Leave time for the user's own typing and in-flight model requests.
     */
    const val MEETING_INITIAL_IDLE_MS = 9_000L
    const val MEETING_QUIET_DECISION_MS = 60_000L
}
