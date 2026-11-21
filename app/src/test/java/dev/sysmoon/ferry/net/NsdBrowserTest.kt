package dev.sysmoon.ferry.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NsdBrowserTest {
    @Test fun ipv6HostsAreBracketedWithoutZone() {
        assertEquals("10.22.4.62", NsdBrowser.urlHost("10.22.4.62"))
        assertEquals("[fe80::1]", NsdBrowser.urlHost("fe80::1%wlan0"))
        assertEquals("[2a00:23c8::5]", NsdBrowser.urlHost("2a00:23c8::5"))
        assertEquals("http://[2a00:23c8::5]:8765", NsdBrowser.Found("Snug", "2a00:23c8::5", 8765, null).url)
    }

    @Test fun ipv4IsPreferredThenRoutableIpv6() {
        assertEquals("10.22.4.62", NsdBrowser.pickHost(listOf("fe80::1%wlan0", "2a00::5", "10.22.4.62")))
        assertEquals("2a00::5", NsdBrowser.pickHost(listOf("fe80::1%wlan0", "2a00::5")))
        assertEquals("fe80::1%wlan0", NsdBrowser.pickHost(listOf("fe80::1%wlan0")))
        assertNull(NsdBrowser.pickHost(emptyList()))
    }
}
