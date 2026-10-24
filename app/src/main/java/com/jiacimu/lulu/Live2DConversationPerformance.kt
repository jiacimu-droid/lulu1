package com.jiacimu.lulu

import org.json.JSONArray
import org.json.JSONObject

/**
 * Compiles a visual intention chosen in the SAME character decision into a
 * short, local score. It never reads dialogue, guesses feelings or stores a
 * second emotional state. The bus still verifies every resulting rig channel.
 */
internal object Live2DConversationPerformance {
    private data class Pose(
        val face: Map<String, Double> = emptyMap(),
        val head: Map<String, Double> = emptyMap(),
        val body: Map<String, Double> = emptyMap(),
    )

    private val feelings = linkedMapOf(
        "calm" to Pose(face = mapOf("gazeX" to 0.0, "gazeY" to 0.0,
            "smile" to 0.0, "eyeSmile" to 0.0, "eyeOpen" to 1.0, "browY" to 0.0)),
        "smile" to Pose(face = mapOf("smile" to .52, "eyeSmile" to .25,
            "eyeOpen" to .9, "browY" to .1),
            head = mapOf("roll" to 1.4), body = mapOf("pitch" to .6, "roll" to .3)),
        "happy" to Pose(face = mapOf("smile" to .8, "eyeSmile" to .55,
            "eyeOpen" to .86, "browY" to .22),
            head = mapOf("pitch" to 2.5, "roll" to 2.5),
            body = mapOf("pitch" to 1.6, "roll" to .8)),
        "playful" to Pose(face = mapOf("smile" to .68, "eyeSmile" to .28,
            "eyeOpen" to .92, "browY" to .26, "gazeX" to .3),
            head = mapOf("yaw" to 3.0, "roll" to 4.0),
            body = mapOf("yaw" to 1.4, "roll" to 1.4)),
        "amused" to Pose(face = mapOf("smile" to .72, "eyeSmile" to .68,
            "eyeOpen" to .68, "browY" to .12, "squint" to .16),
            head = mapOf("pitch" to 1.8, "roll" to 2.0), body = mapOf("roll" to .5)),
        "curious" to Pose(face = mapOf("gazeY" to .06, "eyeOpen" to 1.0,
            "browY" to .28, "browAngle" to .08, "smile" to .05), head = mapOf("roll" to 3.0)),
        "thinking" to Pose(face = mapOf("gazeX" to .28, "gazeY" to -.12,
            "eyeOpen" to .82, "browAngle" to -.12), head = mapOf("yaw" to 2.8, "pitch" to -1.4)),
        "shy" to Pose(face = mapOf("smile" to .22, "eyeSmile" to .12,
            "eyeOpen" to .8, "gazeX" to .32, "gazeY" to -.2, "browY" to .12),
            head = mapOf("yaw" to 3.5, "pitch" to -3.8, "roll" to -2.0),
            body = mapOf("yaw" to 1.5, "roll" to -.4)),
        "sad" to Pose(face = mapOf("smile" to -.3, "eyeOpen" to .82,
            "browY" to .2, "browForm" to -.25, "browAngle" to -.35, "gazeY" to -.2),
            head = mapOf("pitch" to -4.0), body = mapOf("pitch" to -1.0)),
        "aggrieved" to Pose(face = mapOf("smile" to -.52, "eyeOpen" to .85,
            "browY" to .55, "browAngle" to .38, "browForm" to .24,
            "pout" to .5, "gazeY" to -.27),
            head = mapOf("pitch" to -4.5, "roll" to 5.5),
            body = mapOf("pitch" to -2.0, "roll" to 1.4)),
        "tearful" to Pose(face = mapOf("smile" to -.65, "eyeOpen" to .72,
            "browY" to .62, "browAngle" to .42, "browForm" to .3,
            "pout" to .72, "gazeY" to -.36),
            head = mapOf("pitch" to -6.0, "roll" to 5.0),
            body = mapOf("pitch" to -3.0, "roll" to 1.2)),
        "worried" to Pose(face = mapOf("smile" to -.2, "eyeOpen" to .95,
            "browY" to .42, "browAngle" to .26, "gazeY" to -.12),
            head = mapOf("pitch" to -2.5, "roll" to 1.0),
            body = mapOf("pitch" to -1.0)),
        "concerned" to Pose(face = mapOf("smile" to -.15, "eyeOpen" to .96,
            "browY" to .26, "browAngle" to -.22), head = mapOf("pitch" to -1.8)),
        "serious" to Pose(face = mapOf("smile" to -.08, "eyeOpen" to .92,
            "eyeSmile" to 0.0, "browY" to -.12, "browAngle" to -.1)),
        "affectionate" to Pose(face = mapOf("smile" to .46, "eyeSmile" to .35,
            "eyeOpen" to .82, "browY" to .16, "gazeX" to 0.0),
            head = mapOf("roll" to 3.2, "pitch" to -1.0),
            body = mapOf("pitch" to 1.3, "roll" to .8)),
        "relieved" to Pose(face = mapOf("smile" to .32, "eyeSmile" to .3,
            "eyeOpen" to .76, "browY" to .14),
            head = mapOf("pitch" to -1.5), body = mapOf("pitch" to 1.2)),
        "surprised" to Pose(face = mapOf("eyeOpen" to 1.0, "browY" to .55,
            "browAngle" to .1), head = mapOf("pitch" to 2.8), body = mapOf("pitch" to -1.4)),
        "displeased" to Pose(face = mapOf("smile" to -.3, "eyeOpen" to .78,
            "browY" to -.25, "browAngle" to -.35, "pout" to .12),
            head = mapOf("yaw" to -2.0), body = mapOf("yaw" to -1.0)),
    )
    private val movements = linkedMapOf(
        "nod" to Pose(head = mapOf("pitch" to 10.0)),
        "shake" to Pose(head = mapOf("yaw" to 10.0)),
        "tilt" to Pose(head = mapOf("roll" to 10.0), body = mapOf("roll" to .8)),
        "glance" to Pose(face = mapOf("gazeX" to .6), head = mapOf("yaw" to 6.0)),
        "look_down" to Pose(face = mapOf("gazeY" to -.45), head = mapOf("pitch" to -7.0)),
        "turn" to Pose(head = mapOf("yaw" to 8.0), body = mapOf("yaw" to 6.0, "roll" to .8)),
        "lean" to Pose(head = mapOf("pitch" to -1.4), body = mapOf("pitch" to 2.5)),
        "withdraw" to Pose(head = mapOf("pitch" to 2.5), body = mapOf("pitch" to -3.5)),
    )
    private val labels = mapOf(
        "calm" to "平静", "smile" to "浅笑", "happy" to "开心", "playful" to "俏皮", "amused" to "笑意明显", "curious" to "好奇",
        "thinking" to "思考", "shy" to "羞赧", "sad" to "失落", "aggrieved" to "委屈", "tearful" to "含泪", "worried" to "担忧", "concerned" to "关切",
        "serious" to "认真", "affectionate" to "柔情", "relieved" to "释然", "surprised" to "惊讶", "displeased" to "不悦",
        "nod" to "点一次头", "shake" to "轻摇一次头", "tilt" to "轻歪头",
        "glance" to "侧看后回视", "look_down" to "垂眸", "turn" to "身体轻转",
        "lean" to "身体轻倾", "withdraw" to "身体稍收回",
    )
    private val headIds = mapOf("yaw" to "ParamAngleX", "pitch" to "ParamAngleY", "roll" to "ParamAngleZ")
    private val bodyIds = mapOf("yaw" to "ParamBodyAngleX", "pitch" to "ParamBodyAngleY", "roll" to "ParamBodyAngleZ")
    private val faceIds = Live2DVisualActionBus.faceChannels

