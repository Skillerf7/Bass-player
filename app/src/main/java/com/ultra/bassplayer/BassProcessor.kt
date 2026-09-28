package com.ultra.bassplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.*

class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun setCoef(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        b0 = nb0 / a0
        b1 = nb1 / a0
        b2 = nb2 / a0
        a1 = na1 / a0
        a2 = na2 / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

class BassProcessor : BaseAudioProcessor() {
    private val centers = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    private val bands = DoubleArray(10)
    private val bandOn = BooleanArray(10)

    @Volatile private var dirty = true
    @Volatile private var preDb = -6.0
    @Volatile private var bassDb = 5.0
    @Volatile private var bassHz = 100.0
    @Volatile private var bassQ = 1.0
    @Volatile private var trebleDb = 3.0
    @Volatile private var trebleHz = 8000.0
    @Volatile private var trebleQ = 0.8
    @Volatile private var hpHz = 35.0
    @Volatile private var limiter = true

    private var sampleRate = 44100
    private var channels = 2
    private var enc = C.ENCODING_PCM_16BIT
    private var bytesPer = 2
    private var pre = 1.0
    private var hpOn = false
    private var bassOn = false
    private var trebleOn = false
    private var low = Array(2) { Biquad() }
    private var high = Array(2) { Biquad() }
    private var hp = Array(2) { Biquad() }
    private var eqf = Array(2) { Array(10) { Biquad() } }

    fun update(
        pre: Double, bass: Double, bassHz: Double, bassQ: Double,
        treble: Double, trebleHz: Double, trebleQ: Double,
        hpHz: Double, limiter: Boolean, b: DoubleArray
    ) {
        this.preDb = pre
        this.bassDb = bass
        this.bassHz = bassHz
        this.bassQ = bassQ
        this.trebleDb = treble
        this.trebleHz = trebleHz
        this.trebleQ = trebleQ
        this.hpHz = hpHz
        this.limiter = limiter
        for (i in 0 until 10) bands[i] = b[i]
        dirty = true
    }

    private fun newFilters() {
        low = Array(channels) { Biquad() }
        high = Array(channels) { Biquad() }
        hp = Array(channels) { Biquad() }
        eqf = Array(channels) { Array(10) { Biquad() } }
        dirty = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        enc = inputAudioFormat.encoding
        bytesPer = when (enc) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        newFilters()
        return inputAudioFormat
    }

    override fun onFlush() {
        newFilters()
    }

    private fun shelf(f: Biquad, hz: Double, db: Double, q: Double, fs: Double, isLow: Boolean) {
        val a = 10.0.pow(db / 40.0)
        val w = 2.0 * PI * hz / fs
        val cw = cos(w)
        val al = sin(w) / (2.0 * q)
        val t = 2.0 * sqrt(a) * al
        if (isLow) {
            f.setCoef(
                a * ((a + 1) - (a - 1) * cw + t),
                2 * a * ((a - 1) - (a + 1) * cw),
                a * ((a + 1) - (a - 1) * cw - t),
                (a + 1) + (a - 1) * cw + t,
                -2 * ((a - 1) + (a + 1) * cw),
                (a + 1) + (a - 1) * cw - t
            )
        } else {
            f.setCoef(
                a * ((a + 1) + (a - 1) * cw + t),
                -2 * a * ((a - 1) + (a + 1) * cw),
                a * ((a + 1) + (a - 1) * cw - t),
                (a + 1) - (a - 1) * cw + t,
                2 * ((a - 1) - (a + 1) * cw),
                (a + 1) - (a - 1) * cw - t
            )
        }
    }

    private fun peak(f: Biquad, hz: Double, db: Double, fs: Double) {
        val a = 10.0.pow(db / 40.0)
        val w = 2.0 * PI * hz / fs
        val cw = cos(w)
        val al = sin(w) / (2.0 * 1.41)
        f.setCoef(1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a)
    }

    private fun highPass(f: Biquad, hz: Double, fs: Double) {
        val w = 2.0 * PI * hz / fs
        val cw = cos(w)
        val al = sin(w) / (2.0 * 0.7071)
        f.setCoef((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al)
    }

    private fun refresh() {
        dirty = false
        val fs = sampleRate.toDouble()
        val nyq = fs * 0.45
        pre = 10.0.pow(preDb / 20.0)
        hpOn = hpHz >= 10.0
        bassOn = abs(bassDb) > 0.05
        trebleOn = abs(trebleDb) > 0.05
        for (k in 0 until 10) bandOn[k] = abs(bands[k]) > 0.05 && centers[k] <= nyq
        for (c in 0 until channels) {
            if (bassOn) shelf(low[c], bassHz.coerceIn(20.0, nyq), bassDb, bassQ, fs, true)
            if (trebleOn) shelf(high[c], trebleHz.coerceIn(20.0, nyq), trebleDb, trebleQ, fs, false)
            if (hpOn) highPass(hp[c], hpHz.coerceAtMost(nyq), fs)
            for (k in 0 until 10) if (bandOn[k]) peak(eqf[c][k], centers[k], bands[k], fs)
        }
    }

    private fun soft(x: Double): Double {
        val t = 0.8
        val a = abs(x)
        if (a <= t) return x
        val y = t + (1 - t) * tanh((a - t) / (1 - t))
        return if (x < 0) -y else y
    }

    private fun readSample(b: ByteBuffer): Double = when (enc) {
        C.ENCODING_PCM_FLOAT -> b.float.toDouble()
        C.ENCODING_PCM_32BIT -> b.int / 2147483648.0
        C.ENCODING_PCM_24BIT -> {
            val lo = b.get().toInt() and 0xFF
            val mid = b.get().toInt() and 0xFF
            val hi = b.get().toInt()
            (lo or (mid shl 8) or (hi shl 16)) / 8388608.0
        }
        else -> b.short / 32768.0
    }

    private fun writeSample(b: ByteBuffer, x: Double) {
        val s = x.coerceIn(-1.0, 1.0)
        when (enc) {
            C.ENCODING_PCM_FLOAT -> b.putFloat(s.toFloat())
            C.ENCODING_PCM_32BIT -> b.putInt((s * 2147483647.0).toInt())
            C.ENCODING_PCM_24BIT -> {
                val v = (s * 8388607.0).toInt()
                b.put(v.toByte())
                b.put((v shr 8).toByte())
                b.put((v shr 16).toByte())
            }
            else -> b.putShort((s * 32767.0).roundToInt().toShort())
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = bytesPer * channels
        val frames = inputBuffer.remaining() / frameBytes
        val total = frames * frameBytes
        if (total == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        if (dirty) refresh()
        val out = replaceOutputBuffer(total)
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                var x = readSample(inputBuffer) * pre
                if (hpOn) x = hp[c].process(x)
                if (bassOn) x = low[c].process(x)
                if (trebleOn) x = high[c].process(x)
                for (k in 0 until 10) if (bandOn[k]) x = eqf[c][k].process(x)
                if (limiter) x = soft(x)
                writeSample(out, x)
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
