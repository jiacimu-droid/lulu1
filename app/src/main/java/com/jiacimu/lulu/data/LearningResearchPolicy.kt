package com.jiacimu.lulu.data

import java.net.URI

/** Prefer independent domains before multiple hits from the same website. */
internal object LearningResearchPolicy {
    fun diverseCandidates(hits: List<WebSearchHit>): List<WebSearchHit> {
        val unique = hits.distinctBy { it.url }
        val primary = unique.distinctBy { runCatching { URI(it.url).host.lowercase() }.getOrDefault(it.url) }
        return primary + unique.filterNot { it.url in primary.map(WebSearchHit::url) }
    }
}
