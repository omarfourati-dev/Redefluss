package de.omarfourati.redefluss.config

class ConfigException(message: String) : RuntimeException(message)

/** Daily limits of one account; the owner gets the classic values, invited guests smaller ones. */
data class Limits(val turnsPerDay: Int, val liveMinutesPerDay: Int, val pronunciationsPerDay: Int, val vocabReviewsPerDay: Int)

data class Config(
    val port: Int,
    val databaseUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val ownerEmail: String?,
    val ownerPassword: String?,
    val ownerResetPassword: Boolean,
    val speech: String,
    val openAiKey: String?,
    val aiModel: String,
    val transcribeModel: String,
    val ttsModel: String,
    val ttsVoice: String,
    val turnsPerDay: Int,
    val vocabPerDay: Int,
    val vocabReviewsPerDay: Int,
    val pronunciation: String,
    val azureKey: String?,
    val azureRegion: String,
    val azureSecondsPerMonth: Int,
    val pronunciationsPerDay: Int,
    val liveMinutesPerDay: Int,
    val realtimeModel: String,
    val realtimeVoice: String,
    val guestTurnsPerDay: Int = 100,
    val guestLiveMinutesPerDay: Int = 15,
    val guestPronunciationsPerDay: Int = 30,
    val guestVocabReviewsPerDay: Int = 30,
    /** Caps over all accounts together, so a few guests cannot multiply the costs. */
    val totalTurnsPerDay: Int = 500,
    val totalLiveMinutesPerDay: Int = 60,
) {
    val ownerLimits: Limits get() = Limits(turnsPerDay, liveMinutesPerDay, pronunciationsPerDay, vocabReviewsPerDay)
    val guestLimits: Limits get() = Limits(guestTurnsPerDay, guestLiveMinutesPerDay, guestPronunciationsPerDay, guestVocabReviewsPerDay)

    /** The generated data-class text would print every secret; mask them. */
    override fun toString(): String = "Config(port=$port, databaseUrl=$databaseUrl, dbUser=$dbUser, dbPassword=***, jwtSecret=***, " +
        "ownerEmail=$ownerEmail, ownerPassword=${ownerPassword?.let { "***" }}, speech=$speech, openAiKey=${openAiKey?.let { "***" }}, " +
        "pronunciation=$pronunciation, azureKey=${azureKey?.let { "***" }}, azureRegion=$azureRegion)"

    companion object {
        fun from(env: Map<String, String>): Config {
            fun opt(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun req(name: String) = opt(name) ?: throw ConfigException("$name is missing")
            fun int(name: String, default: Int) =
                opt(name)?.let { it.toIntOrNull() ?: throw ConfigException("$name must be a number") } ?: default

            val speech = opt("SPEECH") ?: "openai"
            if (speech !in setOf("openai", "fake")) throw ConfigException("SPEECH must be openai or fake")
            val key = opt("OPENAI_API_KEY")?.takeIf { it != "not-configured" }
            if (speech == "openai" && key == null) throw ConfigException("OPENAI_API_KEY is required for SPEECH=openai")
            val pronunciation = opt("PRONUNCIATION") ?: if (speech == "fake") "fake" else "azure"
            if (pronunciation !in setOf("azure", "fake")) throw ConfigException("PRONUNCIATION must be azure or fake")
            val jwt = req("JWT_SECRET")
            if (jwt.length < 32) throw ConfigException("JWT_SECRET must have at least 32 characters")

            return Config(
                port = int("PORT", 8080),
                databaseUrl = req("DATABASE_URL"),
                dbUser = req("DB_USER"),
                dbPassword = req("DB_PASSWORD"),
                jwtSecret = jwt,
                ownerEmail = opt("OWNER_EMAIL")?.lowercase(),
                ownerPassword = opt("OWNER_PASSWORD"),
                ownerResetPassword = opt("OWNER_RESET_PASSWORD") == "true",
                speech = speech,
                openAiKey = key,
                aiModel = opt("AI_MODEL") ?: "gpt-4o-mini",
                transcribeModel = opt("TRANSCRIBE_MODEL") ?: "gpt-4o-transcribe",
                ttsModel = opt("TTS_MODEL") ?: "gpt-4o-mini-tts",
                ttsVoice = opt("TTS_VOICE") ?: "coral",
                turnsPerDay = int("TURNS_PER_DAY", 300),
                vocabPerDay = int("VOCAB_PER_DAY", 7).coerceIn(5, 10),
                vocabReviewsPerDay = int("VOCAB_REVIEWS_PER_DAY", 60),
                pronunciation = pronunciation,
                azureKey = opt("AZURE_SPEECH_KEY"),
                azureRegion = opt("AZURE_SPEECH_REGION") ?: "germanywestcentral",
                azureSecondsPerMonth = int("AZURE_SECONDS_PER_MONTH", 16200).coerceAtLeast(0),
                pronunciationsPerDay = int("PRONUNCIATIONS_PER_DAY", 100).coerceAtLeast(0),
                liveMinutesPerDay = int("LIVE_MINUTES_PER_DAY", 30).coerceIn(0, 120),
                realtimeModel = opt("REALTIME_MODEL") ?: "gpt-realtime",
                realtimeVoice = opt("REALTIME_VOICE") ?: "marin",
                guestTurnsPerDay = int("GUEST_TURNS_PER_DAY", 100).coerceAtLeast(0),
                guestLiveMinutesPerDay = int("GUEST_LIVE_MINUTES_PER_DAY", 15).coerceIn(0, 120),
                guestPronunciationsPerDay = int("GUEST_PRONUNCIATIONS_PER_DAY", 30).coerceAtLeast(0),
                guestVocabReviewsPerDay = int("GUEST_VOCAB_REVIEWS_PER_DAY", 30).coerceAtLeast(0),
                totalTurnsPerDay = int("TOTAL_TURNS_PER_DAY", 500).coerceAtLeast(0),
                totalLiveMinutesPerDay = int("TOTAL_LIVE_MINUTES_PER_DAY", 60).coerceIn(0, 480),
            )
        }
    }
}
