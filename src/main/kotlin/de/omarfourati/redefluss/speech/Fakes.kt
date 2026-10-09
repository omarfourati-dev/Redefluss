package de.omarfourati.redefluss.speech

import java.io.ByteArrayOutputStream

/** SPEECH=fake: deterministic, free, used for local runs and CI. "TEXT:…" audio bytes are taken as the transcript. */
class FakeTranscriber : Transcriber {
    override suspend fun transcribe(audio: Audio): String {
        val head = String(audio.bytes, 0, minOf(audio.bytes.size, 7), Charsets.UTF_8)
        return when {
            head.startsWith("TEXT:") -> String(audio.bytes, Charsets.UTF_8).removePrefix("TEXT:").trim()
            head == "SILENCE" -> ""
            else -> DEFAULT
        }
    }
    companion object { const val DEFAULT = "Ich habe den ganzen Zeit an meinem Projekt gearbeitet." }
}

class FakeCoach : Coach {
    private val rules = listOf(
        Correction("den ganzen Zeit", "die ganze Zeit", "„Zeit“ ist feminin: die Zeit.", "artikel"),
        Correction("du muss", "du musst", "Bei „du“ endet das Verb auf -st.", "konjugation"),
    )

    override suspend fun respond(input: CoachInput): CoachReply {
        val found = rules.filter { it.wrong in input.utterance }
        val natural = found.fold(input.utterance) { s, c -> s.replace(c.wrong, c.right) }
        val reply = if (input.history.size >= 2) "Erzähl mir mehr darüber – was war dabei am schwierigsten?"
                    else "Interessant! Woran hast du genau gearbeitet?"
        return CoachReply(found, natural, reply)
    }
}

class FakeVoice : Voice {
    override suspend fun speak(text: String): Audio = Audio(silentWav(), "audio/wav")

    /** 0.2 s of 8 kHz mono 16-bit silence. */
    private fun silentWav(): ByteArray {
        val samples = 1600
        val data = samples * 2
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); int(36 + data); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(8000); int(16000); short(2); short(16)
        out.write("data".toByteArray()); int(data); out.write(ByteArray(data))
        return out.toByteArray()
    }
}
