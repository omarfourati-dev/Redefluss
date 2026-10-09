package de.omarfourati.redefluss.config

class ConfigException(message: String) : RuntimeException(message)

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
) {
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
            )
        }
    }
}
