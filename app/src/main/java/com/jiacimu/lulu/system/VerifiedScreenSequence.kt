package com.jiacimu.lulu.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Bounded local execution: each action requires fresh before/after observations. */
internal object VerifiedScreenSequence {
    suspend fun execute(args: JSONObject): String = withContext(Dispatchers.Main) {
        val steps = args.getJSONArray("steps")
        require(steps.length() in 1..5) { "屏幕流程必须包含1至5步" }
        val allowed = args.getJSONArray("allowedPackages")
        val packages = (0 until allowed.length()).map { allowed.getString(it) }.filter(String::isNotBlank)
        require(packages.isNotEmpty()) { "必须限定允许操作的应用包名" }
        // Validate the entire plan before the first side effect.
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            require(step.getString("text").isNotBlank() && step.getString("expectedText").isNotBlank()) { "每步需要点击文字和验证文字" }
        }
        val observations = JSONArray()
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val before = LuluAccessibilityService.observe()
            if (!before.connected || before.packageName !in packages || !before.visibleText.contains(step.getString("text"))) {
                return@withContext outcome(false, i, observations, "当前屏幕不符合操作前提，已停止")
            }
            val accepted = LuluAccessibilityService.clickFirstText(step.getString("text"))
            if (!accepted) return@withContext outcome(false, i, observations, "点击未被系统接受，已停止")
            var after = LuluAccessibilityService.observe()
            var verified = false
            for (attempt in 0 until 12) {
                delay(250)
                after = LuluAccessibilityService.observe()
                if (!after.connected || after.packageName !in packages) break
                if (after.visibleText.contains(step.getString("expectedText"))) { verified = true; break }
            }
            observations.put(JSONObject().put("step", i + 1).put("before", before.visibleText).put("after", after.visibleText)
                .put("packageName", after.packageName).put("capturedAt", after.capturedAt.toString()).put("verified", verified))
            if (!verified) return@withContext outcome(false, i, observations, "点击已发出，但未观察到预期文字；不重试点击，已停止")
        }
        outcome(true, steps.length(), observations, "所有步骤均观察到预期文字；这不证明屏幕外的业务已经完成")
    }
    private fun outcome(success: Boolean, completed: Int, evidence: JSONArray, note: String) = JSONObject()
        .put("success", success).put("status", if (success) "succeeded" else "waiting_user")
        .put("completedSteps", completed).put("observations", evidence).put("verification", note).toString()
}
