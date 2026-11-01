package dev.sysmoon.ferry.data

/**
 * Free per-country XMLTV guides (epgshare01) used to fill in listings for sports channels the
 * provider's guide leaves empty, hidden groups included, so the football finder can see what
 * foreign channels are showing. Channels are matched to a feed by country (from the playlist's
 * "FR | " style group prefix or a ".fr" guide ID), then by guide ID or name.
 */
object SportsGuides {
    const val BEIN = "BEIN"

    const val INDEX_URL = "https://epgshare01.online/epgshare01/"
    fun fileUrl(file: String) = "https://epgshare01.online/epgshare01/epg_ripper_$file.xml.gz"

    /** File codes in epgshare01's directory listing ("UK1", "US_SPORTS1", "BEIN1"…). */
    fun parseIndex(html: String): Set<String> =
        Regex("""epg_ripper_([A-Za-z0-9_]+)\.xml\.gz""").findAll(html).map { it.groupValues[1] }.toSet()

    /**
     * The files to read for [code]: its numbered country files ("UK1", "UK2") and any sports file
     * ("US_SPORTS1"), at most three. Without the listing, just "<code>1" (the usual name).
     */
    fun filesFor(code: String, index: Set<String>?): List<String> {
        if (index == null) return listOf("${code}1")
        val own = index.filter { it.matches(Regex("${code}\\d+")) }.sorted()
        val sports = index.filter { it.matches(Regex("${code}_SPORTS\\d*")) }.sorted()
        return (sports + own).take(3)
    }

    /** Playlist country prefixes → epgshare01 file codes. */
    private val CODES = mapOf(
        "UK" to "UK", "GB" to "UK", "US" to "US", "USA" to "US", "CA" to "CA", "AU" to "AU", "NZ" to "NZ", "IE" to "IE",
        "FR" to "FR", "DE" to "DE", "IT" to "IT", "ES" to "ES", "PT" to "PT", "NL" to "NL", "BE" to "BE", "CH" to "CH",
        "AT" to "AT", "PL" to "PL", "TR" to "TR", "GR" to "GR", "SE" to "SE", "NO" to "NO", "DK" to "DK", "FI" to "FI",
        "BR" to "BR", "MX" to "MX", "AR" to "AR", "IN" to "IN", "ZA" to "ZA", "HU" to "HU", "RO" to "RO", "CZ" to "CZ",
        "HR" to "HR", "RS" to "RS", "BG" to "BG", "SK" to "SK", "SI" to "SI", "IL" to "IL", "CY" to "CY",
    )
    private val PREFIX = Regex("""^\s*([A-Za-z]{2,3})\s*[|:\-]\s""")
    /** "SuperSport | ZA", "AU | Optus Live" style group suffix. */
    private val SUFFIX = Regex("""[|:\-]\s*([A-Za-z]{2,3})\s*$""")
    /** Groups named by country ("Norway Locals", "Bosnia", "Netherlands Locals"). */
    private val COUNTRY_WORDS = mapOf(
        "united kingdom" to "UK", "ireland" to "IE", "united states" to "US", "usa" to "US", "canada" to "CA",
        "australia" to "AU", "new zealand" to "NZ", "france" to "FR", "germany" to "DE", "deutschland" to "DE",
        "italy" to "IT", "italia" to "IT", "spain" to "ES", "espana" to "ES", "portugal" to "PT",
        "netherlands" to "NL", "holland" to "NL", "belgium" to "BE", "switzerland" to "CH", "austria" to "AT",
        "poland" to "PL", "turkey" to "TR", "greece" to "GR", "sweden" to "SE", "norway" to "NO", "denmark" to "DK",
        "finland" to "FI", "brazil" to "BR", "mexico" to "MX", "argentina" to "AR", "india" to "IN",
        "south africa" to "ZA", "hungary" to "HU", "romania" to "RO", "czech" to "CZ", "croatia" to "HR",
        "serbia" to "RS", "bulgaria" to "BG", "slovakia" to "SK", "slovenia" to "SI", "israel" to "IL", "cyprus" to "CY",
    )
    /** Arabic-world broadcasters: "AR:" on these means Arabic, not Argentina. */
    private val ARABIC = Regex("""(?i)\b(bein|ssc|alkass|al kass|ad sports|abu dhabi|dubai sports|osn|shahid|arabic)\b""")

    /** epgshare01 code for a channel's country, or null if it can't be told. */
    fun country(name: String, group: String?, tvgId: String?): String? {
        if (ARABIC.containsMatchIn(name + " " + group.orEmpty())) return null // beIN's own feed covers these
        listOfNotNull(group, name).forEach { s ->
            PREFIX.find(s)?.groupValues?.get(1)?.uppercase()?.let { CODES[it] }?.let { return it }
        }
        group?.let { g ->
            SUFFIX.find(g)?.groupValues?.get(1)?.uppercase()?.let { CODES[it] }?.let { return it }
            val lower = g.lowercase()
            COUNTRY_WORDS.entries.firstOrNull { (w, _) -> Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(lower) }?.let { return it.value }
        }
        return tvgId?.substringAfterLast('.', "")?.uppercase()?.let { CODES[it] }
    }

    /** Where Premier League / Champions League rights holders are: fetched first. */
    val FOOTBALL_PRIORITY = listOf("UK", "IE", "ES", "PT", "FR", "IT", "DE", "NL", "NO", "SE", "DK", "ZA", "AU", "US",
        "CA", "TR", "GR", "BE", "PL", "AT", "CH", "NZ", "IN")

    /** Country of a feed channel from its id ("beIN.Sports.1.fr" → FR). */
    fun feedCountry(id: String): String? = CODES[id.substringAfterLast('.', "").uppercase()]

    /** Guide IDs compared without punctuation or case: "beinsports1.fr" ≡ "beIN.Sports.1.fr". */
    fun idKey(id: String): String = id.lowercase().filter { it.isLetterOrDigit() }

    /** Channel names compared without the country prefix and quality tags. */
    fun nameKey(name: String): String = SkyMatcher.canon(name.replace(PREFIX, ""))
}
