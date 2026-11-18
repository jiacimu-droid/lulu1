@file:OptIn(ExperimentalJsExport::class)

package com.jiacimu.lulu.games

import org.json.JSONObject

@JsExport
fun arcadeRequest(message: String): String = previewResponse(message)

@JsExport
fun arcadeSave(session: String): String = previewSave(session)

@JsExport
fun arcadeRestore(session: String, save: String): Boolean =
    runCatching { previewRestore(session, JSONObject(save)) }.getOrDefault(false)
