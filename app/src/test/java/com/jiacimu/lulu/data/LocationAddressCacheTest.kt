package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class LocationAddressCacheTest {
    private val a = LocationAddressCache.Key(1.0, 2.0, "zh-CN")
    private val b = LocationAddressCache.Key(3.0, 4.0, "zh-CN")

    @Test fun slowLookupIsQueuedOnceAndNeverDelaysCaller() {
        val queued = mutableListOf<() -> Unit>()
        val cache = LocationAddressCache { queued += it }
        var resolved = 0
        repeat(30) {
            assertEquals("", cache.peekOrRefresh(a) { resolved++; "地址A" })
        }
        assertEquals(0, resolved)
        assertEquals(1, queued.size)
        queued.removeAt(0).invoke()
        assertEquals("地址A", cache.peekOrRefresh(a) { error("must use cache") })
        assertEquals(1, resolved)
        assertTrue(queued.isEmpty())
    }

    @Test fun changedCoordinatesAndLocaleNeverReuseAnOldAddress() {
        val queued = mutableListOf<() -> Unit>()
        val cache = LocationAddressCache { queued += it }
        cache.peekOrRefresh(a) { "地址A" }
        assertEquals("", cache.peekOrRefresh(b) { "地址B" })
        assertEquals(1, queued.size)
        queued.removeAt(0).invoke()
        assertEquals("", cache.peekOrRefresh(b) { "地址B" })
        queued.removeAt(0).invoke()
        assertEquals("地址B", cache.peekOrRefresh(b) { error("cached") })
        assertEquals("", cache.peekOrRefresh(b.copy(locale = "en")) { "B" })
    }

    @Test fun failedResolverAndRejectedExecutorDoNotBreakChatOrStickTheFlight() {
        val queued = mutableListOf<() -> Unit>()
        val cache = LocationAddressCache { queued += it }
        assertEquals("", cache.peekOrRefresh(a) { error("geocoder offline") })
        queued.removeAt(0).invoke()
        cache.peekOrRefresh(a) { "recovered" }
        queued.removeAt(0).invoke()
        assertEquals("recovered", cache.peekOrRefresh(a) { error("cached") })
        val rejected = LocationAddressCache { error("executor unavailable") }
        repeat(2) { assertEquals("", rejected.peekOrRefresh(a) { "never" }) }
    }
}
