package de.omarfourati.redefluss

import kotlin.test.*

class OwnerCheckTest {
    @Test fun emptyDbWithoutOwnerEnvFails() {
        assertEquals("Kein Konto vorhanden – OWNER_EMAIL und OWNER_PASSWORD setzen.", ownerCheck(0, null, null))
        assertNotNull(ownerCheck(0, "a@b.de", null))
        assertNotNull(ownerCheck(0, null, "pw"))
    }

    @Test fun otherwiseFine() {
        assertNull(ownerCheck(0, "a@b.de", "pw"))
        assertNull(ownerCheck(1, null, null))
    }
}
