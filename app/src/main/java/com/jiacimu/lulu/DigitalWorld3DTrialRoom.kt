package com.jiacimu.lulu

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A REAL OpenGL ES 3D sandbox, not a flattened image or a second canon world.
 * The sandbox's props/actor are placeholders and do not modify world state.
 * Later replace the procedural meshes with licensed VRM / GLB scene assets.
 */
@Composable
internal fun DigitalWorld3DTrialRoom(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val surface = remember(context) {
        val renderer = TrialRoomRenderer()
        var lastX = 0f
        var lastY = 0f
        var lastSpan = 0f
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.x; lastY = event.y; lastSpan = 0f
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount >= 2) lastSpan = sqrt(
                            (event.getX(0) - event.getX(1)) * (event.getX(0) - event.getX(1)) +
                                (event.getY(0) - event.getY(1)) * (event.getY(0) - event.getY(1)))
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount >= 2) {
                            val dx = event.getX(0) - event.getX(1)
                            val dy = event.getY(0) - event.getY(1)
                            val span = sqrt(dx * dx + dy * dy)
                            if (lastSpan > 0) renderer.distance =
                                (renderer.distance * lastSpan / span.coerceAtLeast(1f)).coerceIn(3.4f, 14f)
                            lastSpan = span
                        } else {
                            renderer.yaw += (event.x - lastX) * .005f
                            renderer.pitch = (renderer.pitch - (event.y - lastY) * .003f).coerceIn(.1f, 1.05f)
                        }
                        lastX = event.x; lastY = event.y
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> lastSpan = 0f
                }
                true
            }
        }
    }
    // Rendering starts when AndroidView attaches the surface; do not resume a
    // GLSurfaceView before its internal GL thread has been attached.
    DisposableEffect(surface) {
        onDispose { surface.onPause() }
    }
    Box(modifier.fillMaxSize().background(Color(0xFF101B26))) {
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.TopStart).padding(14.dp)
                .background(Color(0xD8243040), RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("3D 体验间 · 原型", color = Color.White, fontSize = 14.sp)
            Text("拖动旋转视角 · 双指缩放", color = Color(0xFFE1E5EE), fontSize = 11.sp)
            Text("当前人物与家具为演示资产，尚未同步真实家园", color = Color(0xFFD7E1E8), fontSize = 10.sp)
        }
    }
}

