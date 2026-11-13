package dev.sysmoon.ferry.server

import dev.sysmoon.ferry.data.Settings
import dev.sysmoon.ferry.data.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ApiHelpersTest {
    // ---- Slow results computed in the background (guide search, football candidates)

    private fun await(results: BackgroundResults, key: String, work: suspend () -> JsonElement): JsonElement = runBlocking {
        withTimeout(5_000) {
            var value = results.get(key, work)
            while (value == null) { delay(10); value = results.get(key, work) }
            value
        }
    }

    @Test
    fun firstRequestStartsTheWorkAndLaterOnesGetTheResult() {
        val runs = AtomicInteger()
        val results = BackgroundResults(CoroutineScope(Dispatchers.Default))
        val work: suspend () -> JsonElement = { runs.incrementAndGet(); delay(50); JsonPrimitive("done") }
        assertNull("nothing yet on the first ask", results.get("k", work))
        assertEquals(JsonPrimitive("done"), await(results, "k", work))
        // The same key again is answered from the result, not by running the work again.
        assertEquals(JsonPrimitive("done"), results.get("k", work))
        assertEquals(1, runs.get())
    }

    @Test
    fun resultsExpireSoTheMapCannotGrowForever() {
        val runs = AtomicInteger()
        val results = BackgroundResults(CoroutineScope(Dispatchers.Default), ttlMs = 300)
        val work: suspend () -> JsonElement = { JsonPrimitive(runs.incrementAndGet()) }
        assertEquals(JsonPrimitive(1), await(results, "k", work))
        Thread.sleep(450)
        assertNull("the old result has been dropped", results.get("k", work))
        assertEquals(JsonPrimitive(2), await(results, "k", work))
    }

    @Test
    fun failedWorkIsReportedInsteadOfPollingForever() {
        val results = BackgroundResults(CoroutineScope(Dispatchers.Default))
        val value = await(results, "k") { error("guide database is busy") }
        assertEquals(JsonPrimitive("guide database is busy"), value.jsonObject["error"])
    }

    // ---- Source validation

    @Test
    fun sourceUrlsMustBeHttp() {
        assertThrows(IllegalArgumentException::class.java) { SourceInput(name = "Provider", url = "ftp://example.com/list.m3u").validated() }
        assertThrows(IllegalArgumentException::class.java) { SourceInput(name = " ", url = "http://example.com/list.m3u").validated() }
        assertThrows(IllegalArgumentException::class.java) { SourceInput(name = "Provider", url = "http://example.com", refreshHours = 0).validated() }
        val ok = SourceInput(name = " Provider ", url = " http://example.com/list.m3u", epgUrl = "  ").validated()
        assertEquals("Provider", ok.name)
        assertNull("a blank guide URL is no guide URL", ok.epgUrl)
    }

    @Test
    fun bbcSourcesCarryARegionAndNoLogin() {
        val bbc = SourceInput(name = "BBC", type = SourceType.BBC, url = "nowhere", username = "u", password = "p", refreshHours = 500).validated()
        assertNull(bbc.username)
        assertNull(bbc.password)
        assertTrue(bbc.refreshHours in 1..168)
    }

    // ---- Paired devices

    @Test
    fun pairedDevicesAreNamedFromTheirUserAgent() {
        assertEquals(
            "iPhone · Safari",
            ConfigServer.deviceLabel("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"),
        )
        assertEquals(
            "Android · Chrome",
            ConfigServer.deviceLabel("Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"),
        )
        assertEquals("Home Assistant", ConfigServer.deviceLabel("HomeAssistant/2026.11 aiohttp/3.10 Python/3.13"))
        assertEquals("Unnamed device", ConfigServer.deviceLabel(null))
    }

    @Test
    fun aTokensIdDoesNotGiveTheTokenAway() {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef"
        val id = Settings.tokenId(token)
        assertEquals(id, Settings.tokenId(token))
        assertEquals(12, id.length)
        assertFalse(token.contains(id))
        assertNotEquals(id, Settings.tokenId(token.reversed()))
    }
}
