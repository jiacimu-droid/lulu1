package com.jiacimu.lulu

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AIRI-style discovery of expression and motion resources missing from model3.json.
 * Supplies a virtual manifest to the renderer without modifying the user's imported original.
 */
internal object Live2DModelManifestAdapter {
    fun normalizedSettings(settings: File): JSONObject {
        require(settings.isFile && settings.name.endsWith(".model3.json", true))
        val json = JSONObject(settings.readText())
        val refs = json.getJSONObject("FileReferences")
        for (key in listOf("Physics", "Pose", "DisplayInfo", "UserData")) {
            if (refs.isNull(key)) refs.remove(key)
        }
        val base = settings.parentFile ?: return json
        val expressions = refs.optJSONArray("Expressions") ?: JSONArray()
        val existingExpressions = (0 until expressions.length()).mapNotNull {
            expressions.optJSONObject(it)?.optString("File")
                ?.takeIf(String::isNotBlank)?.let { name -> File(base, name).canonicalPath }
        }.toMutableSet()
        val existingNames = (0 until expressions.length()).mapNotNull {
            expressions.optJSONObject(it)?.optString("Name")?.takeIf(String::isNotBlank)
        }.toMutableSet()
        base.walkTopDown().filter { it.isFile && it.name.endsWith(".exp3.json", true) }.forEach { file ->
            if (existingExpressions.add(file.canonicalPath)) {
                val filename = file.relativeTo(base).invariantSeparatorsPath
                val name = file.name.substringBeforeLast(".exp3.json")
                var safeName = name
                if (safeName in existingNames) safeName = filename.removeSuffix(".exp3.json")
                existingNames.add(safeName)
                expressions.put(JSONObject().put("Name", safeName).put("File", filename))
            }
        }
        if (expressions.length() > 0) refs.put("Expressions", expressions)

        val motions = refs.optJSONObject("Motions") ?: JSONObject()
        val existing = mutableSetOf<String>()
        val keys = motions.keys()
        while (keys.hasNext()) {
            val group = keys.next()
            val items = motions.optJSONArray(group) ?: continue
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optString("File")?.takeIf(String::isNotBlank)
                    ?.let { existing.add(File(base, it).canonicalPath) }
            }
        }
        val discovered = JSONArray()
        base.walkTopDown().filter { it.isFile && it.name.endsWith(".motion3.json", true) }
            .forEach { file ->
                if (existing.add(file.canonicalPath))
                    discovered.put(JSONObject().put("File", file.relativeTo(base).invariantSeparatorsPath))
            }
        if (discovered.length() > 0) motions.put("Lulu", discovered)
        if (motions.length() > 0) refs.put("Motions", motions)
        return json
    }
}
