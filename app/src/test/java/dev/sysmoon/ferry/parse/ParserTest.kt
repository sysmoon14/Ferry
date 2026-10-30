package dev.sysmoon.ferry.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class ParserTest {
    private val playlist = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz"
        #EXTINF:-1 tvg-id="BBCOne.uk" tvg-name="BBC One" tvg-logo="http://logo/bbc1.png" group-title="UK, Entertainment" tvg-chno="101",BBC One HD
        http://stream.example/live/u/p/1.ts
        #EXTINF:-1 tvg-id="" group-title="News",Sky News, International
        #EXTVLCOPT:http-user-agent=Foo
        http://stream.example/live/u/p/2.m3u8
        #EXTINF:-1,Bare Channel
        #EXTGRP:Misc
        http://stream.example/3

        #EXTINF:-1 tvg-name="Fallback Name",
        http://stream.example/4
        http://orphan.example/no-extinf
    """.trimIndent()

    @Test fun parsesM3uPlus() {
        val entries = M3uParser.parse(BufferedReader(StringReader(playlist))).toList()
        assertEquals(4, entries.size)

        entries[0].let {
            assertEquals("BBC One HD", it.name)
            assertEquals("BBCOne.uk", it.tvgId)
            assertEquals("UK, Entertainment", it.group)
            assertEquals(101, it.number)
            assertEquals("http://logo/bbc1.png", it.logo)
            assertEquals("http://stream.example/live/u/p/1.ts", it.url)
        }
        entries[1].let {
            assertEquals("Sky News, International", it.name)
            assertNull(it.tvgId)
            assertEquals("News", it.group)
        }
        assertEquals("Misc", entries[2].group)
        assertEquals("Bare Channel", entries[2].name)
        assertEquals("Fallback Name", entries[3].name)
    }

    @Test fun parsesXmltvTimes() {
        assertEquals(1_793_390_400_000L, XmltvTime.parse("20261030200000 +0000"))
        assertEquals(1_793_386_800_000L, XmltvTime.parse("20261030200000 +0100"))
        assertEquals(1_793_390_400_000L, XmltvTime.parse("20261030200000"))
        assertEquals(1_793_386_800_000L, XmltvTime.parse("20261030200000+0100"))
        assertNull(XmltvTime.parse("garbage"))
        assertNull(XmltvTime.parse(null))
    }
}
