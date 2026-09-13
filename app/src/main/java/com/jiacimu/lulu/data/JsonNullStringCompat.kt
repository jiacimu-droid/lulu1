package com.jiacimu.lulu.data

/** Treat legacy JSONObject.NULL -> optString() values as absent instead of the literal text "null". */
fun String.isNotBlank(): Boolean =
    isNotEmpty() && !equals("null", ignoreCase = true) && any { character -> !character.isWhitespace() }

fun String?.isNullOrBlank(): Boolean = this == null || !this.isNotBlank()
