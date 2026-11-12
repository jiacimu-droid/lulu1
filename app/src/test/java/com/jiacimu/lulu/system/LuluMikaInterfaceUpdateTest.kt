package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluMikaInterfaceUpdateTest {
    private fun item(ref: String, name: String) =
        LuluMikaInterfaceUpdate.Element(ref, name, "Button", "id_${ref}", true, 500, 500)

    @Test fun sameUiDoesNotInventAnEffect() {
        val before = listOf(item("e1", "搜索"), item("e2", "笔记"))
        val d = LuluMikaInterfaceUpdate.between(before, before, true)
        assertFalse(d.full)
        assertEquals(2, d.unchanged)
        assertTrue(d.summary().contains("未发生"))
    }

    @Test fun localChangesAreDistinguishedFromNewElements() {
        val base = listOf(item("e1", "搜索"), item("e2", "笔记"), item("e3", "返回"))
        val now = listOf(item("e1", "搜索"), item("e2", "新笔记"), item("e3", "返回"))
        val d = LuluMikaInterfaceUpdate.between(base, now, true)
        assertFalse(d.full)
        assertEquals("e2", d.changed.single().ref)
        assertEquals(2, d.unchanged)
        assertTrue(d.summary().contains("新笔记"))
    }

    @Test fun lostBaseOrCompleteScreenChangeReturnsFullNotFabricatedDiff() {
        val old = listOf(item("e1", "一"), item("e2", "二"))
        val newer = listOf(item("e3", "新"), item("e4", "页面"))
        assertTrue(LuluMikaInterfaceUpdate.between(null, newer, false).full)
        assertTrue(LuluMikaInterfaceUpdate.between(old, newer, true).full)
    }

    @Test fun refsAreNotSilentlyReusedAsProofOfAction() {
        val before = listOf(item("e1", "点击搜索"), item("e2", "返回"))
        val after = listOf(item("e1", "点击搜索"), item("e2", "返回"))
        assertFalse(LuluMikaInterfaceUpdate.between(before, after, true).full)
        assertEquals(2, LuluMikaInterfaceUpdate.between(before, after, true).unchanged)
    }
}
