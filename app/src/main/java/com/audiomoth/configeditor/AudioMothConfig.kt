package com.audiomoth.configeditor

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class AudioMothConfig(
    @SerializedName("timePeriods")
    val timePeriods: List<TimePeriod> = emptyList(),
    @SerializedName("ledEnabled")
    val ledEnabled: Boolean = true,
    @SerializedName("lowVoltageCutoffEnabled")
    val lowVoltageCutoffEnabled: Boolean = true,
    @SerializedName("minimumBatteryVoltage")
    val minimumBatteryVoltage: Int = 33,
    @SerializedName("sampleRate")
    val sampleRate: Int = 48000,
    @SerializedName("gain")
    val gain: Int = 2,
    @SerializedName("recordDuration")
    val recordDuration: Int = 55,
    @SerializedName("sleepDuration")
    val sleepDuration: Int = 5,
    @SerializedName("localTime")
    val localTime: Boolean = false,
    @SerializedName("firstRecordingDateEnabled")
    val firstRecordingDateEnabled: Boolean = false,
    @SerializedName("lastRecordingDateEnabled")
    val lastRecordingDateEnabled: Boolean = false,
    @SerializedName("dutyEnabled")
    val dutyEnabled: Boolean = true,
    @SerializedName("passFiltersEnabled")
    val passFiltersEnabled: Boolean = false,
    @SerializedName("filterType")
    val filterType: String = "none",
    @SerializedName("lowerFilter")
    val lowerFilter: Int = 0,
    @SerializedName("higherFilter")
    val higherFilter: Int = 0,
    @SerializedName("amplitudeThresholdingEnabled")
    val amplitudeThresholdingEnabled: Boolean = false,
    @SerializedName("amplitudeThreshold")
    val amplitudeThreshold: Double = 0.001,
    @SerializedName("minimumAmplitudeThresholdDuration")
    val minimumAmplitudeThresholdDuration: Int = 0,
    @SerializedName("frequencyTriggerEnabled")
    val frequencyTriggerEnabled: Boolean = false,
    @SerializedName("frequencyTriggerWindowLength")
    val frequencyTriggerWindowLength: Int = 64,
    @SerializedName("frequencyTriggerCentreFrequency")
    val frequencyTriggerCentreFrequency: Int = 12000,
    @SerializedName("minimumFrequencyTriggerDuration")
    val minimumFrequencyTriggerDuration: Int = 0,
    @SerializedName("frequencyTriggerThreshold")
    val frequencyTriggerThreshold: Double = 0.001,
    @SerializedName("requireAcousticConfig")
    val requireAcousticConfig: Boolean = false,
    @SerializedName("dailyFolders")
    val dailyFolders: Boolean = false,
    @SerializedName("displayVoltageRange")
    val displayVoltageRange: Boolean = false,
    @SerializedName("amplitudeThresholdScale")
    val amplitudeThresholdScale: String = "percentage",
    @SerializedName("version")
    val version: String = "1.10.2",
    @SerializedName("energySaverModeEnabled")
    val energySaverModeEnabled: Boolean = false,
    @SerializedName("disable48DCFilter")
    val disable48DCFilter: Boolean = false,
    @SerializedName("lowGainRangeEnabled")
    val lowGainRangeEnabled: Boolean = false,
    @SerializedName("timeSettingFromGPSEnabled")
    val timeSettingFromGPSEnabled: Boolean = false,
    @SerializedName("magneticSwitchEnabled")
    val magneticSwitchEnabled: Boolean = false
) {
    fun toJsonString(): String = Gson().toJson(this)

    companion object {
        fun fromJsonString(json: String): AudioMothConfig? =
            try {
                Gson().fromJson(json, AudioMothConfig::class.java)
            } catch (e: Exception) {
                null
            }
    }
}

data class TimePeriod(
    @SerializedName("startMins")
    val startMins: Int,
    @SerializedName("endMins")
    val endMins: Int
)

data class RecordingStats(
    val filesPerDay: Int,
    val fileSizeKB: Double,
    val dailyStorageMB: Double,
    val dailyMah: Int
)

fun AudioMothConfig.scheduleValidationMessage(): String? {
    return if (timePeriods.isEmpty()) {
        "This config is not valid: add at least one recording schedule before saving or applying."
    } else {
        null
    }
}

fun AudioMothConfig.calculateRecordingStats(): RecordingStats? {
    if (timePeriods.isEmpty()) return null
    
    // Calculate total active minutes in 24h
    val totalActiveMinutes = timePeriods.sumOf { period ->
        val start = period.startMins.coerceIn(0, 1440)
        val end = period.endMins.coerceIn(0, 1440)
        (end - start).coerceAtLeast(0)
    }
    
    if (totalActiveMinutes <= 0) return null
    
    val cycleDuration = recordDuration + sleepDuration
    if (cycleDuration <= 0) return null
    
    // Calculate number of files per day
    val filesPerDay = (totalActiveMinutes * 60) / cycleDuration
    
    // Calculate file size in bytes (16-bit mono WAV: 2 bytes per sample + 44-byte WAV header)
    val fileSizeBytes = (recordDuration * sampleRate * 2) + 44
    val fileSizeKB = fileSizeBytes / 1024.0
    
    // Calculate daily storage in MB
    val dailyStorageMB = (filesPerDay * fileSizeKB) / 1024.0
    
    // Calculate daily energy consumption
    val energyPerHour = AcousticConfigBuilder.energyPerHourForSampleRate(sampleRate) ?: 0.0
    val recordingHours = totalActiveMinutes / 60.0
    val dailyMah = (recordingHours * energyPerHour).toInt()
    
    return RecordingStats(
        filesPerDay = filesPerDay,
        fileSizeKB = fileSizeKB,
        dailyStorageMB = dailyStorageMB,
        dailyMah = dailyMah
    )
}
