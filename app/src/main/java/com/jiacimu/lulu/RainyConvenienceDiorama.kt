package com.jiacimu.lulu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/**
 * A standalone miniature in the digital world's experimental area.
 * There are no people, purchases, fictional visits or changes to the persistent world.
 * OpenGL ES 2 runs inside Android; no browser, CDN, WebView, or fake turntable sprites.
 */
@Composable
internal fun RainyConvenienceDiorama(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val camera = remember { RainyDioramaOrbit() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // "waiting" becomes "ready" ONLY after a real GL frame. An unsupported GPU
    // surface must never look like a successfully opened but empty world.
    val startup = remember { mutableStateOf("waiting") }
    val renderer = remember(camera) {
        RainyDioramaRenderer(camera) { result ->
            mainHandler.post { startup.value = result }
        }
    }
    val surface = remember(context, renderer) {
        var previousX = 0f
        var previousY = 0f
        var previousSpan = 0f
        var touchStart = 0L
        var lastTap = 0L
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            // Use the same widely supported default RGB/depth config as the
            // already-shipped DigitalWorld3DTrialRoom. Requesting alpha=8 for an
            // opaque SurfaceView can make EGL window-surface creation fail on OEMs.
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        previousX = event.x
                        previousY = event.y
                        touchStart = SystemClock.uptimeMillis()
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                        previousSpan = fingerSpan(event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount >= 2) {
                            val span = fingerSpan(event)
                            if (previousSpan > 1f && span > 1f) camera.pinch(span / previousSpan)
                            previousSpan = span
                        } else {
                            camera.drag(event.x - previousX, event.y - previousY, height.toFloat())
                        }
                        previousX = event.x
                        previousY = event.y
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        previousSpan = 0f
                        val remaining = if (event.actionIndex == 0) 1 else 0
                        if (remaining < event.pointerCount) {
                            previousX = event.getX(remaining)
                            previousY = event.getY(remaining)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val now = SystemClock.uptimeMillis()
                        if (now - touchStart < 220L) {
                            if (now - lastTap < 350L) renderer.replay()
                            lastTap = now
                        }
                    }
                }
                true
            }
        }
    }
    // Only schedule frames while foreground and attached; rendering must stop when the
    // user backgrounds the app, not just when Compose finally disposes this page.
    LaunchedEffect(surface, lifecycleOwner) {
        while (true) {
            if (surface.isAttachedToWindow &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                surface.requestRender()
            }
            delay(33L)
        }
    }
    DisposableEffect(surface, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (surface.isAttachedToWindow) surface.onPause()
                Lifecycle.Event.ON_RESUME -> if (surface.isAttachedToWindow) surface.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (surface.isAttachedToWindow) surface.onPause()
        }
    }
    val timedOut = remember(surface) { mutableStateOf(false) }
    LaunchedEffect(surface) {
        delay(3500L)
        if (startup.value == "waiting") timedOut.value = true
    }
    Box(modifier.fillMaxSize().background(Color(0xFF09121D))) {
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        if (startup.value != "ready" && (timedOut.value || startup.value != "waiting")) {
            val message = if (startup.value == "waiting")
                "3D 渲染未启动。请确认设备支持 OpenGL ES 2.0，重新进入或查看应用日志。"
            else "3D 渲染失败：${startup.value}"
            Text(
                message,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xED182736))
                    .padding(18.dp),
            )
        }
    }
}

private fun fingerSpan(event: MotionEvent): Float {
    val x = event.getX(0) - event.getX(1)
    val y = event.getY(0) - event.getY(1)
    return sqrt(x * x + y * y)
}

/** Shared, bounded orbit gesture math: separable from any actual game rules. */
internal class RainyDioramaOrbit {
    @Volatile var azimuth = 0.67f
        private set
    @Volatile var elevation = 0.62f
        private set
    @Volatile var radius = 17.8f
        private set

    fun drag(dx: Float, dy: Float, viewportHeight: Float) {
        val scale = max(200f, viewportHeight)
        azimuth += dx / scale * 2.5f
        elevation = (elevation + dy / scale * 1.75f).coerceIn(0.16f, 1.13f)
    }