    fun capabilityNote(allowed: Set<String>): String {
        fun supported(pose: Pose): Boolean = pose.head.keys.any { headIds[it] in allowed } ||
            pose.body.keys.any { bodyIds[it] in allowed } ||
            pose.face.keys.any { key -> faceIds[key]?.all { it in allowed } == true }
        fun options(source: Map<String, Pose>) = source.filterValues(::supported).keys
            .joinToString("、") { "$it(${labels[it]})" }.ifBlank { "无" }
        return "可选神态 feeling：${options(feelings)}；可选小动作 movement：${options(movements)}。"
    }

    /** Explicit beats win; explicit numeric channels override generated values. */
    fun expand(request: JSONObject): JSONObject {
        val intent = request.optJSONObject("performance") ?: return request
        if ((request.optJSONArray("beats")?.length() ?: 0) > 0) return request
        fun choice(key: String, options: Map<String, Pose>): String? {
            val value = intent.optString(key).trim().lowercase(java.util.Locale.ROOT)
            return options.keys.firstOrNull { it == value || labels[it] == value }
        }
        val feeling = choice("feeling", feelings)
        val movement = choice("movement", movements)
        if (feeling == null && movement == null) return request
        val strength = intent.optDouble("intensity", .45).takeIf(Double::isFinite)
            ?.coerceIn(0.0, 1.0) ?: .45
        if (strength == 0.0) return request
        val side = if (intent.optString("side") in setOf("left", "左")) -1.0 else 1.0
        val feelingPose = feelings[feeling] ?: Pose()
        val movementPose = movements[movement] ?: Pose()
        // A chosen face accompanies speech beyond the first second, while
        // movement peaks early and settles. Actual TTS end cancels remaining
        // beats and releases the temporary face in the renderer.
        val times = listOf(0, 240, 680, 2000, 4500, 9000)
        val feelingCurve = listOf(.68, 1.0, .98, .91, .86, .78)
        val movementCurve = when (movement) {
            "nod" -> listOf(-.35, -1.0, .28, 0.0, 0.0, 0.0)
            "shake" -> listOf(.15, 1.0, -.85, .18, 0.0, 0.0)
            else -> listOf(.45, 1.0, .65, .18, 0.0, 0.0)
        }
        val beats = JSONArray()
        for (i in times.indices) {
            val beat = JSONObject().put("atMs", times[i])
                .put("durationMs", if (i < times.lastIndex) times[i + 1] - times[i] + 120 else 6000)
            fun part(key: String, emotion: Map<String, Double>, action: Map<String, Double>) {
                val values = JSONObject()
                for (channel in emotion.keys + action.keys) {
                    val rest = if (channel == "eyeOpen") 1.0 else 0.0
                    val directed = channel in setOf("yaw", "roll", "gazeX")
                    val direction = if (directed) side else 1.0
                    val value = rest + ((emotion[channel]?.minus(rest) ?: 0.0) * feelingCurve[i] +
                        (action[channel]?.minus(rest) ?: 0.0) * movementCurve[i]) * strength * direction
                    values.put(channel, value)
                }
                request.optJSONObject(key)?.let { explicit ->
                    for (channel in explicit.keys()) values.put(channel, explicit.get(channel))
                }
                if (values.length() > 0) beat.put(key, values)
            }
            part("face", feelingPose.face, movementPose.face)
            part("head", feelingPose.head, movementPose.head)
            part("body", feelingPose.body, movementPose.body)
            beats.put(beat)
        }
        return JSONObject(request.toString()).put("beats", beats)
    }
}
