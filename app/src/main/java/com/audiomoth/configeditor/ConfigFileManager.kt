package com.audiomoth.configeditor

import android.content.Context
import java.io.File

object ConfigFileManager {
    fun saveConfig(context: Context, config: AudioMothConfig, filename: String): Boolean {
        return try {
            val file = File(context.getExternalFilesDir(null), filename)
            file.writeText(config.toJsonString())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadConfig(context: Context, filename: String): AudioMothConfig? {
        return try {
            val file = File(context.getExternalFilesDir(null), filename)
            if (file.exists()) {
                AudioMothConfig.fromJsonString(file.readText())
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun listConfigs(context: Context): List<String> {
        return try {
            context.getExternalFilesDir(null)?.listFiles()?.filter {
                it.extension == "config"
            }?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun deleteConfig(context: Context, filename: String): Boolean {
        return try {
            val file = File(context.getExternalFilesDir(null), filename)
            file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
