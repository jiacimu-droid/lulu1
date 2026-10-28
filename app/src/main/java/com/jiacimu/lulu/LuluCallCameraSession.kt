package com.jiacimu.lulu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.jiacimu.lulu.ai.VisionModelService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CameraX and 45-second still-frame sharing belong to the ongoing PHONE SERVICE,
 * not the disposable full-screen Composable. Leaving the video page merely
 * detaches its Preview surface. No images are retained on disk.
 *
 * Opt-in is explicit and scoped to one real call. A hangup or camera-off action
 * releases every use case and invalidates in-flight observations.
 */
internal object LuluCallCameraSession {
    data class ViewState(
        val enabled: Boolean = false,
        val sharing: Boolean = false,
        val status: String = "",
        val error: String = "",
        val characterId: String = "",
        val callId: String = "",
    )

    private val mutable = MutableStateFlow(ViewState())
    val state = mutable.asStateFlow()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var application: Context? = null
    private var owner: LifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var preview: Preview? = null
    private var previewView: PreviewView? = null
    private var captureTimer: Job? = null
    private var analysis: Job? = null
    private var capturePending = false
    private var shareRevision = 0L
    private var generation: Long = 0L

    fun requestOpen(context: Context, characterId: String, callId: String) {
        if (characterId.isBlank() || callId.isBlank() ||
            !LuluVoiceCallSession.state.value.connected ||
            LuluVoiceCallSession.state.value.callExperienceId != callId) {
            mutable.value = mutable.value.copy(error = "通话接通后才能持续打开摄像头")
            return
        }
        // Invoked from the visible call UI; Android 14+ does not permit
        // starting a camera foreground service later from arbitrary background work.
        context.startService(Intent(context, LuluVoiceCallService::class.java)
            .setAction(LuluVoiceCallService.ACTION_CAMERA_ON)
            .putExtra(LuluVoiceCallService.EXTRA_CHARACTER_ID, characterId)
            .putExtra(LuluVoiceCallService.EXTRA_CALL_ID, callId))
    }

    fun requestClose(context: Context) {
        context.startService(Intent(context, LuluVoiceCallService::class.java)
            .setAction(LuluVoiceCallService.ACTION_CAMERA_OFF))
    }

    /** Only the already-running call foreground service may own capture. */
    fun openInService(context: Context, lifecycleOwner: LifecycleOwner,
        characterId: String, callId: String) {
        val call = LuluVoiceCallSession.state.value
        if (!call.connected || call.callExperienceId != callId ||
            call.characterId != characterId ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
            mutable.value = mutable.value.copy(error = "摄像头权限或通话会话已失效")
            return
        }
        if (mutable.value.enabled && mutable.value.characterId == characterId &&
            mutable.value.callId == callId && owner === lifecycleOwner) return
        closeInService()
        val token = ++generation
        application = context.applicationContext
        owner = lifecycleOwner
        mutable.value = ViewState(enabled = true, characterId = characterId,
            callId = callId, status = "摄像头正在启动")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (token != generation || !mutable.value.enabled ||
                owner !== lifecycleOwner) return@addListener
            runCatching {
                val provider = future.get()
                val photo = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(640, 480)).build()
                provider.bindToLifecycle(lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA, photo)
                cameraProvider = provider
                capture = photo
                attachPreviewIfPossible()
                mutable.value = mutable.value.copy(status = "摄像头已开启", error = "")
            }.onFailure { fail("前置摄像头打开失败：" +
                it.message.orEmpty().take(90)) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun attachPreview(view: PreviewView) {
        previewView = view
        attachPreviewIfPossible()
    }

    fun detachPreview(view: PreviewView) {
        if (previewView !== view) return
        previewView = null
        preview?.let { cameraProvider?.unbind(it) }
        preview = null
        // Do NOT unbind ImageCapture: phone service still owns it.
    }

    private fun attachPreviewIfPossible() {
        val provider = cameraProvider ?: return
        val lifecycleOwner = owner ?: return
        val view = previewView ?: return
        preview?.let { provider.unbind(it) }
        runCatching {
            val display = Preview.Builder().build()
            display.setSurfaceProvider(view.surfaceProvider)
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, display)
            preview = display
        }.onFailure { mutable.value = mutable.value.copy(
            error = "本机预览暂不可用：" + it.message.orEmpty().take(80)) }
    }

