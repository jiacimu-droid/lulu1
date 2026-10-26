package com.jiacimu.lulu.ai

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Native TypeSafe API. The role authors the questions and interprets ALL
 * probabilities; code never turns them into intentions, facts or actions. */
internal object JevDirections {
    const val TOOL = "consider_directions"
    private const val PREFS = "lulu_optional_reasoning"
    private const val LONG_CONTEXT = "jev_long_context"
    const val INTERPRETATION = "这是Jev对你所给材料和问题的概率参考，不是新发生的事实、你的最终意愿或执行回执。请结合自己的人格、关系和原始证据继续理解；可以采纳、不同意、保留矛盾或不行动。程序没有按概率替你选择。"

    fun configured(): Boolean = runCatching {
        ScopedModelSelections.selectedArchiveId(ScopedModelSelections.JEV)
    }.getOrNull() != null
    fun longContext(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean(LONG_CONTEXT, false)
    fun setLongContext(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(LONG_CONTEXT, enabled).apply()
    }

    fun context(): String = if (!configured()) "" else """
        【可选多方向参考工具】
        consider_directions 已配置。若你本人想把几个不同方向分别考虑，可选择 tool=consider_directions，args={"state":"你自己选取的相关事实、人格、旧经历和当前疑问，保留原话与来源，猜测须标明","questions":{"本人起的方向ID":{"instructions":"本人想问的一个是否问题，可选criteria={true:何为是,false:何为否}"}}}。
        你自己提出问题，方向不限于用户最新一句；也可考虑本人兴趣、旧愿望、他人关系、世界经历、学习或未来可能性，不要求逐项遍历或使用固定主题。一次提交所有本次想问的问题，返回每题noul概率。没有新材料不必重复问。
        $INTERPRETATION
        此工具只作参考，不替代你理解主人、选择行动、写心声或确认目标完成；不应每轮调用。它会单独收费；默认state+单题32k、整包64k，显式长上下文开启后262k。保留长期原文，只选本次相关材料。
    """.trimIndent()

    internal fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/v1/systemone") -> base
            base.endsWith("/v1") -> "$base/systemone"
            else -> "$base/v1/systemone"
        }
    }

    internal fun request(model: String, args: JSONObject): JSONObject {
        require(model.startsWith("jev-")) { "请选择Jev型号存档；它使用原生systemone接口，不能拿聊天模型代替" }
        val state = args.opt("state")
        require(state is JSONObject || state is JSONArray || state is String && state.isNotBlank()) {
            "state必须是本人提供的非空文字、对象或数组"
        }
        val questions = args.optJSONObject("questions") ?: error("请提供本人想分别考虑的questions")
        require(questions.length() > 0) { "questions不能为空" }
        val normalized = JSONObject()
        questions.keys().forEach { key ->
            require(key.isNotBlank()) { "问题ID不能为空" }
            val item = questions.optJSONObject(key) ?: error("问题${key}必须是对象")
            require(item.optString("type", "noul") == "noul") { "当前仅接入noul是否问题，请分别提出每个方向" }
            val instruction = item.opt("instructions")
            require(instruction is JSONObject || instruction is JSONArray ||
                instruction is String && instruction.isNotBlank()) { "问题${key}缺少instructions" }
            val question = JSONObject().put("type", "noul").put("instructions", instruction)
            item.optJSONObject("criteria")?.let { question.put("criteria", it) }
            normalized.put(key, question)
        }
        return JSONObject().put("model", model).put("state", state).put("questions", normalized)
    }

    internal fun result(request: JSONObject, response: JSONObject): JSONObject {
        val answers = response.optJSONObject("answers") ?: error("Jev未返回answers")
        val questions = request.getJSONObject("questions")
        val directions = JSONArray()
        questions.keys().forEach { key ->
            val answer = answers.optJSONObject(key) ?: error("Jev遗漏问题$key，结果未作为完整参考提交")
            val number = answer.opt("noul") as? Number ?: error("问题${key}未返回数字概率")
            val probability = number.toDouble()
            require(answer.optString("type") == "noul" && probability.isFinite() && probability in 0.0..1.0) {
                "问题${key}的概率或类型无效"
            }
            directions.put(JSONObject().put("id", key).put("question", questions.getJSONObject(key))
                .put("noul", probability))
        }
        require(response.optString("model").isNotBlank()) { "Jev没有报告实际使用的型号" }
        val usage = response.optJSONObject("usage") ?: error("Jev没有报告usage，不能假造token用量")
        require(usage.has("input_tokens") && usage.has("output_tokens")) { "Jev用量字段缺失" }
        for (key in listOf("input_tokens", "output_tokens")) {
            val count = usage.opt(key) as? Number ?: error("Jev用量${key}不是整数")
            require(count.toDouble().isFinite() && count.toDouble() == count.toLong().toDouble() &&
                count.toLong() in 0..Int.MAX_VALUE.toLong()) { "Jev用量${key}无效" }
        }
        return JSONObject().put("success", true).put("status", "succeeded")
            .put("model", response.getString("model")).put("directions", directions)
            .put("usage", usage).put("interpretation", INTERPRETATION)
            .put("summary", "已取得${directions.length()}个本人所问方向的概率参考；尚未由角色作最终选择，也没有执行这些方向的行动。")
    }

    /** Same network timeout policy as the existing model gateway. One request,
     * no automatic paid retry; ToolRouter persists its exact request receipt. */
    internal fun exchange(connection: ModelConnection, args: JSONObject, extended: Boolean): Pair<JSONObject, Int> {
        val body = request(connection.model, args)
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32 * 1024 * 1024) { "Jev请求超过官方32MiB限制；未上传，请缩小本次材料" }
        val http = URL(endpoint(connection.baseUrl)).openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.instanceFollowRedirects = false
            http.connectTimeout = 30_000
            http.readTimeout = 90_000
            http.setRequestProperty("Authorization", "Bearer ${connection.apiKey}")
            http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (extended) http.setRequestProperty("TypeSafe-Danger-Zone-Long-Context", "true")
            http.doOutput = true
            http.outputStream.use { it.write(bytes) }
            val code = http.responseCode
            check(code in 200..299) {
                "Jev请求失败（HTTP $code）。" + when (code) {
                    400, 413, 422 -> "请核对问题结构与本次输入大小；默认单题32k、整包64k，长上下文需显式开启。"
                    401, 403 -> "请核对TypeSafe密钥与账户权限。"
                    429, 529 -> "服务限流或繁忙，本次没有自动重试。"
                    else -> "未取得有效参考，本次没有自动重试或改用其他收费模型。"
                }
            }
            val response = http.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            return result(body, response) to bytes.size
        } finally { http.disconnect() }
    }

    suspend fun evaluate(context: Context, characterId: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        val archiveId = ScopedModelSelections.selectedArchiveId(ScopedModelSelections.JEV)
            ?: return@withContext failure("Jev尚未配置；未调用，不影响原角色继续思考", "waiting_user")
        val started = System.nanoTime()
        runCatching {
            val connection = LuluAiServices.connectionStore.resolveConnection(archiveId)
            val (result, bytes) = exchange(connection, args, longContext(context))
            val usage = result.getJSONObject("usage")
            val duration = (System.nanoTime() - started) / 1_000_000
            LuluRepositories.performance.recordGeneration(
                source = "角色思路参考", title = "$characterId · Jev", model = result.getString("model"),
                provider = URL(connection.baseUrl).host,
                reportedInputTokens = usage.optInt("input_tokens"), reportedOutputTokens = usage.optInt("output_tokens"),
                cachedTokens = 0, estimatedInputTokens = 0, estimatedOutputTokens = 0,
                breakdown = emptyList(), promptMillis = 0, modelMillis = duration, totalMillis = duration,
                requestBodyBytes = bytes.toLong(), contextMode = "角色选择的材料与问题",
            )
            if (ScopedModelSelections.selectedArchiveId(ScopedModelSelections.JEV) != archiveId)
                failure("本次请求期间Jev配置已撤销或更换，结果已丢弃；已发生的API用量仍已记录")
            else result.toString()
        }.getOrElse { failure(it.message ?: "Jev未返回有效结果") }
    }

    private fun failure(reason: String, status: String = "failed") = JSONObject()
        .put("success", false).put("status", status).put("error", reason).toString()
}
