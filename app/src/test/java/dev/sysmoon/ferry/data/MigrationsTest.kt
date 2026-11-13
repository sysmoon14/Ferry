package dev.sysmoon.ferry.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationsTest {
    /** Every version from 1 to the current one has an upgrade step, in order, with no gaps. */
    @Test
    fun migrationsCoverEveryVersion() {
        val steps = FerryDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        val expected = (1 until FerryDatabase.VERSION).map { it to it + 1 }
        assertEquals(
            "Add a Migration for the new schema version (and list it in FerryDatabase.MIGRATIONS)",
            expected, steps,
        )
    }
}
