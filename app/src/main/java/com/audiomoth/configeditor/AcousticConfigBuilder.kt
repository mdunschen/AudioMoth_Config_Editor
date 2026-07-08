package com.audiomoth.configeditor

object AcousticConfigBuilder {

    private const val USB_APP_PACKET_SIZE = 62

    // Energy consumption lookup table (mAh per hour of recording for each sample rate)
    private val energyLookup = mapOf(
        8000 to 12.0,
        16000 to 12.0,
        32000 to 14.0,
        48000 to 15.0,
        96000 to 18.0,
        192000 to 26.0,
        250000 to 28.0,
        384000 to 40.0
    )

    fun energyPerHourForSampleRate(sampleRate: Int): Double? {
        return energyLookup[sampleRate]
    }

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

    fun buildUsbAppPacket(config: AudioMothConfig): ByteArray {
        val packet = ByteArray(USB_APP_PACKET_SIZE)

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
