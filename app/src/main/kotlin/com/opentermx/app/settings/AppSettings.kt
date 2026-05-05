package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AppSettings(
    val theme: String = "DARK",
    val locale: String = "es",
    val terminalFontFamily: String = "Consolas",
    val terminalFontSize: Double = 14.0,
    val terminalScrollbackLimit: Int = 10_000,
    val accelerators: Map<String, String> = DEFAULT_ACCELERATORS,
) {
    companion object {
        val DEFAULT_ACCELERATORS: Map<String, String> = linkedMapOf(
            "file.newSession" to "Ctrl+N",
            "file.serial" to "Ctrl+Shift+S",
            "file.ssh" to "Ctrl+Shift+H",
            "edit.copy" to "Ctrl+Shift+C",
            "edit.paste" to "Ctrl+Shift+V",
            "setup.macros" to "Ctrl+M",
            "window.closeTab" to "Ctrl+W",
            "file.exit" to "Ctrl+Q",
        )
    }
}