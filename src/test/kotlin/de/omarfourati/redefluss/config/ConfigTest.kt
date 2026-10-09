package de.omarfourati.redefluss.config

import kotlin.test.*

class ConfigTest {
    private val base = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://localhost:5432/redefluss",
        "DB_USER" to "redefluss", "DB_PASSWORD" to "pw",
        "JWT_SECRET" to "k".repeat(32), "SPEECH" to "fake",
    )

    @Test fun defaults() {
        val c = Config.from(base)
        assertEquals(8080, c.port)
        assertEquals("fake", c.speech)
        assertEquals("gpt-4o-mini", c.aiModel)
        assertEquals("gpt-4o-transcribe", c.transcribeModel)
        assertEquals("gpt-4o-mini-tts", c.ttsModel)
        assertEquals("coral", c.ttsVoice)
        assertEquals(300, c.turnsPerDay)
        assertNull(c.ownerEmail)
        assertFalse(c.ownerResetPassword)
    }

    @Test fun shortJwtSecretIsRejected() {
        assertFailsWith<ConfigException> { Config.from(base + ("JWT_SECRET" to "short")) }
    }

    @Test fun openAiNeedsAKey() {
        assertFailsWith<ConfigException> { Config.from(base + ("SPEECH" to "openai")) }
        assertFailsWith<ConfigException> { Config.from(base + mapOf("SPEECH" to "openai", "OPENAI_API_KEY" to "not-configured")) }
        assertEquals("openai", Config.from(base + mapOf("SPEECH" to "openai", "OPENAI_API_KEY" to "sk-test")).speech)
    }

    @Test fun unknownSpeechModeIsRejected() {
        assertFailsWith<ConfigException> { Config.from(base + ("SPEECH" to "azure")) }
    }

    @Test fun ownerAndLimitsFromEnv() {
        val c = Config.from(base + mapOf("OWNER_EMAIL" to " Omar@Example.de ", "OWNER_PASSWORD" to "x".repeat(12),
            "OWNER_RESET_PASSWORD" to "true", "TURNS_PER_DAY" to "5", "PORT" to "9000"))
        assertEquals("omar@example.de", c.ownerEmail)
        assertTrue(c.ownerResetPassword)
        assertEquals(5, c.turnsPerDay)
        assertEquals(9000, c.port)
    }

    @Test fun invalidNumberIsRejected() {
        assertFailsWith<ConfigException> { Config.from(base + ("TURNS_PER_DAY" to "viele")) }
    }
}
