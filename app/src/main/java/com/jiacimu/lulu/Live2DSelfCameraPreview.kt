package com.jiacimu.lulu

import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Explicitly opt-in LOCAL camera preview for the user's side of a video call.
 * Frame capture is opt-in and triggered explicitly by the parent's
 * captureRequest. No storage or upload occurs inside this view. The caller
 * owns the captured Bitmap and must recycle it after optional analysis.
 * Releasing the view or switching voice-only mode unbinds the camera.
 */
@Composable
internal fun Live2DSelfCameraPreview(
    onFailure: (String) -> Unit,
    modifier: Modifier = Modifier,
    captureRequest: Long = 0L,
    onFrameCaptured: (Bitmap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var captureUseCase by remember(previewView) { mutableStateOf<ImageCapture?>(null) }
    val updatedOnCaptured = androidx.compose.runtime.rememberUpdatedState(onFrameCaptured)
    LaunchedEffect(captureRequest, captureUseCase) {
        val capture = captureUseCase ?: return@LaunchedEffect
        if (captureRequest <= 0L) return@LaunchedEffect
        capture.takePicture(ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        var bitmap = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            val corrected = Bitmap.createBitmap(
                                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            if (corrected !== bitmap) bitmap.recycle()
                            bitmap = corrected
                        }
                        updatedOnCaptured.value(bitmap)
                    } catch (_: Exception) {
                        onFailure("摄像头画面读取失败，已停止本次取帧")
                    } finally {
                        image.close()
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    onFailure("取帧失败，请确认前置摄像头仍然可用")
                }
            })
    }
    DisposableEffect(context, previewView, lifecycleOwner) {
        var disposed = false
        var boundPreview: Preview? = null
        var boundCapture: ImageCapture? = null
        var provider: ProcessCameraProvider? = null
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) {
                    runCatching {
                        val readyProvider = future.get()
                        val preview = Preview.Builder().build()
                        val photo = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetResolution(Size(640, 480))
                            .build()
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        readyProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview, photo,
                        )
                        provider = readyProvider
                        boundPreview = preview
                        boundCapture = photo
                        captureUseCase = photo
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
            captureUseCase = null
            val preview = boundPreview
            val capture = boundCapture
            val readyProvider = provider
            if (readyProvider != null) {
                preview?.let { readyProvider.unbind(it) }
                capture?.let { readyProvider.unbind(it) }
            }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}
