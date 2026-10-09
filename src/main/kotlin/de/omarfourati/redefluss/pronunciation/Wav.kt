package de.omarfourati.redefluss.pronunciation

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Int, val dataOffset: Int = 0) {
    val seconds: Double get() = dataBytes / (sampleRate * channels * bitsPerSample / 8.0)

    companion object {
        /** Reads the RIFF/WAVE header (PCM, format 1) by walking the chunks; null for anything else. Judging the values is up to the caller. */
        fun parse(bytes: ByteArray): WavInfo? {
            if (bytes.size < 12) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(at: Int) = String(bytes, at, 4, Charsets.ISO_8859_1)
            if (tag(0) != "RIFF" || tag(8) != "WAVE") return null
            var pos = 12
            var rate = 0; var channels = 0; var bits = 0; var haveFmt = false
            while (pos + 8 <= bytes.size) {
                val id = tag(pos)
                val size = b.getInt(pos + 4).toLong() and 0xFFFFFFFFL
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        if (size < 16 || body + 16 > bytes.size) return null
                        if (b.getShort(body).toInt() != 1) return null
                        channels = b.getShort(body + 2).toInt() and 0xFFFF
                        rate = b.getInt(body + 4)
                        bits = b.getShort(body + 14).toInt() and 0xFFFF
                        haveFmt = true
                    }
                    "data" -> {
                        if (!haveFmt || rate <= 0 || channels <= 0 || bits <= 0) return null
                        // Streamed recordings may declare 0 or 0xFFFFFFFF: never trust more than what is actually there.
                        return WavInfo(rate, channels, bits, minOf(size, (bytes.size - body).toLong()).toInt(), body)
                    }
                }
                val next = body + size + (size and 1L)
                if (next > Int.MAX_VALUE) return null
                pos = next.toInt()
            }
            return null
        }
    }
}
