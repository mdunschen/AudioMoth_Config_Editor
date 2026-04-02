package com.audiomoth.configeditor

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AcousticConfigBuilder {

    fun buildPacket(config: AudioMothConfig): ByteArray {
        val buffer = ByteBuffer.allocate(250).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Preamble (8 bytes of 0xAA)
        repeat(8) { buffer.put(0xAA.toByte()) }

        // 2. Start byte
        buffer.put(0x01.toByte())

        // 3. Data payload
        val dataStart = buffer.position()
        
        // Current time (4 bytes) - Using UTC seconds since epoch
        val currentTime = (System.currentTimeMillis() / 1000).toInt()
        buffer.putInt(currentTime)

        // Gain (1 byte)
        buffer.put(config.gain.toByte())

        // Sample rate index (1 byte)
        val sampleRateIndex = when (config.sampleRate) {
            8000 -> 0
            16000 -> 1
            32000 -> 2
            48000 -> 3
            96000 -> 4
            192000 -> 5
            250000 -> 6
            384000 -> 7
            else -> 3 // Default to 48kHz
        }
        buffer.put(sampleRateIndex.toByte())

        // Record and Sleep durations (2 bytes each)
        buffer.putShort(config.recordDuration.toShort())
        buffer.putShort(config.sleepDuration.toShort())

        // Settings bitmask (1 byte)
        var settings = 0
        if (config.ledEnabled) settings = settings or (1 shl 0)
        if (config.lowVoltageCutoffEnabled) settings = settings or (1 shl 1)
        if (config.minimumBatteryVoltage > 0) settings = settings or (1 shl 2)
        if (config.dutyEnabled) settings = settings or (1 shl 6)
        if (config.passFiltersEnabled) settings = settings or (1 shl 7)
        buffer.put(settings.toByte())

        // Active periods bitmap (180 bytes = 1440 bits)
        val bitmap = ByteArray(180)
        config.timePeriods.forEach { period ->
            for (m in period.startMins until period.endMins) {
                val clampedM = m.coerceIn(0, 1439)
                val byteIdx = clampedM / 8
                val bitIdx = clampedM % 8
                bitmap[byteIdx] = (bitmap[byteIdx].toInt() or (1 shl bitIdx)).toByte()
            }
        }
        buffer.put(bitmap)

        val dataEnd = buffer.position()
        val dataSize = dataEnd - dataStart

        // 4. CRC16-CCITT
        val crc = calculateCRC16(buffer.array(), dataStart, dataSize)
        buffer.putShort(crc.toShort())

        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }

    private fun calculateCRC16(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        val polynomial = 0x1021
        for (i in 0 until length) {
            val b = bytes[offset + i].toInt()
            for (j in 0 until 8) {
                val bit = (b shr (7 - j) and 1) == 1
                val c15 = (crc shr 15 and 1) == 1
                crc = crc shl 1
                if (c15 xor bit) crc = crc xor polynomial
            }
        }
        return crc and 0xFFFF
    }
}
