package de.omarfourati.redefluss.speech

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class FakesTest {
    @Test fun transcriber() = runBlocking {
        val t = FakeTranscriber()
        assertEquals("Hallo Welt", t.transcribe(Audio("TEXT:Hallo Welt".toByteArray(), "audio/webm")))
        assertEquals("", t.transcribe(Audio("SILENCE".toByteArray(), "audio/webm")))
        assertEquals(FakeTranscriber.DEFAULT, t.transcribe(Audio(ByteArray(5000) { 7 }, "audio/webm")))
    }

    @Test fun coachCorrectsTheKnownPattern() = runBlocking {
        val r = FakeCoach().respond(CoachInput("Ich habe den ganzen Zeit gearbeitet.", "Arbeit", emptyList(), emptyList()))
        assertEquals("die ganze Zeit", r.corrections.single().right)
        assertEquals("Ich habe die ganze Zeit gearbeitet.", r.natural)
        assertTrue(r.reply.endsWith("?"))
        assertTrue(FakeCoach().respond(CoachInput("Alles gut.", "", emptyList(), emptyList())).corrections.isEmpty())
    }

    @Test fun voiceIsAValidWav() = runBlocking {
        val a = FakeVoice().speak("Hallo")
        assertEquals("audio/wav", a.contentType)
        assertEquals("RIFF", String(a.bytes, 0, 4))
        assertEquals("WAVE", String(a.bytes, 8, 4))
    }
}
