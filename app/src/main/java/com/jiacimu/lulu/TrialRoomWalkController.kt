package com.jiacimu.lulu

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * First-person collision controller for the isolated three-dimensional test
 * room. Stable scene props are geometric obstacles; no demo interaction writes
 * to the canonical digital-world timeline.
 */
internal class TrialRoomWalkController {
    @Volatile var x: Float = 0f
        private set
    @Volatile var z: Float = 5.5f
        private set
    @Volatile var yaw: Float = 0f
    @Volatile var pitch: Float = 0f

    private data class Fixture(
        val title: String,
        val cx: Float, val cz: Float, val width: Float, val depth: Float,
        val description: String,
    )
    private val fixtures = listOf(
        Fixture("沙发", 4f, -1.76f, 2.6f, 1.25f, "一张蓝灰色沙发，旁边留了个可以坐下的位置。"),
        Fixture("茶几", 3.3f, 2.5f, 1.85f, 1.25f, "矮茶几上放着一本书。现在只能查看，尚不能拾取。"),
        Fixture("书架", -6.28f, -4f, 1f, 1.5f, "书架上有几排演示书籍。"),
        Fixture("盆栽", 5.66f, 3.8f, .75f, .75f, "一株摆在窗边的演示绿植。"),
        Fixture("角色低模", -1.44f, .6f, .9f, .74f, "眼前是尚未绑定骨骼、表情的二次元风格人物占位模型。"),
    )
    fun move(strafe: Float, forward: Float, seconds: Float = .016f) {
        val speed = 2.2f * seconds.coerceIn(0f, .1f)
        val size = sqrt(strafe * strafe + forward * forward)
        if (size < .02f) return
        val side = strafe / size
        val ahead = forward / size
        val dx = (cos(yaw.toDouble()) * side + sin(yaw.toDouble()) * ahead).toFloat() * speed
        val dz = (sin(yaw.toDouble()) * side - cos(yaw.toDouble()) * ahead).toFloat() * speed
        fun open(nx: Float, nz: Float): Boolean {
            if (nx !in -7.05f..7.05f || nz !in -6.30f..6.30f) return false
            return fixtures.none { fixture ->
                kotlin.math.abs(nx - fixture.cx) <= fixture.width / 2f + .20f &&
                    kotlin.math.abs(nz - fixture.cz) <= fixture.depth / 2f + .20f
            }
        }
        if (open(x + dx, z)) x += dx
        if (open(x, z + dz)) z += dz
    }

    fun look(deltaX: Float, deltaY: Float) {
        yaw += deltaX * .005f
        pitch = (pitch - deltaY * .003f).coerceIn(-.85f, .85f)
    }

    fun inspect(): String {
        val candidates = fixtures.mapNotNull { fixture ->
            val dx = fixture.cx - x
            val dz = fixture.cz - z
            val d = sqrt(dx * dx + dz * dz)
            val toward = (sin(yaw.toDouble()) * dx - cos(yaw.toDouble()) * dz).toFloat()
            fixture.takeIf { d < 2.65f && toward > d * .35f }?.let { it to d }
        }
        return candidates.minByOrNull { it.second }?.first?.let {
            "${it.title} · ${it.description}"
        } ?: "面前没有可查看的东西。靠近家具或人物，再点击查看。"
    }

    fun reset() {
        x = 0f; z = 5.5f; yaw = 0f; pitch = 0f
    }
}
