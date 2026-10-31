package dev.sysmoon.ferry.parse

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class XmltvProgramme(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/**
 * Streaming XMLTV parser. Pass a fresh XmlPullParser (android.util.Xml.newPullParser()
 * on device). Calls [onProgramme] for each programme whose channel passes [accept],
 * so whole-country guides can be filtered down to the channels you actually have.
 */
object XmltvParser {
    fun parse(
        input: InputStream,
        parser: XmlPullParser,
        accept: (String) -> Boolean = { true },
        /** Called for each <channel> (they come before the programmes) with its id and display names. */
        onChannel: ((id: String, names: List<String>) -> Unit)? = null,
        onProgramme: (XmltvProgramme) -> Unit,
    ) {
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "channel" && onChannel != null) {
                val id = parser.getAttributeValue(null, "id")
                val names = mutableListOf<String>()
                val depth = parser.depth
                while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
                    if (parser.eventType == XmlPullParser.END_DOCUMENT) break
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    if (parser.name == "display-name") names += parser.nextText().trim() else skip(parser)
                }
                if (id != null) onChannel(id, names)
            } else if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")
                val start = XmltvTime.parse(parser.getAttributeValue(null, "start"))
                val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"))
                if (channel != null && start != null && stop != null && accept(channel)) {
                    readProgramme(parser, channel, start, stop)?.let(onProgramme)
                } else {
                    skip(parser)
                }
            }
            event = parser.next()
        }
    }

    private fun readProgramme(p: XmlPullParser, channel: String, start: Long, stop: Long): XmltvProgramme? {
        var title: String? = null
        var desc: String? = null
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "title" -> if (title == null) title = p.nextText().trim() else skip(p)
                "desc" -> if (desc == null) desc = p.nextText().trim() else skip(p)
                else -> skip(p)
            }
        }
        return title?.takeIf { it.isNotEmpty() }?.let { XmltvProgramme(channel, start, stop, it, desc) }
    }

    private fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (p.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
