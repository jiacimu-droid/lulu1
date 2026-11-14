package com.jiacimu.lulu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scene's literary adapter must retain an authored resident's identity. */
class WorldSceneParticipantProfileGenderTest {
    @Test fun femaleCafeWorkerIsIntroducedAsFemaleNotInferredMaleFromJob() {
        val text = WorldSceneParticipantProfile.format(
            WorldSceneParticipantProfile.Profile(
                id = "resident:cafe-girl",
                name = "岑灯",
                gender = "女",
                publicIdentity = "咖啡店店员",
                authoredIdentity = "女性咖啡师",
            ),
            forNarrator = true,
        )
        assertTrue(text.contains("姓名=岑灯"))
        assertTrue(text.contains("明确性别=女"))
        assertTrue(text.contains("登记的公开身份=咖啡店店员"))
        assertTrue(text.contains("本人明确身份设定"))
        assertTrue(text.contains("女性咖啡师"))
        assertFalse(text.contains("岑灯先生"))
        assertTrue(WorldSceneParticipantProfile.instruction.contains("先生/女士"))
    }

    @Test fun missingGenderIsUnknownInsteadOfDefaultMale() {
        val text = WorldSceneParticipantProfile.format(
            WorldSceneParticipantProfile.Profile(id = "role:x", name = "阿未"),
            forNarrator = false,
        )
        assertTrue(text.contains("明确性别=未单列"))
        assertFalse(text.contains("明确性别=男"))
        assertFalse(text.contains("明确性别=女"))
    }
}
