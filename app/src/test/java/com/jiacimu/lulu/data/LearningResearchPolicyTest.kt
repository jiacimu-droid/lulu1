package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LearningResearchPolicyTest {
    @Test fun diverseDomainsBeforeSecondHitFromSameSite() {
        val items = listOf(
            WebSearchHit("A", "https://example.org/a", ""),
            WebSearchHit("B", "https://example.org/b", ""),
            WebSearchHit("C", "https://another.org/c", ""),
            WebSearchHit("A duplicate", "https://example.org/a", "")
        )
        assertEquals(listOf("A", "C", "B"),
            LearningResearchPolicy.diverseCandidates(items).map { it.title })
    }
}
