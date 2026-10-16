package com.jiacimu.lulu.data

/** Normalize transport formatting only; unknown identifiers still fail the real action switch. */
internal fun normalizeWorldAction(raw: String): String = raw.filterNot {
    it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt()
}.lowercase(java.util.Locale.ROOT)
