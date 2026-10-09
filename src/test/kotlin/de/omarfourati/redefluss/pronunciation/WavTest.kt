package de.omarfourati.redefluss.pronunciation

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

fun wavBytes(samples: Int, rate: Int = 16000, channels: Int = 1, bits: Int = 16, format: Int = 1, extraChunk: Boolean = false): ByteArray {
    val data = ByteArray(samples * channels * bits / 8)
    fun chunk(id: String, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + body.size + body.size % 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put(id.toByteArray()); b.putInt(body.size); b.put(body)
        return b.array()
    }
    val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(format.toShort()); putShort(channels.toShort()); putInt(rate)
        putInt(rate * channels * bits / 8); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
    }.array()
    val body = ByteArrayOutputStream()
    body.write("WAVE".toByteArray()); body.write(chunk("fmt ", fmt))
    if (extraChunk) body.write(chunk("LIST", "INFOabc".toByteArray())) // odd size -> padding byte
    body.write(chunk("data", data))
    val out = ByteBuffer.allocate(8 + body.size()).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray()); out.putInt(body.size()); out.put(body.toByteArray())
    return out.array()
}

class WavTest {
    @Test fun parsesMono16k() {
        val w = WavInfo.parse(wavBytes(16000 * 2))!!
        assertEquals(16000, w.sampleRate); assertEquals(1, w.channels); assertEquals(16, w.bitsPerSample)
        assertEquals(2.0, w.seconds, 1e-9)
    }

    @Test fun skipsExtraChunkBeforeData() {
        val w = WavInfo.parse(wavBytes(8000, extraChunk = true))!!
        assertEquals(0.5, w.seconds, 1e-9)
    }

    @Test fun parsesOtherFormatsWithoutJudging() {
        val w = WavInfo.parse(wavBytes(44100, rate = 44100, channels = 2))!!
        assertEquals(44100, w.sampleRate); assertEquals(2, w.channels); assertEquals(1.0, w.seconds, 1e-9)
    }

    @Test fun rejectsNonWav() {
        assertNull(WavInfo.parse("hello world, this is not a wav file at all".toByteArray()))
        assertNull(WavInfo.parse(ByteArray(0)))
    }

    @Test fun rejectsFloatFormat() = assertNull(WavInfo.parse(wavBytes(100, format = 3, bits = 32)))

    @Test fun rejectsTruncatedHeader() {
        val full = wavBytes(100)
        assertNull(WavInfo.parse(full.copyOf(30)))
        assertNull(WavInfo.parse(full.copyOf(40))) // fmt present, no data chunk header
    }
}
