package com.opentermx.app.settings

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object SettingsStore {

    private val log = LoggerFactory.getLogger(javaClass)

    val configDir: Path = Path.of(System.getProperty("user.home"), ".opentermx")
    private val settingsFile: Path = configDir.resolve("settings.json")

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    fun load(): AppSettings {
        return try {
            if (Files.isRegularFile(settingsFile)) {
                mapper.readValue(settingsFile.toFile(), AppSettings::class.java)
            } else {
                AppSettings()
            }
        } catch (e: Exception) {
            log.warn("No se pudo leer {} ({}); usando defaults", settingsFile, e.message)
            AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        try {
            Files.createDirectories(configDir)
            mapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), settings)
        } catch (e: Exception) {
            log.warn("No se pudo guardar {} ({})", settingsFile, e.message)
        }
    }
}