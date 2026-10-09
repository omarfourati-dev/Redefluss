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
        assertEquals(7, c.vocabPerDay)
        assertEquals(60, c.vocabReviewsPerDay)
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

    @Test fun pronunciationDefaults() {
        val c = Config.from(base)
        assertEquals("fake", c.pronunciation)
        assertEquals("germanywestcentral", c.azureRegion)
        assertEquals(16200, c.azureSecondsPerMonth)
        assertEquals(100, c.pronunciationsPerDay)
        assertNull(c.azureKey)
        assertEquals("azure", Config.from(base + mapOf("SPEECH" to "openai", "OPENAI_API_KEY" to "sk-test")).pronunciation)
    }

    @Test fun azurePronunciationWithoutKeyIsAllowed() {
        val c = Config.from(base + ("PRONUNCIATION" to "azure"))
        assertEquals("azure", c.pronunciation)
        assertNull(c.azureKey)
        assertEquals("k1", Config.from(base + mapOf("PRONUNCIATION" to "azure", "AZURE_SPEECH_KEY" to " k1 ")).azureKey)
        assertFailsWith<ConfigException> { Config.from(base + ("PRONUNCIATION" to "google")) }
    }

    @Test fun pronunciationLimitsAreClampedToZero() {
        val c = Config.from(base + mapOf("AZURE_SECONDS_PER_MONTH" to "-5", "PRONUNCIATIONS_PER_DAY" to "-1"))
        assertEquals(0, c.azureSecondsPerMonth)
        assertEquals(0, c.pronunciationsPerDay)
    }

    @Test fun vocabPerDayIsClamped() {
        assertEquals(10, Config.from(base + ("VOCAB_PER_DAY" to "20")).vocabPerDay)
        assertEquals(5, Config.from(base + ("VOCAB_PER_DAY" to "2")).vocabPerDay)
        assertEquals(8, Config.from(base + ("VOCAB_PER_DAY" to "8")).vocabPerDay)
        assertEquals(12, Config.from(base + ("VOCAB_REVIEWS_PER_DAY" to "12")).vocabReviewsPerDay)
    }
}
