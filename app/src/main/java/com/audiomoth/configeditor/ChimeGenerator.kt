package com.audiomoth.configeditor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

object ChimeGenerator {

    private const val SAMPLE_RATE = 44100
    private const val BIT_DURATION_MS = 10L
    private const val FREQ_0 = 17000.0
    private const val FREQ_1 = 18000.0

    fun play(packet: ByteArray) {
        val samplesPerBit = (SAMPLE_RATE * BIT_DURATION_MS / 1000).toInt()
        val totalBits = packet.size * 8
        val totalSamples = totalBits * samplesPerBit
        val buffer = ShortArray(totalSamples)

        var currentPhase = 0.0
        var sampleIdx = 0

        for (byte in packet) {
            for (bitIdx in 0 until 8) {
                // AudioMoth FSK transmits LSB first
                val bit = (byte.toInt() shr bitIdx) and 1
                val freq = if (bit == 1) FREQ_1 else FREQ_0
                
                val phaseIncrement = 2.0 * PI * freq / SAMPLE_RATE

                repeat(samplesPerBit) {
                    buffer[sampleIdx++] = (sin(currentPhase) * Short.MAX_VALUE).toInt().toShort()
                    currentPhase += phaseIncrement
                }
            }
        }

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(buffer, 0, buffer.size)
        audioTrack.play()
        
        // AudioTrack in static mode plays in background. 
        // We might want to release it after playback finishes in a real app.
    }
}
