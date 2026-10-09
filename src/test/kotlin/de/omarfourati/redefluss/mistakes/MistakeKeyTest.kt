package de.omarfourati.redefluss.mistakes

import kotlin.test.*

class MistakeKeyTest {
    @Test fun sameMistakeDifferentSpellingGivesSameKey() {
        val a = MistakeKey.normalize("artikel", "den ganzen Zeit", "die ganze Zeit")
        val b = MistakeKey.normalize("artikel", "  Den  ganzen Zeit. ", "„die ganze Zeit“")
        assertEquals(a, b)
        assertEquals("artikel|den ganzen zeit|die ganze zeit", a)
    }

    @Test fun categoryIsPartOfTheKey() {
        assertNotEquals(MistakeKey.normalize("artikel", "x", "y"), MistakeKey.normalize("kasus", "x", "y"))
    }

    @Test fun umlautsStayAndCaseFoldsGerman() {
        assertEquals("schreibung|über|über", MistakeKey.normalize("schreibung", "ÜBER", "über"))
    }

    @Test fun unknownOrMissingCategoryBecomesSonstiges() {
        assertEquals("sonstiges", MistakeKey.category("grammar"))
        assertEquals("sonstiges", MistakeKey.category(null))
        assertEquals("artikel", MistakeKey.category(" Artikel "))
        assertEquals(9, MistakeKey.CATEGORIES.size)
    }
}
