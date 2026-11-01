package dev.sysmoon.ferry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsGuidesTest {
    @Test fun countries() {
        assertEquals("ZA", SportsGuides.country("SuperSport Premier League", "SuperSport | ZA", null))
        assertEquals("NO", SportsGuides.country("NO: V Sport 1", "Norway Locals", null))
        assertEquals("NL", SportsGuides.country("Viaplay TV", "Netherlands Locals", null))
        assertEquals("AU", SportsGuides.country("Optus Sport 1 FHD", "AU | Optus Live", null))
        // "AR:" on beIN means Arabic (beIN's own feed), not Argentina.
        assertNull(SportsGuides.country("AR: beIN Sports 1 HD", "Arabic", null))
        assertEquals("AR", SportsGuides.country("AR: TyC Sports", "Argentina", null))
    }

    @Test fun index() {
        val html = """<a href="epg_ripper_UK1.xml.gz">x</a> <a href="epg_ripper_UK2.xml.gz">
            <a href="epg_ripper_US2.xml.gz"> <a href="epg_ripper_US_SPORTS1.xml.gz"> <a href="epg_ripper_BEIN1.xml.gz">"""
        val idx = SportsGuides.parseIndex(html)
        assertEquals(listOf("UK1", "UK2"), SportsGuides.filesFor("UK", idx))
        assertEquals(listOf("US_SPORTS1", "US2"), SportsGuides.filesFor("US", idx))
        assertEquals(listOf("BEIN1"), SportsGuides.filesFor("BEIN", idx))
        assertEquals(emptyList<String>(), SportsGuides.filesFor("CA", idx))
        assertEquals(listOf("CA1"), SportsGuides.filesFor("CA", null))
    }
}