    fun setSharing(enabled: Boolean) {
        val snapshot = mutable.value
        val app = application
        if (enabled && (!snapshot.enabled || app == null ||
                !LuluVoiceCallSession.state.value.connected)) {
            mutable.value = snapshot.copy(error = "请先接通视频电话并打开摄像头")
            return
        }
        captureTimer?.cancel()
        captureTimer = null
        shareRevision++
        analysis?.cancel()
        analysis = null
        if (snapshot.sharing) Live2DCallVisionState.disable(snapshot.characterId, snapshot.callId)
        if (!enabled) {
            mutable.value = snapshot.copy(sharing = false, status = "已停止画面共享")
            return
        }
        Live2DCallVisionState.enable(snapshot.characterId, snapshot.callId)
        mutable.value = snapshot.copy(sharing = true, status = "共享已开启，正在获取画面", error = "")
        captureTimer = work.launch {
            while (isActive && mutable.value.sharing) {
                if (capture == null) delay(500L)
                else {
                    captureFrame()
                    delay(45_000L)
                }
            }
        }
    }

    fun captureNow() {
        if (mutable.value.sharing) captureFrame()
    }

    private fun captureFrame() {
        val app = application ?: return
        val photo = capture ?: return
        val snapshot = mutable.value
        if (!snapshot.enabled || !snapshot.sharing || capturePending ||
            analysis?.isActive == true) return
        val token = generation
        val revision = shareRevision
        capturePending = true
        photo.takePicture(ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var bitmap: Bitmap? = null
                    try {
                        if (token != generation || revision != shareRevision ||
                            !mutable.value.sharing) return
                        bitmap = image.toBitmap()
                        val degrees = image.imageInfo.rotationDegrees
                        if (degrees != 0) {
                            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                            val source = checkNotNull(bitmap)
                            val rotated = Bitmap.createBitmap(source, 0, 0,
                                source.width, source.height, matrix, true)
                            if (rotated !== source) source.recycle()
                            bitmap = rotated
                        }
                        val frame = Live2DCallVisionState.beginFrame(
                            snapshot.characterId, snapshot.callId) ?: return
                        val owned = checkNotNull(bitmap)
                        bitmap = null
                        mutable.value = mutable.value.copy(status = "正在理解最近一帧", error = "")
                        analysis = work.launch {
                            try {
                                val result = VisionModelService.describeCameraFrame(
                                    app, owned, snapshot.characterId)
                                if (token != generation ||
                                    !Live2DCallVisionState.isCurrent(frame)) return@launch
                                mutable.value = if (result.isSuccess &&
                                    Live2DCallVisionState.accept(frame, result.getOrThrow())) {
                                    mutable.value.copy(status = "画面已理解，可用于下一轮通话", error = "")
                                } else {
                                    mutable.value.copy(status = "等待下一帧",
                                        error = result.exceptionOrNull()?.message.orEmpty().take(140))
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } finally {
                                owned.recycle()
                            }
                        }
                    } catch (error: Exception) {
                        mutable.value = mutable.value.copy(
                            error = "取帧失败：" + error.message.orEmpty().take(90))
                    } finally {
                        bitmap?.recycle()
                        image.close()
                        if (token == generation && revision == shareRevision) capturePending = false
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    if (token == generation && revision == shareRevision) {
                        capturePending = false
                        mutable.value = mutable.value.copy(
                            error = "取帧失败：" + exception.message.orEmpty().take(90))
                    }
                }
            })
    }

    fun closeInService() {
        generation++
        shareRevision++
        capturePending = false
        captureTimer?.cancel(); captureTimer = null
        analysis?.cancel(); analysis = null
        val old = mutable.value
        if (old.callId.isNotBlank()) {
            Live2DCallVisionState.disable(old.characterId, old.callId)
        }
        preview?.let { cameraProvider?.unbind(it) }
        capture?.let { cameraProvider?.unbind(it) }
        preview = null
        capture = null
        cameraProvider = null
        owner = null
        application = null
        mutable.value = ViewState()
    }

    private fun fail(message: String) {
        closeInService()
        mutable.value = mutable.value.copy(error = message)
    }
}
