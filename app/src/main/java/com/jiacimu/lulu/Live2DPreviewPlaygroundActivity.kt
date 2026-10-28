package com.jiacimu.lulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import com.jiacimu.lulu.data.MigratedDomainStores
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Standalone launcher entry for the motion/expression playground, so the
 * user can open it from the desktop without going through a video call.
 * With one modeled character it opens that character directly; otherwise
 * it lists every character that already has a model bound.
 */
class Live2DPreviewPlaygroundActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = intent.getStringExtra(EXTRA_CHARACTER_ID)
        setContent {
            MaterialTheme {
                var selected by remember { mutableStateOf(requested) }
                // Choose the REAL character before importing a skin. The old
                // "lulu" fallback put assets under a different character than
                // the one being called, making calibrated and video skins diverge.
                val characterSettings by MigratedDomainStores.characters.settings.collectAsState()
                val modelRevision by Live2DAssets.revisions.collectAsState()
                val modeled = remember(modelRevision) { Live2DAssets.listModeledCharacters(this) }
                val choices = (characterSettings.values.map { it.characterId to it.displayName } +
                    modeled).distinctBy { it.first }.sortedBy { it.second }
                val resolved = selected ?: choices.singleOrNull()?.first
                if (resolved != null) {
                    Live2DPreviewPlaygroundContent(resolved) { finish() }
                } else {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("选择角色 · 上传及校准 Live2D", fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        if (choices.isEmpty()) {
                            Text("请先创建角色，再在这里上传和测试他的模型。", fontSize = 13.sp)
                        } else {
                            for ((id, label) in choices) {
                                Button(onClick = { selected = id }) {
                                    Text(label + if (modeled.any { it.first == id }) " · 已上传" else " · 未上传",
                                        fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_CHARACTER_ID = "character_id"
    }
}
