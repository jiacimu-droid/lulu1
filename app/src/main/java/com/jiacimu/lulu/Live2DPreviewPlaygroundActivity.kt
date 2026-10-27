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
                val modeled = remember { Live2DAssets.listModeledCharacters(this) }
                val resolved = selected ?: modeled.singleOrNull()?.first
                if (resolved != null) {
                    Live2DPreviewPlaygroundContent(resolved) { finish() }
                } else {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                    ) {
                        Text("选择要试验的角色", fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        if (modeled.isEmpty()) {
                            Text(
                                "还没有导入任何 Live2D 模型，直接在这里给主角导一个就行。",
                                fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { selected = "lulu" }) {
                                Text("给主角导入皮套", fontSize = 13.sp)
                            }
                        } else {
                            for ((id, label) in modeled) {
                                Button(onClick = { selected = id }) {
                                    Text(label, fontSize = 13.sp)
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
