package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CompanionPresenceStore

/** Uses the existing portrait; no placeholder is presented as an imported Live2D model. */
@Composable
internal fun StatefulCharacterPortrait(characterId: String, avatarUri: String?, name: String, size: Int = 92) {
    val avatars by AvatarController.states.collectAsState()
    val presence by CompanionPresenceStore.states.collectAsState()
    val current = avatars[characterId]
    LuluProfileAvatar(avatarUri, name.take(1), size)
    if (current?.speaking == true) {
        LinearProgressIndicator(progress = { current.mouthOpen }, modifier = Modifier.width(size.dp).height(3.dp))
    }
    val mood = presence[characterId]?.mood.orEmpty()
    if (mood.isNotBlank()) Text(mood, style = MaterialTheme.typography.bodySmall)
    if (current?.listening == true) Text("正在听", style = MaterialTheme.typography.labelSmall)
}
