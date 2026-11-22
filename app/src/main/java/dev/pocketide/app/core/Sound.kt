package dev.pocketide.app.core

import java.io.ByteArrayOutputStream

/** Make short sound effects and jingles as WAV files (16-bit, mono, 22050 Hz) without any audio library. */
object Sound {
    const val RATE = 22050

    class Voice(val wave: String, val freq: Double, val freqEnd: Double, val seconds: Double, val volume: Double = 0.6, val attack: Double = 0.005, val decay: Double = 3.0, val vibrato: Double = 0.0)

    val PRESETS: Map<String, List<Voice>> = mapOf(
        "coin" to listOf(Voice("square", 988.0, 988.0, 0.07), Voice("square", 1319.0, 1319.0, 0.25, decay = 6.0)),
        "jump" to listOf(Voice("square", 260.0, 620.0, 0.18, decay = 4.0)),
        "hit" to listOf(Voice("noise", 0.0, 0.0, 0.12, decay = 8.0), Voice("square", 180.0, 70.0, 0.12, decay = 8.0)),
        "explosion" to listOf(Voice("noise", 0.0, 0.0, 0.7, volume = 0.8, decay = 4.0)),
        "powerup" to listOf(Voice("square", 330.0, 330.0, 0.07), Voice("square", 440.0, 440.0, 0.07), Voice("square", 660.0, 660.0, 0.07), Voice("square", 880.0, 880.0, 0.2, decay = 5.0)),
        "click" to listOf(Voice("square", 1200.0, 900.0, 0.03, decay = 10.0)),
        "laser" to listOf(Voice("saw", 1400.0, 200.0, 0.22, decay = 5.0)),
        "blip" to listOf(Voice("sine", 700.0, 700.0, 0.08, decay = 6.0)),
        "hurt" to listOf(Voice("saw", 300.0, 90.0, 0.3, decay = 5.0)),
        "win" to listOf(Voice("square", 523.0, 523.0, 0.12), Voice("square", 659.0, 659.0, 0.12), Voice("square", 784.0, 784.0, 0.12), Voice("square", 1047.0, 1047.0, 0.4, decay = 4.0)))

    fun noteFreq(name: String): Double? {
        val m = Regex("([A-Ga-g])([#b]?)(-?\\d)").matchEntire(name) ?: return null
        val base = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)[m.groupValues[1][0].uppercaseChar()]!!
        val semi = base + (if (m.groupValues[2] == "#") 1 else if (m.groupValues[2] == "b") -1 else 0) + (m.groupValues[3].toInt() + 1) * 12
        return 440.0 * Math.pow(2.0, (semi - 69) / 12.0)
    }

    /** "C4:1 E4:1 G4:2 R:1" (note:beats, R = rest) at [bpm] to voices. */
    fun melody(notes: String, bpm: Int, wave: String): List<Voice> {
        val beat = 60.0 / bpm.coerceIn(30, 300)
        val out = ArrayList<Voice>()
        for (tok in notes.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val name = tok.substringBefore(':'); val beats = tok.substringAfter(':', "1").toDoubleOrNull() ?: throw IllegalArgumentException("bad length in '$tok'")
            val secs = beat * beats
            if (name.equals("R", true)) out.add(Voice("sine", 0.0, 0.0, secs, volume = 0.0))
            else { val f = noteFreq(name) ?: throw IllegalArgumentException("'$name' is not a note (use C4, F#3, Bb5)"); out.add(Voice(wave, f, f, secs, decay = 1.5, vibrato = 0.0)) }
        }
        return out
    }

    fun render(voices: List<Voice>): ByteArray {
        val total = voices.sumOf { it.seconds }
        if (total > 30) throw IllegalArgumentException("sound is longer than 30 seconds")
        val pcm = ShortArray((total * RATE).toInt())
        var pos = 0; var seed = 12345L
        for (v in voices) {
            val n = (v.seconds * RATE).toInt(); var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val f = v.freq + (v.freqEnd - v.freq) * t + (if (v.vibrato > 0) Math.sin(i * 0.002) * v.vibrato else 0.0)
                phase += f / RATE; phase -= Math.floor(phase)
                val raw = when (v.wave) {
                    "square" -> if (phase < 0.5) 1.0 else -1.0
                    "saw" -> 2 * phase - 1
                    "triangle" -> 4 * Math.abs(phase - 0.5) - 1
                    "noise" -> { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; (seed % 20001) / 10000.0 - 1.0 }
                    else -> Math.sin(2 * Math.PI * phase)
                }
                val secs = i.toDouble() / RATE
                val env = (if (secs < v.attack) secs / v.attack else 1.0) * Math.exp(-v.decay * t) * (if (i > n - 100) (n - i) / 100.0 else 1.0)
                if (pos + i < pcm.size) pcm[pos + i] = (raw * env * v.volume * 32000).toInt().coerceIn(-32768, 32767).toShort()
            }
            pos += n
        }
        return wav(pcm)
    }

    fun wav(pcm: ShortArray): ByteArray {
        val o = ByteArrayOutputStream(); val dataLen = pcm.size * 2
        fun i32(v: Int) { o.write(v); o.write(v shr 8); o.write(v shr 16); o.write(v shr 24) }
        fun i16(v: Int) { o.write(v); o.write(v shr 8) }
        o.write("RIFF".toByteArray()); i32(36 + dataLen); o.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(dataLen); for (s in pcm) i16(s.toInt())
        return o.toByteArray()
    }
}
