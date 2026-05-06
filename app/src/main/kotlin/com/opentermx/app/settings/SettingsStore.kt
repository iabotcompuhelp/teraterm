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

    fun exportSnapshot(snapshot: SetupSnapshot, target: java.io.File) {
        mapper.writerWithDefaultPrettyPrinter().writeValue(target, snapshot)
    }

    /**
     * Reads a setup file. Tries the new {@link SetupSnapshot} format first; falls back to a
     * raw {@link AppSettings} document so files written by the previous Save setup still load.
     */
    fun importSnapshot(source: java.io.File): SetupSnapshot {
        val tree = mapper.readTree(source)
        return if (tree != null && tree.has("settings")) {
            mapper.treeToValue(tree, SetupSnapshot::class.java)
        } else {
            val legacy = mapper.treeToValue(tree, AppSettings::class.java)
                ?: throw IllegalArgumentException("Empty or unreadable settings file: $source")
            SetupSnapshot(settings = legacy, savedSession = null)
        }
    }
}