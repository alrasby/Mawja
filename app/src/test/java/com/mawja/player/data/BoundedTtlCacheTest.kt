package com.mawja.player.data

import org.junit.Assert.*
import org.junit.Test

class BoundedTtlCacheTest {
    @Test fun evictsLeastRecentlyUsedAndKeepsHardLimit() {
        val cache = BoundedTtlCache<String, String>(2, 1000) { 0 }
        cache["a"] = "A"; cache["b"] = "B"
        assertEquals("A", cache["a"])
        cache["c"] = "C"
        assertNull(cache["b"])
        assertEquals("A", cache["a"])
        assertEquals(2, cache.size())
    }
    @Test fun expiresAtBoundaryAndPrunesUnrelatedExpiredEntries() {
        var now = 0L
        val cache = BoundedTtlCache<String, String>(3, 10) { now }
        cache["a"] = "A"; now = 9
        assertEquals("A", cache["a"])
        now = 10
        cache["b"] = "B"
        assertNull(cache["a"])
        assertEquals(1, cache.size())
        now = 20
        assertNull(cache["b"])
        assertEquals(0, cache.size())
    }
    @Test fun replacementRestartsLifetime() {
        var now = 0L
        val cache = BoundedTtlCache<String, String>(1, 10) { now }
        cache["a"] = "old"; now = 9; cache["a"] = "new"; now = 10
        assertEquals("new", cache["a"])
        cache.remove("a")
        assertNull(cache["a"])
    }
}
