package com.jiacimu.lulu

/** UI compatibility for legacy persisted JSON null values. */
fun String?.isNullOrBlank(): Boolean =
    this == null || isEmpty() || equals("null", ignoreCase = true) || all(Char::isWhitespace)
