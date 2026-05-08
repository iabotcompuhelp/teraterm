package com.opentermx.app.i18n

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * Smoke test for the i18n keys added by the Help menu and ErrorDialog. Catches typos and
 * out-of-sync translations between messages.properties and messages_en.properties before
 * they reach a runtime [MissingResourceException] in production.
 */
class HelpAndErrorStringsTest {

    private val keys = listOf(
        // Help menu items
        "help.quickGuide", "help.shortcuts", "help.systemInfo",
        "help.openLogsDir", "help.openSpec", "help.reportIssue",
        "about.details",
        // Help dialogs
        "help.guide.title", "help.guide.header",
        "help.shortcuts.title", "help.shortcuts.header", "help.shortcuts.hint",
        "help.systemInfo.title", "help.systemInfo.header",
        // Quick guide tabs (title + body)
        "help.guide.firstSteps.title", "help.guide.firstSteps.body",
        "help.guide.ssh.title", "help.guide.ssh.body",
        "help.guide.telnet.title", "help.guide.telnet.body",
        "help.guide.serial.title", "help.guide.serial.body",
        "help.guide.tftp.title", "help.guide.tftp.body",
        "help.guide.macros.title", "help.guide.macros.body",
        "help.guide.transfer.title", "help.guide.transfer.body",
        "help.guide.shortcuts.title", "help.guide.shortcuts.body",
        // Spec / report
        "help.openSpec.missing.header", "help.openSpec.missing.body",
        "help.reportIssue.header", "help.reportIssue.body",
        // ErrorDialog defaults
        "error.dialog.title.info", "error.dialog.title.warning", "error.dialog.title.error",
        "error.dialog.header.info", "error.dialog.header.warning", "error.dialog.header.error",
        "error.dialog.details.label", "error.dialog.copy", "error.dialog.copied",
        "error.dialog.openLogs",
        // Specific error headers wired across the UI
        "error.connection.title", "error.connection.header",
        "error.openLogsDir.header", "error.openLogsDir.body",
        "error.openSpec.header", "error.openSpec.body",
        "sftp.error.header",
        "pf.listError.header", "pf.removeError.header",
        "pf.addError.header", "pf.addedDynamic.header",
    )

    @Test
    fun `every help and error key resolves in Spanish`() = assertResolves(Locale.of("es"))

    @Test
    fun `every help and error key resolves in English`() = assertResolves(Locale.of("en"))

    private fun assertResolves(locale: Locale) {
        val bundle: ResourceBundle = ResourceBundle.getBundle("i18n.messages", locale)
        for (key in keys) {
            val value = try {
                bundle.getString(key)
            } catch (e: MissingResourceException) {
                throw AssertionError("Missing i18n key '$key' in locale '$locale'", e)
            }
            assertNotNull(value, "Key '$key' resolved to null in locale '$locale'")
            assertFalse(value.isBlank(), "Key '$key' resolved to blank value in locale '$locale'")
        }
    }
}