package dev.sysmoon.ferry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamClientTest {
    private val account = XtreamClient.Account("http://tv.example:8080", "ben smith", "p/a#ss?1+")

    @Test fun credentialsAreEncodedInLiveUrls() {
        assertEquals("http://tv.example:8080/live/ben%20smith/p%2Fa%23ss%3F1%2B/1234.ts", account.liveUrl("1234"))
        assertEquals("http://tv.example:8080/live/ben/pass/9.m3u8", XtreamClient.Account("http://tv.example:8080", "ben", "pass").liveUrl("9", "m3u8"))
    }

    @Test fun credentialsAreEncodedInQueries() {
        assertTrue(account.api("get_live_streams").endsWith("username=ben+smith&password=p%2Fa%23ss%3F1%2B&action=get_live_streams"))
    }

    @Test fun timeshiftKeepsEncodedCredentials() {
        val url = XtreamClient.timeshiftUrl(account.liveUrl("1234"), 0, 30 * 60_000L, "UTC")
        assertEquals("http://tv.example:8080/timeshift/ben%20smith/p%2Fa%23ss%3F1%2B/30/1970-01-01:00-00/1234.ts", url)
    }
}
