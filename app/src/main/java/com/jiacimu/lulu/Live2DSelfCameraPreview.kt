package com.jiacimu.lulu

import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Explicitly opt-in LOCAL camera preview for the user's side of a video call.
 * Do not attach ImageAnalysis, ImageCapture, files or any network / AI upload.
 * Releasing the view or switching voice-only mode unbinds the camera.
 */
@Composable
internal fun Live2DSelfCameraPreview(onFailure: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(context, previewView, lifecycleOwner) {
        var disposed = false
        var boundPreview: Preview? = null
        var provider: ProcessCameraProvider? = null
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) {
                    runCatching {
                        val readyProvider = future.get()
                        val preview = Preview.Builder().build()
                        preview.surfaceProvider = previewView.surfaceProvider
                        readyProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview,
                        )
                        provider = readyProvider
                        boundPreview = preview
                    }.onFailure {
                        onFailure("前置摄像头无法开启，请检查权限或是否被其他应用占用")
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        } else {
            onFailure("没有摄像头权限；本机预览已关闭")
        }
        onDispose {
            disposed = true
            val preview = boundPreview
            val readyProvider = provider
            if (preview != null && readyProvider != null) readyProvider.unbind(preview)
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}
