package com.audiomoth.configeditor

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AcousticConfigBuilder {

    /**
     * Builds the acoustic packet sent via chime.
     * Includes preamble, start byte, payload, and CRC.
     */
    fun buildPacket(config: AudioMothConfig): ByteArray {
        val payload = buildPayload(config)
        // 8 bytes preamble + 1 byte start + payload + 2 bytes CRC
        val buffer = ByteBuffer.allocate(8 + 1 + payload.size + 2).order(ByteOrder.LITTLE_ENDIAN)

        repeat(8) { buffer.put(0xAA.toByte()) }
        buffer.put(0x01.toByte())
        buffer.put(payload)
        
        val crc = calculateCRC16(payload)
        buffer.putShort(crc.toShort())

        return buffer.array()
    }

    /**
     * Builds the 512-byte binary configuration file (CONFIG.TXT) expected on the SD card.
     */
    fun buildBinaryConfig(config: AudioMothConfig): ByteArray {
        val payload = buildPayload(config)
        val buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(payload)
        
        val crc = calculateCRC16(payload)
        buffer.putShort(crc.toShort())
        
        // Remainder is zero-filled by allocate()
        return buffer.array()
    }

    /**
     * Parses a binary configuration file back into an AudioMothConfig object.
     */
    fun fromBinaryConfig(data: ByteArray): AudioMothConfig? {
        if (data.size < 207) return null
        
        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Skip current time (4 bytes)
            buffer.position(4)

            val gain = buffer.get().toInt() and 0xFF
            val sampleRateIndex = buffer.get().toInt() and 0xFF
            val sampleRate = when (sampleRateIndex) {
                0 -> 8000
                1 -> 16000
                2 -> 32000
                3 -> 48000
                4 -> 96000
                5 -> 192000
                6 -> 250000
                7 -> 384000
                else -> 48000
            }

            val recordDuration = buffer.getShort().toInt() and 0xFFFF
            val sleepDuration = buffer.getShort().toInt() and 0xFFFF

            val settings = buffer.get().toInt() and 0xFF
            val ledEnabled = (settings and (1 shl 0)) != 0
            val lowVoltageCutoffEnabled = (settings and (1 shl 1)) != 0
            val minimumBatteryVoltage = if ((settings and (1 shl 2)) != 0) 33 else 0
            val localTime = (settings and (1 shl 3)) != 0
            val energySaverModeEnabled = (settings and (1 shl 4)) != 0
            val dailyFolders = (settings and (1 shl 5)) != 0
            val dutyEnabled = (settings and (1 shl 6)) != 0
            val passFiltersEnabled = (settings and (1 shl 7)) != 0

            buffer.get() // Padding (Offset 11)

            val lowerFilter = buffer.getShort().toInt() and 0xFFFF
            val higherFilter = buffer.getShort().toInt() and 0xFFFF

            val ampThresholdRaw = buffer.getShort().toInt() and 0xFFFF
            val amplitudeThresholdingEnabled = ampThresholdRaw != 0
            val amplitudeThreshold = ampThresholdRaw / 32768.0

            val minimumAmplitudeThresholdDuration = buffer.get().toInt() and 0xFF
            
            val windowIndex = buffer.get().toInt() and 0xFF
            val frequencyTriggerEnabled = windowIndex != 0xFF
            val frequencyTriggerWindowLength = when (windowIndex) {
                0 -> 16
                1 -> 32
                2 -> 64
                3 -> 128
                4 -> 256
                5 -> 512
                6 -> 1024
                else -> 64
            }

            val frequencyTriggerCentreFrequency = buffer.getShort().toInt() and 0xFFFF
            val minimumFrequencyTriggerDuration = buffer.get().toInt() and 0xFF
            
            buffer.get() // Padding (Offset 23)

            val freqThresholdRaw = buffer.getShort().toInt() and 0xFFFF
            val frequencyTriggerThreshold = freqThresholdRaw / 32768.0

            val extendedSettings = buffer.get().toInt() and 0xFF
            val lowGainRangeEnabled = (extendedSettings and (1 shl 0)) != 0
            val disable48DCFilter = (extendedSettings and (1 shl 1)) != 0
            val timeSettingFromGPSEnabled = (extendedSettings and (1 shl 2)) != 0
            val magneticSwitchEnabled = (extendedSettings and (1 shl 3)) != 0

            val bitmap = ByteArray(180)
            buffer.get(bitmap)

            val timePeriods = mutableListOf<TimePeriod>()
            var currentStart: Int? = null
            for (m in 0 until 1440) {
                val byteIdx = m / 8
                val bitIdx = m % 8
                val isActive = (bitmap[byteIdx].toInt() and (1 shl bitIdx)) != 0
                if (isActive && currentStart == null) {
                    currentStart = m
                } else if (!isActive && currentStart != null) {
                    timePeriods.add(TimePeriod(currentStart, m))
                    currentStart = null
                }
            }
            if (currentStart != null) {
                timePeriods.add(TimePeriod(currentStart, 1440))
            }

            return AudioMothConfig(
                timePeriods = timePeriods,
                ledEnabled = ledEnabled,
                lowVoltageCutoffEnabled = lowVoltageCutoffEnabled,
                minimumBatteryVoltage = minimumBatteryVoltage,
                sampleRate = sampleRate,
                gain = gain,
                recordDuration = recordDuration,
                sleepDuration = sleepDuration,
                localTime = localTime,
                dutyEnabled = dutyEnabled,
                passFiltersEnabled = passFiltersEnabled,
                lowerFilter = lowerFilter,
                higherFilter = higherFilter,
                amplitudeThresholdingEnabled = amplitudeThresholdingEnabled,
                amplitudeThreshold = amplitudeThreshold,
                minimumAmplitudeThresholdDuration = minimumAmplitudeThresholdDuration,
                frequencyTriggerEnabled = frequencyTriggerEnabled,
                frequencyTriggerWindowLength = frequencyTriggerWindowLength,
                frequencyTriggerCentreFrequency = frequencyTriggerCentreFrequency,
                minimumFrequencyTriggerDuration = minimumFrequencyTriggerDuration,
                frequencyTriggerThreshold = frequencyTriggerThreshold,
                dailyFolders = dailyFolders,
                energySaverModeEnabled = energySaverModeEnabled,
                disable48DCFilter = disable48DCFilter,
                lowGainRangeEnabled = lowGainRangeEnabled,
                timeSettingFromGPSEnabled = timeSettingFromGPSEnabled,
                magneticSwitchEnabled = magneticSwitchEnabled
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Standard CCITT-FALSE CRC16 (Polynomial 0x1021, Init 0xFFFF)
     * Matches implementation used by AudioMoth firmware and configuration apps.
     */
    private fun calculateCRC16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF shl 8)
            for (i in 0 until 8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1 xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    private fun buildPayload(config: AudioMothConfig): ByteArray {
        val buffer = ByteBuffer.allocate(207).order(ByteOrder.LITTLE_ENDIAN)
        
        // Offset 0: Current time (4 bytes)
        val currentTime = (System.currentTimeMillis() / 1000).toInt()
        buffer.putInt(currentTime)

        // Offset 4: Gain (1 byte)
        buffer.put(config.gain.toByte())

        // Offset 5: Sample rate index (1 byte)
        val sampleRateIndex = when (config.sampleRate) {
            8000 -> 0
            16000 -> 1
            32000 -> 2
            48000 -> 3
            96000 -> 4
            192000 -> 5
            250000 -> 6
            384000 -> 7
            else -> 3
        }
        buffer.put(sampleRateIndex.toByte())

        // Offset 6: Record duration (2 bytes)
        buffer.putShort(config.recordDuration.toShort())
        
        // Offset 8: Sleep duration (2 bytes)
        buffer.putShort(config.sleepDuration.toShort())

        // Offset 10: Settings bitmask (1 byte)
        var settings = 0
        if (config.ledEnabled) settings = settings or (1 shl 0)
        if (config.lowVoltageCutoffEnabled) settings = settings or (1 shl 1)
        if (config.minimumBatteryVoltage > 0) settings = settings or (1 shl 2)
        if (config.localTime) settings = settings or (1 shl 3)
        if (config.energySaverModeEnabled) settings = settings or (1 shl 4)
        if (config.dailyFolders) settings = settings or (1 shl 5)
        if (config.dutyEnabled) settings = settings or (1 shl 6)
        if (config.passFiltersEnabled) settings = settings or (1 shl 7)
        buffer.put(settings.toByte())

        // Offset 11: Padding
        buffer.put(0.toByte())

        // Offset 12: Lower filter (2 bytes)
        buffer.putShort(config.lowerFilter.toShort())
        
        // Offset 14: Higher filter (2 bytes)
        buffer.putShort(config.higherFilter.toShort())

        // Offset 16: Amplitude threshold (2 bytes)
        val ampThreshold = if (config.amplitudeThresholdingEnabled) {
            (config.amplitudeThreshold * 32768.0).toInt().coerceIn(0, 32767)
        } else 0
        buffer.putShort(ampThreshold.toShort())

        // Offset 18: Amplitude duration (1 byte)
        buffer.put(config.minimumAmplitudeThresholdDuration.toByte())

        // Offset 19: Window length index (1 byte)
        val windowIndex = when (config.frequencyTriggerWindowLength) {
            16 -> 0
            32 -> 1
            64 -> 2
            128 -> 3
            256 -> 4
            512 -> 5
            1024 -> 6
            else -> 2
        }
        buffer.put(if (config.frequencyTriggerEnabled) windowIndex.toByte() else 0xFF.toByte())

        // Offset 20: Centre frequency (2 bytes)
        buffer.putShort(if (config.frequencyTriggerEnabled) config.frequencyTriggerCentreFrequency.toShort() else 0.toShort())

        // Offset 22: Freq trigger duration (1 byte)
        buffer.put(config.minimumFrequencyTriggerDuration.toByte())

        // Offset 23: Padding
        buffer.put(0.toByte())

        // Offset 24: Freq trigger threshold (2 bytes)
        val freqThreshold = if (config.frequencyTriggerEnabled) {
            (config.frequencyTriggerThreshold * 32768.0).toInt().coerceIn(0, 32767)
        } else 0
        buffer.putShort(freqThreshold.toShort())

        // Offset 26: Extended settings (1 byte)
        var extendedSettings = 0
        if (config.lowGainRangeEnabled) extendedSettings = extendedSettings or (1 shl 0)
        if (config.disable48DCFilter) extendedSettings = extendedSettings or (1 shl 1)
        if (config.timeSettingFromGPSEnabled) extendedSettings = extendedSettings or (1 shl 2)
        if (config.magneticSwitchEnabled) extendedSettings = extendedSettings or (1 shl 3)
        buffer.put(extendedSettings.toByte())

        // Offset 27: Active periods bitmap (180 bytes)
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

        return buffer.array()
    }
}
