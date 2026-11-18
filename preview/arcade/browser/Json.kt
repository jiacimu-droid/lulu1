package org.json

import kotlin.js.JSON

// Small browser implementation of the org.json methods used by the shared Kotlin rules.
private fun raw(value: Any?): dynamic = when (value) {
    is JSONObject -> value.data
    is JSONArray -> value.data
    is Long -> value.toDouble()
    else -> value
}

private fun objectValue(value: dynamic): Boolean =
    value != null && jsTypeOf(value) == "object" && !(js("Array").isArray(value) as Boolean)
private fun arrayValue(value: dynamic): Boolean = js("Array").isArray(value) as Boolean

class JSONObject internal constructor(internal val data: dynamic, @Suppress("UNUSED_PARAMETER") wrap: Boolean) {
    constructor() : this(js("Object.create(null)"), true)
    constructor(source: String) : this(JSON.parse<dynamic>(source), true) {
        require(objectValue(data)) { "Expected JSON object" }
    }

    fun put(name: String, value: Any?): JSONObject {
        data[name] = raw(value)
        return this
    }
    fun has(name: String): Boolean = js("Object").prototype.hasOwnProperty.call(data, name) as Boolean
    fun keys(): Iterator<String> = (js("Object").keys(data) as Array<String>).iterator()
    private fun required(name: String): dynamic = data[name] ?: error("Missing JSON field: $name")
    fun get(name: String): Any? = required(name)
    fun opt(name: String): Any? = data[name]
    fun getString(name: String): String = required(name).toString()
    fun getInt(name: String): Int = getString(name).toInt()
    fun getLong(name: String): Long = getString(name).toLong()
    fun getBoolean(name: String): Boolean = getString(name).toBooleanStrict()
    fun getJSONObject(name: String): JSONObject = optJSONObject(name) ?: error("Expected object: $name")
    fun getJSONArray(name: String): JSONArray = optJSONArray(name) ?: error("Expected array: $name")
    fun optJSONObject(name: String): JSONObject? =
        if (objectValue(data[name])) JSONObject(data[name], true) else null
    fun optJSONArray(name: String): JSONArray? =
        if (arrayValue(data[name])) JSONArray(data[name], true) else null
    fun optString(name: String, fallback: String = ""): String =
        if (data[name] == null) fallback else data[name].toString()
    fun optInt(name: String, fallback: Int = 0): Int = optString(name).toIntOrNull() ?: fallback
    fun optLong(name: String, fallback: Long = 0L): Long = optString(name).toLongOrNull() ?: fallback
    fun optBoolean(name: String, fallback: Boolean = false): Boolean =
        optString(name).toBooleanStrictOrNull() ?: fallback
    override fun toString(): String = JSON.stringify(data)

    companion object {
        val NULL: Any? = null
    }
}

class JSONArray internal constructor(internal val data: dynamic, @Suppress("UNUSED_PARAMETER") wrap: Boolean) {
    constructor() : this(js("[]"), true)
    constructor(items: Collection<*>) : this(items.map { raw(it) }.toTypedArray(), true)

    fun length(): Int = data.length as Int
    fun put(value: Any?): JSONArray {
        data.push(raw(value))
        return this
    }
    fun getJSONObject(index: Int): JSONObject = optJSONObject(index) ?: error("Expected array object: $index")
    fun optJSONObject(index: Int): JSONObject? =
        if (objectValue(data[index])) JSONObject(data[index], true) else null
    fun getInt(index: Int): Int = optString(index).toInt()
    fun getString(index: Int): String = optString(index)
    fun optInt(index: Int, fallback: Int = 0): Int = optString(index).toIntOrNull() ?: fallback
    fun optString(index: Int, fallback: String = ""): String =
        if (data[index] == null) fallback else data[index].toString()
}
