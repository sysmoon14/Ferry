package dev.sysmoon.ferry

import dev.sysmoon.ferry.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun comparesVersions() {
        assertTrue(Updater.isNewer("0.2.0", "0.1.0"))
        assertTrue(Updater.isNewer("v1.0.0", "0.9.9"))
        assertTrue(Updater.isNewer("0.10.0", "0.9.0")) // numeric, not alphabetical
        assertTrue(Updater.isNewer("0.2.1", "0.2.0+dev.140"))
        assertFalse(Updater.isNewer("0.2.0", "0.2.0"))
        assertFalse(Updater.isNewer("0.1.0", "0.2.0"))
        // A nightly built after the tag is that release: not offered the same release again.
        assertFalse(Updater.isNewer("0.2.0", "0.2.0+dev.141"))
        assertFalse(Updater.isNewer("0.2", "0.2.0"))
        assertTrue(Updater.isNewer("0.2.0", "0.1.0+local"))
    }

    @Test
    fun parsesRelease() {
        val json = """
            {"tag_name":"v0.2.0","name":"Ferry 0.2.0","html_url":"https://github.com/sysmoon14/Ferry/releases/tag/v0.2.0",
             "body":"* Main menu by @x\n* HDR","assets":[
               {"name":"ferry-debug.apk","browser_download_url":"https://example/d.apk"},
               {"name":"ferry.apk","browser_download_url":"https://example/f.apk"}]}
        """.trimIndent()
        val r = Updater.parseRelease(json)!!
        assertEquals("0.2.0", r.version)
        assertEquals("Ferry 0.2.0", r.title)
        assertEquals("https://example/f.apk", r.apkUrl)
        assertNull(Updater.parseRelease("""{"tag_name":"v0.3.0","assets":[]}"""))
    }
}
