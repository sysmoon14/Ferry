package dev.sysmoon.ferry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProviderListTest {
    private fun e(group: String?, id: String) = ProviderList.Entry(group, id)

    @Test fun categoriesKeepThePlaylistOrderAndCountEveryEntry() {
        val entries = listOf(
            e("UK | Regionals", "a"), e("UK | Entertainment", "b"), e("UK | Regionals", "c"),
            e(null, "d"), e("UK | Entertainment", "e"), e("UK | Regionals", "f"),
        )
        assertEquals(
            listOf("UK | Regionals" to 3, "UK | Entertainment" to 2, null to 1),
            ProviderList.categoriesOf(entries),
        )
    }

    @Test fun entriesSurviveTheFileFormat() {
        val entries = listOf(e("UK | Sport", "1f2e3d4c5b6a7980"), e(null, "00aa"), e("Odd\tname\nhere", "00bb-2"))
        val read = ProviderList.decodeAll(entries.map(ProviderList::encode).asSequence())
        assertEquals(listOf(e("UK | Sport", "1f2e3d4c5b6a7980"), e(null, "00aa"), e("Odd name here", "00bb-2")), read)
    }

    @Test fun blankCategoriesReadAsNoneAndBrokenLinesAreSkipped() {
        val read = ProviderList.decodeAll(sequenceOf("\tabc", "   \tdef", "no tab here", "UK\tghi"))
        assertEquals(listOf(e(null, "abc"), e(null, "def"), e("UK", "ghi")), read)
    }

    @Test fun aCategoryNameIsSharedNotCopiedPerLine() {
        val read = ProviderList.decodeAll(sequenceOf(String(charArrayOf('U', 'K')) + "\ta", String(charArrayOf('U', 'K')) + "\tb"))
        assertSame(read[0].group, read[1].group)
    }
}
