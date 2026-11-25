package com.mawja.player.data.radio

import org.junit.Assert.*
import org.junit.Test

class RadioSelectionTest {
    @Test fun localIgnoresLegacyManualCountry() {
        assertEquals("OM", radioBrowseCountry(true, "US", "OM"))
        assertNull(radioBrowseCountry(true, "US", null))
    }
    @Test fun worldKeepsManualSelectionAndDoesNotSilentlyUseLocation() {
        assertEquals("US", radioBrowseCountry(false, "US", "OM"))
        assertNull(radioBrowseCountry(false, null, "OM"))
    }
}
