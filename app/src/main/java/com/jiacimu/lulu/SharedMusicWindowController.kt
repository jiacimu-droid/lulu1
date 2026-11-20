package com.jiacimu.lulu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class SharedMusicRoom(
    val conversationId: String,
    val characterId: String,
    val characterName: String,
    val characterAvatarUri: String?,
)

internal enum class SharedMusicWindow { Expanded, Compact, Hidden }

/** One real room owned by the app shell, not by an individual chat route. */
internal object SharedMusicWindowController {
    var room by mutableStateOf<SharedMusicRoom?>(null)
        private set
    var display by mutableStateOf(SharedMusicWindow.Expanded)
        private set
    var requestedTrack by mutableStateOf<SharedListenEntry?>(null)
        private set
    var playbackTitle by mutableStateOf("选择一首歌")
        private set
    var playbackArtist by mutableStateOf("")
        private set
    var playbackPlaying by mutableStateOf(false)
        private set
    var playbackIsExternal by mutableStateOf(true)
        private set

    // Live actions remain attached to the one in-app room, not reimplemented
    // by the outside-app overlay or a second independent MediaPlayer.
    var togglePlayback: (() -> Unit)? = null
    var previousPlayback: (() -> Unit)? = null
    var nextPlayback: (() -> Unit)? = null

    fun updatePlayback(title: String, artist: String, playing: Boolean, external: Boolean) {
        if (playbackTitle != title) playbackTitle = title
        if (playbackArtist != artist) playbackArtist = artist
        if (playbackPlaying != playing) playbackPlaying = playing
        if (playbackIsExternal != external) playbackIsExternal = external
    }

    fun open(target: SharedMusicRoom) {
        if (room?.conversationId != target.conversationId) requestedTrack = null
        room = target
        display = SharedMusicWindow.Expanded
    }
    fun select(target: SharedMusicRoom, entry: SharedListenEntry) {
        if (room?.conversationId != target.conversationId) requestedTrack = null
        room = target
        // Copying gives each explicit tap a new selection, including repeat taps.
        requestedTrack = entry.copy(id = java.util.UUID.randomUUID().toString())
        display = SharedMusicWindow.Expanded
    }
    fun compact() { if (room != null) display = SharedMusicWindow.Compact }
    fun hide() { if (room != null) display = SharedMusicWindow.Hidden }
    fun expand() { if (room != null) display = SharedMusicWindow.Expanded }
    fun close() {
        room = null
        requestedTrack = null
        togglePlayback = null
        previousPlayback = null
        nextPlayback = null
        display = SharedMusicWindow.Hidden
        ListenTogetherSystemOverlay.dismiss()
    }
}