    fun pinch(scale: Float) {
        if (scale.isFinite() && scale > 0.01f) {
            radius = (radius / scale).coerceIn(10f, 27f)
        }
    }
}

private enum class DioramaMesh { BOX, CYLINDER, RING }

private data class DioramaPart(
    val mesh: DioramaMesh,
    val x: Float, val y: Float, val z: Float,
    val sx: Float, val sy: Float, val sz: Float,
    val color: Int, val stage: Int,
    val glow: Float = 0f, val alpha: Float = 1f,
    val rz: Float = 0f, val rx: Float = 0f, val ry: Float = 0f,
)

private class RainyDioramaRenderer(
    private val camera: RainyDioramaOrbit,
    private val reportStartup: (String) -> Unit,
) : GLSurfaceView.Renderer {
    private var glFailed = false
    private var firstFrameReported = false

    private fun reportError(phase: String, error: Throwable) {
        glFailed = true
        Log.e("RainyDiorama", "OpenGL $phase failed", error)
        reportStartup("$phase: ${error.message ?: error.javaClass.simpleName}")
    }

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val parts = mutableListOf<DioramaPart>()
    private val transparent = mutableListOf<DioramaPart>()
    private val rainData = FloatArray(72 * 12)
    private val rainBuffer = ByteBuffer.allocateDirect(rainData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var program = 0
    private var imageProgram = 0
    private var position = 0
    private var normal = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uGlow = 0
    private var uOpacity = 0
    private var imageMvp = 0
    private var imagePosition = 0
    private var imageUv = 0
    private var signTexture = 0
    private var vendingTexture = 0
    private lateinit var cube: FloatBuffer
    private lateinit var cylinder: FloatBuffer
    private lateinit var ring: FloatBuffer
    private lateinit var panel: FloatBuffer
    private var cubeCount = 0
    private var cylinderCount = 0
    private var ringCount = 0
    @Volatile private var started = SystemClock.elapsedRealtime()

    init { createScene() }

    fun replay() { started = SystemClock.elapsedRealtime() }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try { initializeGl()
        } catch (error: Throwable) { reportError("初始化", error) }
    }

    private fun initializeGl() {
        program = link(
            "uniform mat4 uMvp; uniform mat4 uModel; attribute vec3 aPosition; attribute vec3 aNormal;" +
                "varying vec3 vNormal; void main() { gl_Position = uMvp * vec4(aPosition,1.0);" +
                "vNormal = (uModel * vec4(aNormal,0.0)).xyz; }",
            "precision mediump float; varying vec3 vNormal; uniform vec3 uColor;" +
                "uniform float uGlow; uniform float uOpacity; void main() {" +
                "float d = max(dot(normalize(vNormal),normalize(vec3(-0.4,0.85,0.6))),0.0);" +
                "float band = d > 0.68 ? 1.0 : (d > 0.26 ? 0.82 : 0.65);" +
                "vec3 lit = uColor * (band * 0.92 + uGlow * 0.8);" +
                "gl_FragColor = vec4(lit, uOpacity); }",
        )
        imageProgram = link(
            "uniform mat4 uMvp; attribute vec3 aPosition; attribute vec2 aUv;" +
                "varying vec2 vUv; void main() { gl_Position = uMvp * vec4(aPosition,1.0); vUv=aUv; }",
            "precision mediump float; varying vec2 vUv; uniform sampler2D uTexture;" +
                "void main() { gl_FragColor = texture2D(uTexture,vUv); }",
        )
        position = GLES20.glGetAttribLocation(program, "aPosition")
        normal = GLES20.glGetAttribLocation(program, "aNormal")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uGlow = GLES20.glGetUniformLocation(program, "uGlow")
        uOpacity = GLES20.glGetUniformLocation(program, "uOpacity")
        imageMvp = GLES20.glGetUniformLocation(imageProgram, "uMvp")
        imagePosition = GLES20.glGetAttribLocation(imageProgram, "aPosition")
        imageUv = GLES20.glGetAttribLocation(imageProgram, "aUv")
        val cubeValues = makeCube()
        val cylinderValues = makeCylinder(12)
        val ringValues = makeRing(20)
        cube = buffer(cubeValues); cubeCount = cubeValues.size / 6
        cylinder = buffer(cylinderValues); cylinderCount = cylinderValues.size / 6
        ring = buffer(ringValues); ringCount = ringValues.size / 6
        panel = buffer(floatArrayOf(
            -.5f,-.5f,0f, 0f,1f, .5f,-.5f,0f, 1f,1f, .5f,.5f,0f, 1f,0f,
            -.5f,-.5f,0f, 0f,1f, .5f,.5f,0f, 1f,0f, -.5f,.5f,0f, 0f,0f,
        ))
        signTexture = signTexture()
        vendingTexture = vendingTexture()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(0.032f, 0.049f, 0.090f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 45f, width.toFloat() / max(1, height), 0.1f, 75f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (glFailed) return
        try {
            drawScene()
            if (!firstFrameReported) {
                val error = GLES20.glGetError()
                if (error == GLES20.GL_NO_ERROR) {
                    firstFrameReported = true
                    reportStartup("ready")
                } else {
                    reportError("绘制", IllegalStateException("OpenGL error 0x${error.toString(16)}"))
                }
            }
        } catch (error: Throwable) { reportError("绘制", error) }
    }

    private fun drawScene() {
        val time = SystemClock.elapsedRealtime()
        val elapsed = ((time - started) / 1000f).coerceAtLeast(0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val r = camera.radius
        val elevation = camera.elevation
        val yaw = camera.azimuth
        Matrix.setLookAtM(view, 0,
            sin(yaw) * cos(elevation) * r, sin(elevation) * r + 1.2f,
            cos(yaw) * cos(elevation) * r,
            0f, 1.45f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        GLES20.glUseProgram(program)
        for (part in parts) drawPart(part, elapsed)
        GLES20.glDepthMask(false)
        for (part in transparent) drawPart(part, elapsed)
        if (elapsed > 0.95f) drawRain(time)
        GLES20.glDepthMask(true)
        if (elapsed > 2.45f) {
            drawImage(signTexture, 0.82f, 3.62f, 2.035f, 6.23f, 0.60f)
            drawImage(vendingTexture, -3.31f, 1.13f, 2.971f, 0.85f, 2.05f)
        }
    }

    private fun drawPart(p: DioramaPart, elapsed: Float) {
        val progress = ((elapsed - p.stage * 0.36f) / 0.65f).coerceIn(0f, 1f)
        if (progress <= 0f) return
        val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, p.x, p.y + (1f - eased) * 2.2f, p.z)
        if (p.rz != 0f) Matrix.rotateM(model, 0, p.rz, 0f, 0f, 1f)
        if (p.rx != 0f) Matrix.rotateM(model, 0, p.rx, 1f, 0f, 0f)
        if (p.ry != 0f) Matrix.rotateM(model, 0, p.ry, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, p.sx, p.sy * max(0.04f, eased), p.sz)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        val (buffer, count) = when (p.mesh) {
            DioramaMesh.BOX -> cube to cubeCount
            DioramaMesh.CYLINDER -> cylinder to cylinderCount
            DioramaMesh.RING -> ring to ringCount
        }
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3f(uColor, ((p.color shr 16) and 255) / 255f,
            ((p.color shr 8) and 255) / 255f, (p.color and 255) / 255f)
        GLES20.glUniform1f(uGlow, p.glow)
        GLES20.glUniform1f(uOpacity, p.alpha)
        buffer.position(0)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, buffer)
        GLES20.glEnableVertexAttribArray(position)
        buffer.position(3)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 24, buffer)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
    }

    private fun drawImage(texture: Int, x: Float, y: Float, z: Float, width: Float, height: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.scaleM(model, 0, width, height, 1f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUseProgram(imageProgram)
        GLES20.glUniformMatrix4fv(imageMvp, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(imageProgram, "uTexture"), 0)
        panel.position(0)
        GLES20.glVertexAttribPointer(imagePosition, 3, GLES20.GL_FLOAT, false, 20, panel)
        GLES20.glEnableVertexAttribArray(imagePosition)
        panel.position(3)
        GLES20.glVertexAttribPointer(imageUv, 2, GLES20.GL_FLOAT, false, 20, panel)
        GLES20.glEnableVertexAttribArray(imageUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glUseProgram(program)
    }

    private fun drawRain(time: Long) {
        val t = time / 1000f
        for (i in 0 until 72) {
            val px = (((i * 79) % 131) / 131f - 0.5f) * 12.7f
            val pz = (((i * 47 + 17) % 137) / 137f - 0.5f) * 12.7f
            val py = 0.25f + ((i * 0.167f + t * 3.4f) % 5.0f)
            val start = i * 12
            rainData[start] = px
            rainData[start + 1] = py
            rainData[start + 2] = pz
            rainData[start + 3] = 0f
            rainData[start + 4] = 1f
            rainData[start + 5] = 0f
            rainData[start + 6] = px + 0.06f
            rainData[start + 7] = py - 0.24f
            rainData[start + 8] = pz
            rainData[start + 9] = 0f
            rainData[start + 10] = 1f
            rainData[start + 11] = 0f
        }
        rainBuffer.clear()
        rainBuffer.put(rainData)
        rainBuffer.position(0)
        Matrix.setIdentityM(model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, vp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3f(uColor, 0.62f, 0.78f, 0.93f)
        GLES20.glUniform1f(uGlow, 0.2f)
        GLES20.glUniform1f(uOpacity, 0.48f)
        rainBuffer.position(0)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, rainBuffer)
        rainBuffer.position(3)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 24, rainBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 144)
    }

    private fun add(mesh: DioramaMesh, x: Number, y: Number, z: Number,
        sx: Number, sy: Number, sz: Number, color: Int, stage: Int = 0,
        glow: Float = 0f, alpha: Float = 1f, rz: Float = 0f,
        rx: Float = 0f, ry: Float = 0f
    ) {
        val part = DioramaPart(mesh, x.toFloat(), y.toFloat(), z.toFloat(),
            sx.toFloat(), sy.toFloat(), sz.toFloat(), color, stage, glow, alpha, rz, rx, ry)
        if (alpha < 0.999f) transparent.add(part) else parts.add(part)
    }

    private fun box(x: Number,y: Number,z: Number,sx: Number,sy: Number,sz: Number,
        c: Int, stage: Int = 0, glow: Float = 0f, alpha: Float = 1f,
        rz: Float = 0f, rx: Float = 0f, ry: Float = 0f
    ) = add(DioramaMesh.BOX,x,y,z,sx,sy,sz,c,stage,glow,alpha,rz,rx,ry)

    private fun pole(x: Number,y: Number,z: Number,diameter: Number,height: Number,
        c: Int, stage: Int = 2, glow: Float = 0f, rx: Float = 0f, rz: Float = 0f
    ) = add(DioramaMesh.CYLINDER,x,y,z,diameter,height,diameter,c,stage,glow,rz = rz,rx = rx)

    private fun wheel(x: Number,y: Number,z: Number,diameter: Number,c: Int) =
        add(DioramaMesh.RING,x,y,z,diameter,diameter,diameter,c,2)

    private fun createScene() {
        val navy = 0x162636
        val road = 0x223342
        val sidewalk = 0x5C6974
        val cream = 0xDDDCD4
        val charcoal = 0x172D39
        val warm = 0xFFCF87
        val coral = 0xEA8192
        val cyan = 0x78DEE0
        // Single complete collectible base, with a chamfer-like dark side profile.
        box(0, -0.32, 0, 12.6, 0.63, 12.6, 0x101D2A)
        box(0, 0.015, 0, 12.3, 0.06, 12.3, road)
        box(1.10, 0.12, -1.77, 7.43, 0.20, 8.58, sidewalk, 1)
        box(-4.1, 0.10, -1.65, 0.25, 0.08, 8.25, 0x9299A0, 1)
        box(1.1, 0.10, 2.47, 7.32, 0.08, 0.24, 0x9BA4AC, 1)
        // L-shaped road, zebra crossing and a quieter side street.
        for (i in 0..5) {
            box(-4.45 + i * 0.61, 0.062, 4.17, 0.38, 0.015, 1.00, 0xDDE9ED, 1)
        }
        for (i in 0..4) {
            box(-4.2, 0.069, -4.50 + i * 1.25, 0.035, 0.015, .52, 0xD7CFAB, 1)
        }
        // Puddles and a few sparse warm/cool elongated specular streaks.
        for (i in 0..9) {
            val x = -5.6f + (i * 2.37f) % 11.3f
            val z = 3.14f + (i * 0.76f) % 2.55f
            box(x, 0.060, z, 0.50f + i % 3 * 0.28f, 0.007, 0.12f,
                if (i % 2 == 0) cyan else coral, 1, 0.15f, 0.37f)
        }
        // Gutter, grate openings, and parking marks.
        box(3.64, 0.19, 2.39, 1.38, .02, .14, charcoal, 1)
        for (i in 0..9) box(3.04 + .13 * i, .204, 2.39, .025, .022, .12, 0x89959A, 1)
        for (x in listOf(3.8, 5.15)) {
            box(x, .07, 4.46, .045, .012, 2.0, 0xD8E0E1, 1)
        }
        box(4.46, .07, 5.49, 1.36, .012, .04, 0xD8E0E1, 1)
        box(4.48, .08, 4.6, .42, .012, .045, cyan, 1, .2f, .6f)
        // Store floor and its warm counter / believable merchandise rows.
        box(.77, .22, -1.55, 6.37, .18, 5.87, 0xD6D4C6, 3)
        box(.77, .30, -1.55, 6.25, .018, 5.7, 0xE8E1CE, 3)
        box(.77, 2.02, -4.48, 6.39, 3.66, .17, cream, 3)
        box(3.98, 2.02, -1.6, .17, 3.66, 5.86, cream, 3)
        // Rear refrigerated cases: dark glass fronts and luminous product rows.
        for (j in 0..3) {
            val x = -1.68 + j * 1.43
            box(x, 1.66, -4.18, 1.32, 2.48, .46, 0x9EADB0, 4)
            box(x, 1.65, -3.928, 1.19, 2.3, .016, 0x3B5B65, 4, .15f)
            for (row in 0..3) for (col in 0..4) {
                val h = .81 + row * .50
                val c = listOf(0xE2B897, 0xA7E3E0, 0xEF9FA9, 0xEAD67D, 0xA4B9E1)[(j+row+col)%5]
                box(x - .49 + col * .24, h, -3.884, .16, .30, .10, c, 4, .32f)
            }
        }
        // Two stocked aisles: intentionally low enough to be visible through the windows.
        for (aisle in 0..1) {
            val x = -1.18 + aisle * 2.04
            for (level in 0..2) {
                val y = .65 + level * .52
                box(x, y, -1.56, 1.27, .095, 2.6, 0xB7C4C0, 4)
                for (side in listOf(-1f, 1f)) for (j in 0..7) {
                    val z = -2.66 + j * .305
                    val c = listOf(0xF1AB8D,0xB8DCBC,0x9ABBDD,0xEBCF83,0xD7A6D5)[(j+level+aisle)%5]
                    box(x + side*.42, y+.18, z, .21, .33, .20, c, 4, .11f)
                }
            }
            pole(x-.59, .91, -.33, .06, 1.22, 0x879A9D, 4)
            pole(x+.59, .91, -2.8, .06, 1.22, 0x879A9D, 4)
        }
        // Warm checkout, counter equipment, and basket stacks.
        box(2.71, .89, -.40, 1.56, 1.15, .70, 0xC2A68C, 4)
        box(2.71, 1.49, -.40, 1.65, .11, .76, 0xF6DEBE, 4)
        box(2.42, 1.78, -.53, .43, .46, .12, charcoal, 4)
        box(3.08, 1.62, -.48, .32, .13, .41, 0x485C64, 4)
        for (i in 0..3) box(2.10 + i*.21, 1.61, -.08, .15, .16, .15, coral, 4)
        // Front / corner storefront: posts, open glass bays and automatic doors.
        for (x in listOf(-2.43, -.88, .18, 1.15, 2.58, 3.98)) {
            box(x, 1.88, 1.38, .085, 3.18, .12, charcoal, 3)
        }
        for (z in listOf(-4.4, -2.95, -1.6, .05, 1.38)) {
            box(-2.43, 1.85, z, .12, 3.13, .095, charcoal, 3)
        }
        box(.77, 3.44, 1.38, 6.36, .14, .19, charcoal, 3)
        box(-2.43, 3.44, -1.55, .19, .14, 5.85, charcoal, 3)
        box(.77, .39, 1.41, 6.31, .10, .16, charcoal, 3)
        box(-2.42, .38, -1.5, .16, .10, 5.75, charcoal, 3)
        // Deliberately transparent! See full real geometry rather than a photo of shelves.
        box(-1.65, 1.90, 1.43, 1.45, 2.91, .026, 0x90E9F0, 5, 0f, .14f)
        box(2.57, 1.90, 1.43, 2.72, 2.91, .026, 0x9CE7EA, 5, 0f, .13f)
        box(-2.41, 1.90, -1.53, .026, 2.91, 5.52, 0x9AE3E6, 5, 0f, .11f)
        // A recognisable double automatic glass door with central handles.
        for (x in listOf(.41, .90)) {
            box(x, 1.91, 1.48, .47, 2.83, .023, 0xA4E2E6, 5, 0f, .17f)
            box(x, 1.89, 1.48, .025, 2.82, .055, 0xC7D5DA, 5)
        }
        for (x in listOf(.54, .77)) box(x, 1.69, 1.52, .045, .52, .07, 0xE4E7DE, 5)
        box(.67, .247, 1.93, 1.04, .014, .55, 0x313B49, 5)
        // Deep rain awning and roof reveal with elegant repeating colour stripe.
        box(.77, 3.68, 1.61, 6.47, .19, .72, 0xE4E4DD, 5)
        box(.77, 3.49, 1.98, 6.47, .08, .07, 0x6EDCD9, 5, .65f)
        box(.77, 3.56, 1.98, 6.47, .06, .07, coral, 5, .35f)
        box(.77, 4.10, -1.55, 6.74, .24, 6.23, 0x38495A, 5)
        box(.77, 4.22, -1.55, 6.79, .06, 6.26, 0x81939D, 5)
        box(.77, 4.26, -1.55, 6.38, .02, 5.85, 0x293C49, 5)
        box(.77, 4.27, 1.36, 6.52, .08, .12, cream, 5)
        box(.77, 4.29, 1.43, 6.44, .035, .04, cyan, 5, 1.1f)
        box(.77, 4.32, 1.49, 6.44, .025, .04, coral, 5, 1.2f)
        // Interior ceiling strips visible from low orbit angles.
        for (x in listOf(-1.65, .5, 2.55)) {
            box(x, 3.77, -1.58, .13, .10, 3.8, 0xFFEDC8, 5, 1.1f)
        }
        // Vending machine beside the entrance: front texture gets cans + pricing.
        box(-3.31, 1.20, 2.67, .94, 2.28, .57, 0xD9E1E4, 2)
        box(-3.31, 2.44, 2.66, 1.03, .13, .62, cyan, 2, .6f)
        // Umbrella stand and blue umbrella handles.
        box(-1.46, .49, 2.17, .52, .66, .37, charcoal, 2)
        for (i in 0..3) pole(-1.67 + i*.13, 1.06, 2.17, .036, .88,
            listOf(cyan, cream, coral, 0xADBCEC)[i], 2)
        // Bike: actual torus wheels in world coordinates, not a printed icon.
        wheel(-4.77, .63, 2.72, 1.02, 0xBFC6CB)
        wheel(-3.36, .63, 2.72, 1.02, 0xBFC6CB)
        box(-4.06, 1.01, 2.72, 1.38, .052, .05, 0xD2B5A0, 2, rz = -17f)
        box(-4.06, .99, 2.72, 1.30, .052, .05, 0xD2B5A0, 2, rz = 23f)
        box(-4.31, 1.21, 2.72, .73, .05, .05, 0xD2B5A0, 2, rz = -49f)
        box(-4.45, 1.42, 2.72, .53, .08, .10, charcoal, 2)
        pole(-3.37, 1.33, 2.72, .046, 1.30, 0xC4CBD0, 2, rz = -18f)
        box(-3.28, 1.78, 2.72, .65, .045, .065, 0xC4CBD0, 2)
        // Utility pole, layered transformers / power cables, and corner guardrail.
        pole(-5.2, 3.0, -1.75, .20, 6.0, 0x667B86)
        pole(-5.2, 5.37, -1.75, .12, 1.10, 0xA4AAB1)
        box(-5.2, 4.8, -1.75, 1.15, .15, .19, 0xA5ABB3, 2)
        for (i in 0..2) {
            box(-5.2 + .45*i, 4.85, -1.73, .10, .13, .13, 0xE9D7B2, 2)
            box(-5.18, 5.15 - i*.23, -.5, .035, .035, 2.9, 0x4F6474, 2)
        }
        pole(-5.27, 2.35, 1.37, .08, 4.6, 0x7D8C94)
        box(-5.26, 4.43, 1.37, .67, .15, .11, 0xC8D1D6, 2)
        box(-4.83, 4.40, 1.37, .55, .14, .46, 0xEFCB89, 2, 1.0f)
        for (z in listOf(2.6,3.6)) {
            pole(-5.58,.52,z,.085,1.04,0xB6C8CF)
        }
        box(-5.58, .93, 3.1, .075, .075, 1.10, 0xC4CFD6, 2)
        // Trash bins and AC unit have distinct silhouettes.
        for (x in listOf(4.63, 5.23)) {
            box(x, .48, .55, .51, .87, .51, 0xA9BAC0, 2)
            box(x, .93, .55, .54, .07, .54, charcoal, 2)
        }
        box(4.78, .70, -2.45, 1.34, .92, .58, 0xCED8D8, 2)
        for (i in 0..5) box(4.80, .41 + i*.107, -2.144, 1.13, .029, .02, 0x7C969D, 2)
        // Alley noticeboard + hanging posters and tiny flower pots.
        box(4.82, 1.18, -4.11, 1.40, 1.68, .13, 0x634B49, 2)
        for (i in 0..2) box(4.42 + .39*i, 1.25, -4.015,
            .31, .66 + (i%2)*.22, .022, listOf(coral,cyan,0xEFCA81)[i], 2)
        for (i in 0..2) {
            box(5.35, .29, -3.74 + i*.62, .52, .47, .45, 0xA57F72, 2)
            pole(5.35, .78, -3.74 + i*.62, .08, .60, 0x607B68, 2)
            add(DioramaMesh.RING, 5.35, 1.09, -3.74 + i*.62, .52,.48,.52,
                0x6D967B, 2)
        }
        // Wet curb reflections: pale, restrained, no cyberpunk colour flood.
        for (i in 0..5) {
            box(-2.2 + i*.96, .06, 3.3 + (i%2)*.24,
                .19, .008, .95, if (i%3==0) coral else warm, 6, .65f, .30f)
        }
    }

    private fun signTexture(): Int {
        val bitmap = Bitmap.createBitmap(1024, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = AndroidColor.rgb(33, 57, 75)
        c.drawColor(p.color)
        p.color = AndroidColor.rgb(114, 224, 223)
        c.drawRect(0f, 0f, 1024f, 12f, p)
        p.color = AndroidColor.rgb(244, 147, 162)
        c.drawRect(0f, 113f, 1024f, 128f, p)
        p.color = AndroidColor.rgb(250, 248, 226)
        p.textSize = 76f
        p.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        c.drawText("HOSHI", 55f, 93f, p)
        p.textSize = 42f
        c.drawText("MART", 390f, 89f, p)
        p.color = AndroidColor.rgb(114, 224, 223)
        p.textSize = 62f
        c.drawText("24", 840f, 93f, p)
        return upload(bitmap)
    }

    private fun vendingTexture(): Int {
        val bitmap = Bitmap.createBitmap(256, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(AndroidColor.rgb(239, 243, 242))
        p.color = AndroidColor.rgb(112, 211, 220)
        c.drawRect(5f, 6f, 251f, 67f, p)
        p.color = AndroidColor.WHITE
        p.textSize = 32f
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText("DRINK", 43f, 49f, p)
        for (row in 0..4) for (col in 0..3) {
            val left = 18f + col*57
            val top = 87f + row*62
            p.color = AndroidColor.rgb(26, 51, 65)
            c.drawRoundRect(left-3,top-5,left+44,top+49,5f,5f,p)
            p.color = listOf(0xED928E,0x8DCBC1,0xEFCA75,0x9BB6DF)[(row+col)%4] or (0xFF shl 24)
            c.drawRoundRect(left+6,top,left+35,top+38,7f,7f,p)
            p.color = AndroidColor.WHITE
            c.drawRect(left+11,top+11,left+30,top+15,p)
        }
        p.color = AndroidColor.rgb(34, 64, 78)
        c.drawRoundRect(144f, 418f, 234f, 474f, 8f, 8f,p)
        p.color = AndroidColor.rgb(219, 235, 221)
        c.drawRoundRect(156f, 426f, 219f, 446f, 4f, 4f,p)
        return upload(bitmap)
    }

    private fun upload(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return ids[0]
    }

    private fun link(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val status = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(id) }
            return id
        }
        val v = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(p) }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun buffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); position(0) }

    private fun makeCube(): FloatArray {
        val positions = floatArrayOf(
            -.5f,-.5f,-.5f, .5f,-.5f,-.5f, .5f,.5f,-.5f, -.5f,.5f,-.5f,
            -.5f,-.5f,.5f, .5f,-.5f,.5f, .5f,.5f,.5f, -.5f,.5f,.5f,
        )
        val faces = arrayOf(
            intArrayOf(4,5,6,7),intArrayOf(1,0,3,2),intArrayOf(0,4,7,3),
            intArrayOf(5,1,2,6),intArrayOf(3,7,6,2),intArrayOf(0,1,5,4),
        )
        val normals = arrayOf(
            floatArrayOf(0f,0f,1f),floatArrayOf(0f,0f,-1f),floatArrayOf(-1f,0f,0f),
            floatArrayOf(1f,0f,0f),floatArrayOf(0f,1f,0f),floatArrayOf(0f,-1f,0f),
        )
        val result = ArrayList<Float>(216)
        faces.forEachIndexed { index, face ->
            for (corner in intArrayOf(0,1,2,0,2,3)) {
                val pos = face[corner] * 3
                for (k in 0..2) result.add(positions[pos+k])
                result.addAll(normals[index].toList())
            }
        }
        return result.toFloatArray()
    }

    private fun makeCylinder(segments: Int): FloatArray {
        val result = ArrayList<Float>()
        fun push(x: Float,y: Float,z: Float,nx: Float,ny:Float,nz:Float) {
            result.add(x);result.add(y);result.add(z)
            result.add(nx);result.add(ny);result.add(nz)
        }
        for (i in 0 until segments) {
            val a = (i.toFloat()/segments * 2*PI).toFloat()
            val b = ((i+1f)/segments * 2*PI).toFloat()
            val ca=cos(a);val sa=sin(a);val cb=cos(b);val sb=sin(b)
            push(ca*.5f,-.5f,sa*.5f,ca,0f,sa)
            push(cb*.5f,-.5f,sb*.5f,cb,0f,sb)
            push(cb*.5f,.5f,sb*.5f,cb,0f,sb)
            push(ca*.5f,-.5f,sa*.5f,ca,0f,sa)
            push(cb*.5f,.5f,sb*.5f,cb,0f,sb)
            push(ca*.5f,.5f,sa*.5f,ca,0f,sa)
        }
        return result.toFloatArray()
    }

    private fun makeRing(segments: Int): FloatArray {
        val result = ArrayList<Float>()
        fun push(x:Float,y:Float,z:Float) {
            result.add(x);result.add(y);result.add(z)
            result.add(0f);result.add(0f);result.add(1f)
        }
        for (i in 0 until segments) {
            val a=(i.toFloat()/segments*2*PI).toFloat()
            val b=((i+1f)/segments*2*PI).toFloat()
            val outer=.50f; val inner=.435f
            val xa=cos(a);val ya=sin(a);val xb=cos(b);val yb=sin(b)
            push(xa*outer,ya*outer,0f);push(xa*inner,ya*inner,0f);push(xb*outer,yb*outer,0f)
            push(xb*outer,yb*outer,0f);push(xa*inner,ya*inner,0f);push(xb*inner,yb*inner,0f)
        }
        return result.toFloatArray()
    }
}
