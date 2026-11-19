package local.music.collection

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CollectionRulesTest {
    @Test fun namesAreTrimmedAndKeysIgnoreDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("Night Mix", CollectionRules.name("  Night Mix  "))
            assertEquals("night mix", CollectionRules.key("Night Mix"))
            assertEquals("شب", CollectionRules.name("شب"))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun invalidNamesAreRejectedInsteadOfTruncated() {
        listOf("", "  ", "x".repeat(61), "hello\nworld", "zero\u0000").forEach {
            assertThrows(IllegalArgumentException::class.java) { CollectionRules.name(it) }
        }
        assertEquals(60, CollectionRules.name("x".repeat(60)).length)
    }
    @Test fun movesPreserveEveryEntryAndDoNotMutateSource() {
        val source = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), CollectionRules.moved(source, 2, 0))
        assertEquals(listOf("b", "c", "a"), CollectionRules.moved(source, 0, 2))
        assertEquals(listOf("a", "b", "c"), source)
        assertEquals(source, CollectionRules.moved(source, 1, 1))
    }
    @Test fun staleMoveIndicesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CollectionRules.moved(listOf(1), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { CollectionRules.moved(emptyList<Int>(), 0, 0) }
    }
}
