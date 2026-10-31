package dev.sysmoon.ferry.data

/**
 * Free per-country XMLTV guides (epgshare01) used to fill in listings for sports channels the
 * provider's guide leaves empty, hidden groups included, so the football finder can see what
 * foreign channels are showing. Channels are matched to a feed by country (from the playlist's
 * "FR | " style group prefix or a ".fr" guide ID), then by guide ID or name.
 */
object SportsGuides {
    const val BEIN = "BEIN"

    fun feedUrl(code: String) = "https://epgshare01.online/epgshare01/epg_ripper_${code}1.xml.gz"

    /** Playlist country prefixes → epgshare01 file codes. */
    private val CODES = mapOf(
        "UK" to "UK", "GB" to "UK", "US" to "US", "USA" to "US", "CA" to "CA", "AU" to "AU", "NZ" to "NZ", "IE" to "IE",
        "FR" to "FR", "DE" to "DE", "IT" to "IT", "ES" to "ES", "PT" to "PT", "NL" to "NL", "BE" to "BE", "CH" to "CH",
        "AT" to "AT", "PL" to "PL", "TR" to "TR", "GR" to "GR", "SE" to "SE", "NO" to "NO", "DK" to "DK", "FI" to "FI",
        "BR" to "BR", "MX" to "MX", "AR" to "AR", "IN" to "IN", "ZA" to "ZA", "HU" to "HU", "RO" to "RO", "CZ" to "CZ",
        "HR" to "HR", "RS" to "RS", "BG" to "BG", "SK" to "SK", "SI" to "SI", "IL" to "IL", "CY" to "CY",
    )
    private val PREFIX = Regex("""^\s*([A-Za-z]{2,3})\s*[|:\-]\s""")

    /** epgshare01 code for a channel's country, or null if it can't be told. */
    fun country(name: String, group: String?, tvgId: String?): String? {
        listOfNotNull(group, name).forEach { s ->
            PREFIX.find(s)?.groupValues?.get(1)?.uppercase()?.let { CODES[it] }?.let { return it }
        }
        return tvgId?.substringAfterLast('.', "")?.uppercase()?.let { CODES[it] }
    }

    /** Country of a feed channel from its id ("beIN.Sports.1.fr" → FR). */
    fun feedCountry(id: String): String? = CODES[id.substringAfterLast('.', "").uppercase()]

    /** Guide IDs compared without punctuation or case: "beinsports1.fr" ≡ "beIN.Sports.1.fr". */
    fun idKey(id: String): String = id.lowercase().filter { it.isLetterOrDigit() }

    /** Channel names compared without the country prefix and quality tags. */
    fun nameKey(name: String): String = SkyMatcher.canon(name.replace(PREFIX, ""))
}
