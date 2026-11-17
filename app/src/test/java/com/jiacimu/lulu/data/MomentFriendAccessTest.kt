package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class MomentFriendAccessTest {
    private val owner = MomentFriendAccess.OWNER
    private val oldCompanions = setOf("char:one", "char:two")

    @Test fun strangersAndSharedWorldDoNotGrantMomentAccess() {
        assertFalse(MomentFriendAccess.allowed("char:one", "char:two",
            emptySet(), emptySet(), oldCompanions))
        assertFalse(MomentFriendAccess.allowed("resident:r", "char:one",
            emptySet(), emptySet(), oldCompanions))
        assertFalse(MomentFriendAccess.allowed(owner, "resident:r",
            emptySet(), emptySet(), oldCompanions))
    }

    @Test fun ownerExistingCompanionsRetainAccessUntilExplicitUnfriend() {
        assertTrue(MomentFriendAccess.allowed(owner, "char:one",
            emptySet(), emptySet(), oldCompanions))
        assertFalse(MomentFriendAccess.allowed(owner, "char:one",
            emptySet(), setOf("char:one"), oldCompanions))
        assertTrue(MomentFriendAccess.allowed(owner, "char:two",
            emptySet(), setOf("char:one"), oldCompanions))
    }

    @Test fun explicitMutualFriendshipIsSymmetricAndUnrelatedToCommonFriends() {
        val pair = listOf("char:one", "resident:r").sorted().joinToString("\u001f")
        assertTrue(MomentFriendAccess.allowed("char:one", "resident:r",
            setOf(pair), emptySet(), oldCompanions))
        assertTrue(MomentFriendAccess.allowed("resident:r", "char:one",
            setOf(pair), emptySet(), oldCompanions))
        assertFalse(MomentFriendAccess.allowed("char:two", "resident:r",
            setOf(pair), emptySet(), oldCompanions))
    }

    @Test fun retiredResidentCannotRemainLiveBecauseOfAnOldLink() {
        val removed = "resident:retired"
        val savedCharacterIds = setOf("char:one", removed)
        // Even if an obsolete CharacterSettings snapshot survived deletion,
        // the native world's registered resident registry is authoritative.
        assertFalse(MomentFriendAccess.activeEndpoint(removed,
            savedCharacterIds, emptySet()))
        assertTrue(MomentFriendAccess.activeEndpoint(removed,
            savedCharacterIds, setOf(removed)))
        assertTrue(MomentFriendAccess.activeEndpoint("char:one",
            savedCharacterIds, emptySet()))
        assertFalse(MomentFriendAccess.activeEndpoint("resident:never-created",
            savedCharacterIds, emptySet()))
        assertTrue(MomentFriendAccess.activeEndpoint(owner,
            savedCharacterIds, emptySet()))
    }

    @Test fun knownCreatorCanViewOwnPostWithoutFriendship() {
        assertTrue(MomentFriendAccess.allowed("resident:r", "resident:r",
            emptySet(), emptySet(), emptySet()))
    }
}
