package com.jiacimu.lulu

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Only a disposable VIEW of the ongoing call-owned camera.
 * Closing/minimizing the video page removes this preview surface, not CameraX
 * capture or the consented 45-second observation loop in the foreground service.
 */
@Composable
internal fun Live2DSelfCameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(previewView) {
        LuluCallCameraSession.attachPreview(previewView)
        onDispose { LuluCallCameraSession.detachPreview(previewView) }
    }
    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}
