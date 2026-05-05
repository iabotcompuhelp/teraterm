package com.opentermx.app.ui

import javafx.scene.Scene
import javafx.scene.paint.Color

enum class ThemeMode { LIGHT, DARK }

data class TerminalColors(
    val foreground: Color,
    val background: Color,
    val cursor: Color,
    val selection: Color,
)

class Theme(initial: ThemeMode = ThemeMode.DARK) {

    private val darkUrl = resource("css/dark.css")
    private val lightUrl = resource("css/light.css")

    var mode: ThemeMode = initial
        private set

    val isDark: Boolean get() = mode == ThemeMode.DARK

    val terminalColors: TerminalColors get() = if (isDark) DARK_TERMINAL else LIGHT_TERMINAL

    fun applyTo(scene: Scene) {
        scene.stylesheets.removeAll(darkUrl, lightUrl)
        scene.stylesheets += if (isDark) darkUrl else lightUrl
    }

    fun set(mode: ThemeMode): ThemeMode {
        this.mode = mode
        return mode
    }

    fun toggle(): ThemeMode {
        mode = if (mode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        return mode
    }

    private fun resource(path: String): String =
        javaClass.classLoader.getResource(path)?.toExternalForm()
            ?: error("Stylesheet not found on classpath: $path")

    companion object {
        private val DARK_TERMINAL = TerminalColors(
            foreground = Color.web("#e6e6e6"),
            background = Color.web("#0f0f10"),
            cursor = Color.web("#e6e6e6"),
            selection = Color.web("#2a4a6b88"),
        )
        private val LIGHT_TERMINAL = TerminalColors(
            foreground = Color.web("#1a1a1a"),
            background = Color.web("#fafafa"),
            cursor = Color.web("#1a1a1a"),
            selection = Color.web("#b5d1ff88"),
        )
    }
}