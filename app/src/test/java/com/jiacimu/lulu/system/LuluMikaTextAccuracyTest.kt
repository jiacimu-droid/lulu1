/* Lulu-specific regression tests for the Mika Apache-2.0 type-and-verify port. */
package com.jiacimu.lulu.system

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluMikaTextAccuracyTest {
    private class Field(
        var value: String? = "",
        var accept: Boolean = true,
        var reportWrite: Boolean = true,
    ) : LuluMikaTextAccuracy.TextField {
        var writes = 0
        override fun read(): String? = value
        override fun write(text: String): Boolean {
            writes++
            if (!accept) return false
            if (reportWrite) value = text
            return true
        }
    }

    @Test fun exactChineseTextReadbackIsVerified() = runBlocking {
        val field = Field("")
        assertEquals(LuluMikaTextAccuracy.Verification.MATCH,
            LuluMikaTextAccuracy.typeAndVerify(field, "小红书搜索"))
        assertEquals("小红书搜索", field.value)
        assertEquals(1, field.writes)
    }

    @Test fun alreadyFilledTextGetsARealRewriteNotPhantomSuccess() = runBlocking {
        val field = Field("小红书")
        assertEquals(LuluMikaTextAccuracy.Verification.MATCH,
            LuluMikaTextAccuracy.typeAndVerify(field, "小红书"))
        assertEquals("小红书", field.value)
        assertTrue(field.writes >= 2)
    }

    @Test fun unreadableOrRefusingFieldDoesNotClaimMatch() = runBlocking {
        assertEquals(LuluMikaTextAccuracy.Verification.UNKNOWN,
            LuluMikaTextAccuracy.typeAndVerify(Field(null), "搜索"))
        assertEquals(LuluMikaTextAccuracy.Verification.MISMATCH,
            LuluMikaTextAccuracy.typeAndVerify(Field("", accept = false), "搜索"))
    }

    @Test fun stuckFieldCanRemainUnknownButNeverClaimSuccess() = runBlocking {
        val field = Field("", reportWrite = false)
        assertEquals(LuluMikaTextAccuracy.Verification.MISMATCH,
            LuluMikaTextAccuracy.typeAndVerify(field, "搜索", reacted = { false }))
        assertEquals(LuluMikaTextAccuracy.Verification.UNKNOWN,
            LuluMikaTextAccuracy.typeAndVerify(field, "搜索", reacted = { true }))
    }

    @Test fun hintAndTypedTextConcatenationIsNotVerified() {
        assertTrue(LuluMikaTextAccuracy.ambiguousFieldReadback(
            "搜索内容  小红书", "搜索内容", "小红书"))
        assertFalse(LuluMikaTextAccuracy.ambiguousFieldReadback(
            "小红书", "搜索内容", "小红书"))
    }
}
