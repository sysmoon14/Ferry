package dev.sysmoon.ferry.parse

import java.io.BufferedReader

data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val number: Int?,
    val catchupDays: Int?,
)

/**
 * Streaming M3U / M3U Plus parser. Lazily yields entries so 50k-line provider
 * playlists never sit fully in memory.
 */
object M3uParser {
    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(reader: BufferedReader): Sequence<M3uEntry> = sequence {
        var info: String? = null
        var extGroup: String? = null
        for (raw in reader.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> { info = line; extGroup = null }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit // #EXTM3U, #EXTVLCOPT, #KODIPROP etc.
                else -> {
                    val i = info
                    if (i != null) yield(entry(i, line, extGroup))
                    info = null
                    extGroup = null
                }
            }
        }
    }

    internal fun entry(info: String, url: String, extGroup: String?): M3uEntry {
        val attrs = attr.findAll(info).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        // Name is everything after the first comma once quoted attributes are removed,
        // so commas inside group-title="A, B" or in the name itself are handled.
        val name = attr.replace(info, "").substringAfter(',', "").trim()
        return M3uEntry(
            name = name.ifBlank { attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: url },
            url = url,
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            group = (attrs["group-title"] ?: extGroup)?.takeIf { it.isNotBlank() },
            number = (attrs["tvg-chno"] ?: attrs["channel-number"])?.toIntOrNull(),
            catchupDays = attrs["catchup-days"]?.toIntOrNull(),
        )
    }
}
