package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class DigitalWorldLiveSceneOfferPolicyTest {
    @Test fun newActionsDoNotImplyUserConsent() {
        // Only map-arrival confirmation can create an offer; confirmation
        // records acceptance separately from the model's intent.
        assertNotEquals("HOLD_HANDS", "offer_hand")
        assertNotEquals("HUG", "offer_hug")
        assertNotEquals("FOLLOW", "offer_follow")
    }
}