private class TrialRoomRenderer : GLSurfaceView.Renderer {
    @Volatile var yaw = .30f
    @Volatile var pitch = .32f
    @Volatile var distance = 8f
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val vp = FloatArray(16)
    private var program = 0
    private var positionLoc = 0
    private var normalLoc = 0
    private var mvpLoc = 0
    private var colorLoc = 0
    private lateinit var cube: Mesh
    private lateinit var sphere: Mesh

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vNormal = aNormal;
            }
        """.trimIndent())
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float;
            uniform vec4 uColor;
            varying vec3 vNormal;
            void main() {
                float light = max(dot(normalize(vNormal), normalize(vec3(-0.3, 0.85, 0.65))), 0.0);
                gl_FragColor = vec4(uColor.rgb * (0.47 + 0.53 * light), uColor.a);
            }
        """.trimIndent())
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex); GLES20.glAttachShader(it, fragment)
            GLES20.glLinkProgram(it)
        }
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        normalLoc = GLES20.glGetAttribLocation(program, "aNormal")
        mvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
        colorLoc = GLES20.glGetUniformLocation(program, "uColor")
        cube = Mesh(makeCube())
        sphere = Mesh(makeSphere())
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(.07f, .11f, .17f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 50f, width.toFloat() / height.coerceAtLeast(1), .1f, 45f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val y = yaw; val p = pitch; val d = distance
        Matrix.setLookAtM(view, 0,
            (sin(y) * cos(p) * d).toFloat(), (1.0 + sin(p) * d).toFloat(),
            (cos(y) * cos(p) * d).toFloat(),
            0f, 1.2f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        // Actual 3D solid room: floor, two back walls, a window and real furniture.
        box(0f, -.11f, 0f, 7.6f, .2f, 6.8f, .76f, .71f, .64f)
        box(0f, 2.1f, -3.42f, 7.6f, 4.4f, .14f, .83f, .87f, .87f)
        box(-3.77f, 2.1f, 0f, .14f, 4.4f, 6.8f, .79f, .84f, .86f)
        // Window on the rear wall.
        box(1.95f, 2.3f, -3.30f, 2.10f, 1.65f, .07f, .36f, .54f, .68f)
        box(1.95f, 2.3f, -3.22f, .085f, 1.67f, .12f, .94f, .92f, .84f)
        box(1.95f, 2.3f, -3.20f, 2.12f, .085f, .12f, .94f, .92f, .84f)
        // Rug and lounge furniture.
        box(.82f, .035f, 1.25f, 3.75f, .07f, 2.7f, .64f, .74f, .71f)
        box(2.0f, .49f, -.84f, 2.25f, .83f, .84f, .44f, .59f, .60f)
        box(2.0f, 1.05f, -1.22f, 2.25f, 1.06f, .25f, .47f, .62f, .63f)
        box(.87f, .63f, -.84f, .25f, .66f, .82f, .41f, .55f, .55f)
        box(3.12f, .63f, -.84f, .25f, .66f, .82f, .41f, .55f, .55f)
        box(1.70f, .93f, -.75f, .49f, .26f, .43f, .81f, .73f, .62f)
        // Small table, book and pot plant with organic rounded leaves.
        box(1.65f, .58f, 1.25f, 1.4f, .14f, .88f, .53f, .37f, .30f)
        for (x in listOf(1.07f, 2.24f)) for (z in listOf(.94f, 1.53f))
            box(x, .26f, z, .10f, .5f, .10f, .42f, .32f, .29f)
        box(1.49f, .68f, 1.10f, .52f, .08f, .36f, .87f, .78f, .57f)
        box(2.83f, .27f, 1.9f, .48f, .48f, .48f, .74f, .61f, .48f)
        box(2.83f, .73f, 1.9f, .05f, .65f, .05f, .29f, .46f, .28f)
        ball(2.77f, 1.03f, 1.9f, .43f, .25f, .29f, .30f, .52f, .35f)
        ball(3.10f, .98f, 1.82f, .35f, .31f, .22f, .40f, .62f, .40f)
        // Side bookshelf.
        box(-3.18f, 1.3f, -2.0f, .76f, 2.55f, 1.24f, .49f, .35f, .31f)
        box(-3.15f, 1.32f, -1.35f, .72f, .10f, .25f, .68f, .52f, .41f)
        box(-3.15f, 1.98f, -1.35f, .72f, .10f, .25f, .68f, .52f, .41f)
        for (i in 0..5) {
            box(-3.29f + (i % 3) * .16f, .8f + (i / 3) * .70f, -1.35f,
                .13f, .40f, .21f, .35f + (i % 2) * .19f, .49f, .55f)
        }
        // Stylized placeholder actor, with depth-correct limbs, head and hair.
        box(-.95f, .15f, .40f, .33f, .28f, .52f, .18f, .22f, .34f)
        box(-.48f, .15f, .40f, .33f, .28f, .52f, .18f, .22f, .34f)
        box(-.96f, .61f, .27f, .27f, .77f, .30f, .16f, .24f, .33f)
        box(-.47f, .61f, .27f, .27f, .77f, .30f, .16f, .24f, .33f)
        box(-.71f, 1.25f, .26f, .89f, .81f, .47f, .19f, .31f, .43f)
        box(-1.25f, 1.18f, .35f, .23f, .66f, .26f, .19f, .30f, .42f)
        box(-.18f, 1.18f, .50f, .23f, .66f, .26f, .19f, .30f, .42f)
        ball(-.72f, 2.02f, .28f, .47f, .53f, .43f, .96f, .76f, .67f)
        ball(-.72f, 2.39f, .18f, .51f, .28f, .47f, .14f, .17f, .24f)
        box(-.72f, 2.36f, .59f, .73f, .11f, .16f, .14f, .17f, .24f)
        ball(-.88f, 2.08f, .67f, .052f, .075f, .035f, .16f, .20f, .27f)
        ball(-.54f, 2.08f, .67f, .052f, .075f, .035f, .16f, .20f, .27f)
        // Phone has an actual transform in the scene (not a photo overlay).
        box(-.13f, 1.38f, .75f, .21f, .36f, .07f, .10f, .13f, .20f)
        box(-.13f, 1.38f, .81f, .15f, .28f, .02f, .41f, .68f, .78f)
    }

    private fun box(x: Float,y: Float,z: Float,sx: Float,sy: Float,sz: Float,r: Float,g: Float,b: Float) =
        draw(cube, x,y,z, sx,sy,sz,r,g,b)
    private fun ball(x: Float,y: Float,z: Float,sx: Float,sy: Float,sz: Float,r: Float,g: Float,b: Float) =
        draw(sphere, x,y,z, sx,sy,sz,r,g,b)
    private fun draw(mesh: Mesh, x:Float,y:Float,z:Float, sx:Float,sy:Float,sz:Float, r:Float,g:Float,b:Float) {
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,x,y,z)
        Matrix.scaleM(model,0,sx,sy,sz)
        Matrix.multiplyMM(mvp,0,vp,0,model,0)
        GLES20.glUniformMatrix4fv(mvpLoc,1,false,mvp,0)
        GLES20.glUniform4f(colorLoc,r,g,b,1f)
        mesh.buffer.position(0)
        GLES20.glVertexAttribPointer(positionLoc,3,GLES20.GL_FLOAT,false,24,mesh.buffer)
        GLES20.glEnableVertexAttribArray(positionLoc)
        mesh.buffer.position(3)
        GLES20.glVertexAttribPointer(normalLoc,3,GLES20.GL_FLOAT,false,24,mesh.buffer)
        GLES20.glEnableVertexAttribArray(normalLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,mesh.count)
    }
    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader,source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private class Mesh(values: FloatArray) {
        val count = values.size / 6
        val buffer = ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); position(0) }
    }

    private fun makeCube(): FloatArray {
        val v = floatArrayOf(
            -.5f,-.5f,-.5f, .5f,-.5f,-.5f, .5f,.5f,-.5f, -.5f,.5f,-.5f,
            -.5f,-.5f,.5f, .5f,-.5f,.5f, .5f,.5f,.5f, -.5f,.5f,.5f)
        val faces = arrayOf(
            intArrayOf(4,5,6,7), intArrayOf(1,0,3,2), intArrayOf(0,4,7,3),
            intArrayOf(5,1,2,6), intArrayOf(3,7,6,2), intArrayOf(0,1,5,4))
        val normals = arrayOf(
            floatArrayOf(0f,0f,1f),floatArrayOf(0f,0f,-1f),floatArrayOf(-1f,0f,0f),
            floatArrayOf(1f,0f,0f),floatArrayOf(0f,1f,0f),floatArrayOf(0f,-1f,0f))
        val out=ArrayList<Float>(216)
        faces.forEachIndexed { f,ids ->
            for (vId in intArrayOf(ids[0],ids[1],ids[2],ids[0],ids[2],ids[3])) {
                val i=vId*3
                out.add(v[i]);out.add(v[i+1]);out.add(v[i+2])
                out.addAll(normals[f].toList())
            }
        }
        return out.toFloatArray()
    }
    private fun makeSphere(): FloatArray {
        val out=ArrayList<Float>()
        val rings=12; val segments=18
        fun point(u:Int,v:Int): FloatArray {
            val theta=u.toDouble()/segments * Math.PI*2
            val phi=v.toDouble()/rings * Math.PI
            return floatArrayOf(
                (sin(phi)*cos(theta)*.5).toFloat(),(cos(phi)*.5).toFloat(),
                (sin(phi)*sin(theta)*.5).toFloat())
        }
        for (v in 0 until rings) for (u in 0 until segments) {
            val a=point(u,v);val b=point(u+1,v);val c=point(u+1,v+1);val d=point(u,v+1)
            for (p in arrayOf(a,c,b,a,d,c)) {
                out.addAll(p.toList())
                out.addAll(p.map { it*2 }.toList())
            }
        }
        return out.toFloatArray()
    }
}
