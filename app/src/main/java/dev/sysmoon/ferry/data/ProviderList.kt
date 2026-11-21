package dev.sysmoon.ferry.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The provider's own lists, as the source sends them: its categories in playlist order, and every
 * stream in each, duplicates and hidden channels included. A fallback for when Ferry's own lists
 * (merged duplicates, the Sky layout, hidden groups) leave out a stream you want.
 *
 * Ferry's rows reshape the playlist (a merged row takes the group and position of its first copy),
 * so each refresh also saves the playlist's own order, one "group<TAB>channel id" line per entry.
 * Before a source's first refresh with this in place, its order is read back from the rows.
 */
class ProviderList(private val db: FerryDatabase, private val dir: File) {
    /** One playlist entry: the category the source put it in, and its channel row. */
    data class Entry(val group: String?, val id: String)

    data class Category(val sourceId: Long, val source: String, val name: String?, val count: Int)

    /** A stream in a category, with the category the source gave it (merged rows may show another). */
    data class Stream(val channel: ChannelEntity, val group: String?)

    private class Cached(val modified: Long, val entries: List<Entry>, val groupById: Map<String, String?>)
    private val cache = HashMap<Long, Cached>()

    private fun file(sourceId: Long) = File(dir, "$sourceId.tsv")

    /** Called after each refresh with the playlist's entries in order. */
    @Synchronized
    fun save(sourceId: Long, entries: List<Entry>) {
        dir.mkdirs()
        val tmp = File(dir, "$sourceId.tsv.tmp")
        tmp.bufferedWriter().use { out -> entries.forEach { out.write(encode(it)); out.write("\n") } }
        tmp.renameTo(file(sourceId))
        cache.remove(sourceId)
    }

    @Synchronized
    fun delete(sourceId: Long) {
        file(sourceId).delete()
        cache.remove(sourceId)
    }

    private suspend fun entries(sourceId: Long): Cached = withContext(Dispatchers.IO) { load(sourceId) }

    private suspend fun load(sourceId: Long): Cached {
        val f = file(sourceId)
        val modified = if (f.exists()) f.lastModified() else 0L
        synchronized(this) { cache[sourceId]?.takeIf { it.modified == modified }?.let { return it } }
        val entries = if (modified > 0) {
            f.bufferedReader().useLines { lines -> decodeAll(lines) }
        } else {
            db.channels().playlistOrder(sourceId).map { Entry(it.groupTitle?.takeIf(String::isNotBlank), it.id) }
        }
        val loaded = Cached(modified, entries, entries.associate { it.id to it.group })
        // A file-less source is read from the rows each time (they change with every refresh).
        if (modified > 0) synchronized(this) { cache[sourceId] = loaded }
        return loaded
    }

    /** Every source's categories, sources in the order they were added. */
    suspend fun categories(): List<Category> = db.sources().all().flatMap { src ->
        categoriesOf(entries(src.id).entries).map { (name, count) -> Category(src.id, src.name, name, count) }
    }

    /** A category's streams in the source's order. [group] null is the entries without one. */
    suspend fun streams(sourceId: Long, group: String?): List<Stream> = withContext(Dispatchers.IO) {
        val ids = entries(sourceId).entries.filter { it.group == group }.map { it.id }
        val rows = ids.chunked(500).flatMap { db.channels().byIds(it) }.associateBy { it.id }
        ids.mapNotNull { id -> rows[id]?.let { Stream(it, group) } }
    }

    /** The category the source put [channel] in. */
    suspend fun groupOf(channel: ChannelEntity): String? {
        val groups = entries(channel.sourceId).groupById
        return if (channel.id in groups) groups[channel.id] else channel.groupTitle
    }

    companion object {
        /** Categories in order of first appearance, with how many entries each has. */
        fun categoriesOf(entries: List<Entry>): List<Pair<String?, Int>> {
            val counts = LinkedHashMap<String?, Int>()
            entries.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
            return counts.map { (name, count) -> name to count }
        }

        /** "group<TAB>id"; a tab or line break in a category name becomes a space. Empty = none. */
        fun encode(entry: Entry): String =
            entry.group.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') + "\t" + entry.id

        fun decodeAll(lines: Sequence<String>): List<Entry> {
            // One String per category name, not one per line: 50k entries share a few hundred.
            val names = HashMap<String, String>()
            return lines.mapNotNull { line ->
                val tab = line.lastIndexOf('\t')
                if (tab < 0) return@mapNotNull null
                val group = line.substring(0, tab).takeIf { it.isNotBlank() }?.let { names.getOrPut(it) { it } }
                Entry(group, line.substring(tab + 1))
            }.toList()
        }
    }
}
