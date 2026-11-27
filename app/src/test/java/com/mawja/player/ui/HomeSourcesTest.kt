package com.mawja.player.ui

import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import org.junit.Assert.*
import org.junit.Test

class HomeSourcesTest {
    @Test fun everyOnlineCombinationRemovesDisabledAndEmptySections() {
        val a = Track(id = "a", source = SourceType.AUDIUS, title = "A")
        val y = Track(id = "y", source = SourceType.YOUTUBE, title = "Y")
        val sections = listOf(HomeSection(1, listOf(a, y)), HomeSection(2, listOf(y)))
        for (audius in listOf(false, true)) for (youtube in listOf(false, true)) {
            val filtered = homeSectionsForSources(sections, audius, youtube)
            assertEquals(buildSet { if (audius) add("a"); if (youtube) add("y") },
                filtered.flatMap { it.items }.map { it.id }.toSet())
            assertTrue(filtered.all { it.items.isNotEmpty() })
            assertEquals(2, sections.size) // Cached original remains intact when toggled back.
            assertEquals(2, sections.first().items.size)
        }
    }
}
