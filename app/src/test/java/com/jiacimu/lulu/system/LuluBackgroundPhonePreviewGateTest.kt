package com.jiacimu.lulu.system

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Owner's read-only peeking never inherits another role's display or bypasses
 * the pause/lock/excluded-app protections. This does not simulate Android's
 * ImageReader or prove any real virtual display was created.
 */
class LuluBackgroundPhonePreviewGateTest {
    private fun check(
        requested: String = "jiang-du",
        owner: String = "jiang-du",
        id: Int = 7,
        pkg: String = "com.example.reader",
        connected: Boolean = true,
        paused: Boolean = false,
        consent: Boolean = true,
        locked: Boolean = false,
        excluded: Boolean = false,
    ) = LuluBackgroundPhonePreviewGate.requireAccess(
        requested, owner, id, pkg, connected, paused, consent, locked, excluded)

    private fun rejected(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("must reject: $fragment")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains(fragment))
        }
    }

    @Test fun ownerMayPeekOnlyAlreadyRunningVerifiedSession() {
        check()
        rejected("没有运行中的独立后台屏") { check(id = 0) }
        rejected("没有运行中的独立后台屏") { check(connected = false) }
        rejected("没有已授权的可查看应用") { check(pkg = "") }
    }

    @Test fun aRoleCannotPeekAnotherRolesBackgroundScreen() {
        rejected("另一角色") { check(requested = "different-role") }
        rejected("真实角色") { check(requested = "resident:npc") }
    }

    @Test fun revokedPermissionLockAndExcludedAppAlwaysRefuse() {
        rejected("暂停") { check(paused = true) }
        rejected("尚未授权") { check(consent = false) }
        rejected("锁屏") { check(locked = true) }
        rejected("已授权的可查看应用") { check(excluded = true) }
    }
}
