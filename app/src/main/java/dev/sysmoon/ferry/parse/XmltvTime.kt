package dev.sysmoon.ferry.parse

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** XMLTV timestamps: "20261030193000 +0000", sometimes without the offset (assume UTC). */
object XmltvTime {
    private val withOffset = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val bare = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.length >= 14 } ?: return null
        return runCatching {
            if (s.length > 14) {
                val normalised = s.substring(0, 14) + " " + s.substring(14).trim()
                OffsetDateTime.parse(normalised, withOffset).toInstant().toEpochMilli()
            } else {
                LocalDateTime.parse(s, bare).toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        }.getOrNull()
    }
}
