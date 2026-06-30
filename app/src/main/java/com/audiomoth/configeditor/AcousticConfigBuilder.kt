package com.audiomoth.configeditor

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AcousticConfigBuilder {

    private const val USB_APP_PACKET_SIZE = 62

    private data class SampleRateConfig(
        val trueSampleRate: Int,
        val clockDivider: Int,
        val acquisitionCycles: Int,
        val oversampleRate: Int,
        val sampleRate: Int,
        val sampleRateDivider: Int
    )

    private val sampleRateConfigs = listOf(
        SampleRateConfig(8000, 4, 16, 1, 384000, 48),
        SampleRateConfig(16000, 4, 16, 1, 384000, 24),
        SampleRateConfig(32000, 4, 16, 1, 384000, 12),
        SampleRateConfig(48000, 4, 16, 1, 384000, 8),
        SampleRateConfig(96000, 4, 16, 1, 384000, 4),
        SampleRateConfig(192000, 4, 16, 1, 384000, 2),
        SampleRateConfig(250000, 4, 16, 1, 250000, 1),
        SampleRateConfig(384000, 4, 16, 1, 384000, 1)
    )

    fun fromUsbAppPacket(rawPacket: ByteArray): AudioMothConfig? {
        if (rawPacket.isEmpty()) return null

        return try {
            val packet = if (rawPacket.size >= USB_APP_PACKET_SIZE) {
                rawPacket.copyOfRange(0, USB_APP_PACKET_SIZE)
            } else {
                rawPacket.copyOf(USB_APP_PACKET_SIZE)
            }

            val gain = packet[4].toInt() and 0xFF
            val sampleRateRaw = readLeInt(packet, 8)
            val sampleRateDivider = (packet[12].toInt() and 0xFF).coerceAtLeast(1)
            val sampleRate = sampleRateRaw / sampleRateDivider

            val validSampleRates = sampleRateConfigs.map { it.trueSampleRate }.toSet()
            if (gain !in 0..4) return null
            if (sampleRate !in validSampleRates) return null

            val sleepDuration = readLeU16(packet, 13)
            val recordDuration = readLeU16(packet, 15)
            if (sleepDuration !in 0..43200 || recordDuration !in 0..43200) return null
            val ledEnabled = packet[17].toInt() == 1

            val activePeriods = (packet[18].toInt() and 0xFF).coerceIn(0, 4)
            val timePeriods = mutableListOf<TimePeriod>()
            repeat(activePeriods) { i ->
                val start = readLeU16(packet, 19 + i * 4).coerceIn(0, 1440)
                val end = readLeU16(packet, 21 + i * 4).coerceIn(0, 1440)
                if (start != end) timePeriods.add(TimePeriod(start, end))
            }

            val packedByte2 = packet[41].toInt() and 0xFF
            val packedByte3 = packet[43].toInt() and 0xFF
            val packedByte0 = packet[61].toInt() and 0xFF

            val dutyEnabled = (packedByte3 and 0x01) == 0
            val batteryCheckEnabled = (packedByte2 and 0x01) == 0

            val lowerRaw = readLeU16(packet, 52)
            val higherRaw = readLeU16(packet, 54)
            val frequencyTriggerEnabled = ((packedByte0 shr 5) and 0x01) == 1

            val (filterType, passFiltersEnabled, lowerFilter, higherFilter) = if (!frequencyTriggerEnabled) {
                when {
                    lowerRaw == 0 && higherRaw == 0 -> Quad("none", false, 0, 0)
                    lowerRaw == 0xFFFF -> Quad("low-pass", true, 0, higherRaw * 100)
                    higherRaw == 0xFFFF -> Quad("high-pass", true, lowerRaw * 100, 0)
                    else -> Quad("band-pass", true, lowerRaw * 100, higherRaw * 100)
                }
            } else {
                Quad("none", false, 0, 0)
            }

            val thresholdUnion = readLeU16(packet, 56)
            val packedByte4 = packet[58].toInt() and 0xFF
            val minimumTriggerDuration = (packedByte4 shr 2) and 0b11_1111

            val frequencyTriggerCentreFrequency = if (frequencyTriggerEnabled) thresholdUnion * 100 else 12000
            val windowShift = packet[59].toInt() and 0x0F
            val frequencyTriggerWindowLength = when (windowShift) {
                4 -> 16
                5 -> 32
                6 -> 64
                7 -> 128
                8 -> 256
                9 -> 512
                10 -> 1024
                else -> 64
            }

            val freqMantissa = (packet[59].toInt() shr 4) and 0x0F
            var freqExponent = packet[60].toInt() and 0x07
            if (freqExponent >= 4) freqExponent -= 8
            val frequencyThreshold = if (frequencyTriggerEnabled && freqMantissa > 0) {
                (freqMantissa * Math.pow(10.0, freqExponent.toDouble()) / 100.0).coerceIn(0.00001, 1.0)
            } else {
                0.001
            }

            val amplitudeThresholdingEnabled = !frequencyTriggerEnabled && thresholdUnion > 0
            val amplitudeThreshold = if (amplitudeThresholdingEnabled) {
                (thresholdUnion / 32768.0).coerceIn(0.00001, 1.0)
            } else {
                0.001
            }

            AudioMothConfig(
                timePeriods = timePeriods,
                ledEnabled = ledEnabled,
                lowVoltageCutoffEnabled = packet[40].toInt() != 0,
                minimumBatteryVoltage = if (batteryCheckEnabled) 33 else 0,
                sampleRate = sampleRate,
                gain = gain,
                recordDuration = recordDuration,
                sleepDuration = sleepDuration,
                dutyEnabled = dutyEnabled,
                passFiltersEnabled = passFiltersEnabled,
                filterType = filterType,
                lowerFilter = lowerFilter,
                higherFilter = higherFilter,
                amplitudeThresholdingEnabled = amplitudeThresholdingEnabled,
                amplitudeThreshold = amplitudeThreshold,
                minimumAmplitudeThresholdDuration = minimumTriggerDuration,
                frequencyTriggerEnabled = frequencyTriggerEnabled,
                frequencyTriggerWindowLength = frequencyTriggerWindowLength,
                frequencyTriggerCentreFrequency = frequencyTriggerCentreFrequency,
                minimumFrequencyTriggerDuration = minimumTriggerDuration,
                frequencyTriggerThreshold = frequencyThreshold,
                requireAcousticConfig = (packedByte4 and 0x01) == 1,
                dailyFolders = ((packedByte0 shr 6) and 0x01) == 1,
                displayVoltageRange = ((packedByte4 shr 1) and 0x01) == 1,
                energySaverModeEnabled = (packedByte0 and 0x01) == 1,
                disable48DCFilter = ((packedByte0 shr 1) and 0x01) == 1,
                lowGainRangeEnabled = ((packedByte0 shr 4) and 0x01) == 1,
                timeSettingFromGPSEnabled = ((packedByte0 shr 2) and 0x01) == 1,
                magneticSwitchEnabled = ((packedByte0 shr 3) and 0x01) == 1
            )
        } catch (e: Exception) {
            null
        }
    }

    fun buildUsbAppPacket(config: AudioMothConfig, basePacket: ByteArray? = null): ByteArray {
        val packet = ByteArray(USB_APP_PACKET_SIZE)
        if (basePacket != null) {
            System.arraycopy(basePacket, 0, packet, 0, minOf(basePacket.size, packet.size))
        }

        // Keep the packet timestamp fresh so the device applies against current transfer time.
        writeLeInt(packet, 0, (System.currentTimeMillis() / 1000L).toInt())

        packet[4] = config.gain.coerceIn(0, 4).toByte()

        val sampleRateConfig = sampleRateConfigs.firstOrNull { it.trueSampleRate == config.sampleRate }
        if (sampleRateConfig != null) {
            packet[5] = sampleRateConfig.clockDivider.toByte()
            packet[6] = sampleRateConfig.acquisitionCycles.toByte()
            packet[7] = sampleRateConfig.oversampleRate.toByte()
            writeLeInt(packet, 8, sampleRateConfig.sampleRate)
            packet[12] = sampleRateConfig.sampleRateDivider.toByte()
        }

        writeLeU16(packet, 13, config.sleepDuration.coerceAtLeast(0))
        writeLeU16(packet, 15, config.recordDuration.coerceAtLeast(0))
        packet[17] = if (config.ledEnabled) 1 else 0

        val periods = config.timePeriods
            .map { TimePeriod(it.startMins.coerceIn(0, 1440), it.endMins.coerceIn(0, 1440)) }
            .sortedBy { it.startMins }
            .take(4)
        packet[18] = periods.size.toByte()
        repeat(4) { i ->
            val startOffset = 19 + i * 4
            if (i < periods.size) {
                writeLeU16(packet, startOffset, periods[i].startMins)
                // Desktop app encodes 00:00 end-time as 1440 (end of day).
                val encodedEnd = if (periods[i].endMins == 0) 1440 else periods[i].endMins
                writeLeU16(packet, startOffset + 2, encodedEnd)
            } else {
                writeLeU16(packet, startOffset, 0)
                writeLeU16(packet, startOffset + 2, 0)
            }
        }

        packet[40] = if (config.lowVoltageCutoffEnabled || config.minimumBatteryVoltage > 0) 1 else 0

        val packedByte2Base = packet[41].toInt() and 0xFE
        val disableBatteryLevelDisplay = if (config.minimumBatteryVoltage > 0) 0 else 1
        packet[41] = (packedByte2Base or disableBatteryLevelDisplay).toByte()

        val packedByte3Base = packet[43].toInt() and 0xFE
        packet[43] = (packedByte3Base or if (config.dutyEnabled) 0 else 1).toByte()

        var lowerFilterRaw = 0
        var higherFilterRaw = 0
        if (config.passFiltersEnabled && !config.frequencyTriggerEnabled) {
            when (config.filterType) {
                "low-pass" -> {
                    lowerFilterRaw = 0xFFFF
                    higherFilterRaw = (config.higherFilter / 100).coerceIn(0, 0xFFFF)
                }
                "high-pass" -> {
                    lowerFilterRaw = (config.lowerFilter / 100).coerceIn(0, 0xFFFF)
                    higherFilterRaw = 0xFFFF
                }
                "band-pass" -> {
                    lowerFilterRaw = (config.lowerFilter / 100).coerceIn(0, 0xFFFF)
                    higherFilterRaw = (config.higherFilter / 100).coerceIn(0, 0xFFFF)
                }
            }
        }
        writeLeU16(packet, 52, lowerFilterRaw)
        writeLeU16(packet, 54, higherFilterRaw)

        val thresholdUnion = if (config.frequencyTriggerEnabled) {
            (config.frequencyTriggerCentreFrequency / 100).coerceIn(0, 0xFFFF)
        } else if (config.amplitudeThresholdingEnabled) {
            (config.amplitudeThreshold * 32768.0).toInt().coerceIn(0, 32767)
        } else {
            0
        }
        writeLeU16(packet, 56, thresholdUnion)

        val minimumTriggerDuration = if (config.frequencyTriggerEnabled) {
            config.minimumFrequencyTriggerDuration
        } else {
            config.minimumAmplitudeThresholdDuration
        }.coerceIn(0, 63)

        var packedByte4 = 0
        if (config.requireAcousticConfig) packedByte4 = packedByte4 or 0x01
        if (config.displayVoltageRange) packedByte4 = packedByte4 or (1 shl 1)
        packedByte4 = packedByte4 or (minimumTriggerDuration shl 2)
        packet[58] = packedByte4.toByte()

        if (config.frequencyTriggerEnabled) {
            val windowShift = when (config.frequencyTriggerWindowLength) {
                16 -> 4
                32 -> 5
                64 -> 6
                128 -> 7
                256 -> 8
                512 -> 9
                1024 -> 10
                else -> 6
            }

            val percent = (config.frequencyTriggerThreshold * 100.0).coerceAtLeast(0.0)
            val exponent = when {
                percent <= 0.0 -> 0
                percent < 1.0 -> -1
                percent < 10.0 -> 0
                else -> 1
            }
            val mantissa = when {
                exponent == -1 -> (percent * 10.0).toInt().coerceIn(0, 15)
                exponent == 0 -> percent.toInt().coerceIn(0, 15)
                else -> (percent / 10.0).toInt().coerceIn(0, 15)
            }

            packet[59] = ((windowShift and 0x0F) or ((mantissa and 0x0F) shl 4)).toByte()
            packet[60] = (exponent and 0x07).toByte()
        }

        val sunScheduleBit = packet[61].toInt() and (1 shl 7)
        var packedByte0 = 0
        if (config.energySaverModeEnabled) packedByte0 = packedByte0 or 0x01
        if (config.disable48DCFilter) packedByte0 = packedByte0 or (1 shl 1)
        if (config.timeSettingFromGPSEnabled) packedByte0 = packedByte0 or (1 shl 2)
        if (config.magneticSwitchEnabled) packedByte0 = packedByte0 or (1 shl 3)
        if (config.lowGainRangeEnabled) packedByte0 = packedByte0 or (1 shl 4)
        if (config.frequencyTriggerEnabled) packedByte0 = packedByte0 or (1 shl 5)
        if (config.dailyFolders) packedByte0 = packedByte0 or (1 shl 6)
        packedByte0 = packedByte0 or sunScheduleBit
        packet[61] = packedByte0.toByte()

        return packet
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

    private fun readLeU16(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readLeInt(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeLeU16(buffer: ByteArray, offset: Int, value: Int) {
        val v = value.coerceIn(0, 0xFFFF)
        buffer[offset] = (v and 0xFF).toByte()
        buffer[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun writeLeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
