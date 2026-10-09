package de.omarfourati.redefluss.auth

import kotlin.test.*

class PasswordsTest {
    @Test fun hashAndVerify() {
        val h = Passwords.hash("richtig-langes-passwort")
        assertTrue(Passwords.verify("richtig-langes-passwort", h))
        assertFalse(Passwords.verify("falsch", h))
        assertFalse(Passwords.verify("x", "not-a-bcrypt-hash"))
    }

    @Test fun rules() {
        assertEquals("Das Passwort braucht mindestens 12 Zeichen.", Passwords.problem("kurz"))
        assertEquals("Das Passwort ist zu lang (höchstens 72 Bytes).", Passwords.problem("ä".repeat(37)))
        assertNull(Passwords.problem("genau-zwölf!"))
    }
}
