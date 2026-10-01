package com.karin.streamtv.util

import org.junit.Assert.*
import org.junit.Test

class TempSiteHelperTest {

    @Test
    fun normalizeUrl_addsHttps() {
        assertEquals("https://ejemplo.com", TempSiteHelper.normalizeUrl("ejemplo.com"))
        assertEquals("https://ejemplo.com/a", TempSiteHelper.normalizeUrl("https://ejemplo.com/a/"))
    }

    @Test
    fun isValidHttpUrl_rejectsGarbage() {
        assertFalse(TempSiteHelper.isValidHttpUrl(""))
        assertFalse(TempSiteHelper.isValidHttpUrl("htp:/x"))
        assertFalse(TempSiteHelper.isValidHttpUrl("solo-palabras"))
        assertTrue(TempSiteHelper.isValidHttpUrl("https://jkanime.net"))
        assertTrue(TempSiteHelper.isValidHttpUrl("mundodonghua.com/lista"))
    }

    @Test
    fun buildTempSite_marksTemporary() {
        val site = TempSiteHelper.buildTempSite("", "https://ejemplo.com")
        assertNotNull(site)
        assertTrue(site!!.isTemporary)
        assertEquals("https://ejemplo.com", site.url)
        assertTrue(site.name.isNotBlank())
    }

    @Test
    fun buildTempSite_returnsNullOnBadUrl() {
        assertNull(TempSiteHelper.buildTempSite("X", ""))
        assertNull(TempSiteHelper.buildTempSite("X", "htp:/x"))
    }

    @Test
    fun deriveName_usesHostWhenBlank() {
        assertEquals("Ejemplo", TempSiteHelper.deriveName("", "https://www.ejemplo.com/a"))
        assertEquals("MiPágina", TempSiteHelper.deriveName("  MiPágina  ", "https://x.com"))
    }
}
