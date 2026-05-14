package com.opentermx.app.ui.dialog

import com.opentermx.app.settings.AdditionalSettings
import javafx.application.Platform
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.TabPane
import javafx.scene.layout.GridPane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * UI test ligera: levanta el toolkit JavaFX en proc, construye el dialog y verifica que
 * el combo del motor VT respeta `initial.terminalEngine` y que el `resultConverter` lo
 * propaga de vuelta al cerrar con OK — junto con el cambio del usuario.
 *
 * No usa TestFX a propósito: la dependencia entera no aporta nada para tres asserts y
 * añadirla obliga a coordinar versiones con JavaFX 21 en todos los runners.
 */
class AdditionalSettingsDialogTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun initToolkit() {
            try {
                Platform.startup { /* no-op: sólo nos importa que arranque */ }
            } catch (_: IllegalStateException) {
                // El toolkit ya fue iniciado por otra clase de tests en esta JVM.
            }
        }
    }

    @Test
    fun `combo refleja terminalEngine NATIVE del settings inicial`() {
        val dialog = buildDialog(AdditionalSettings(terminalEngine = "NATIVE"))
        val combo = onFx { findEngineCombo(dialog) }
        assertEquals("NATIVE", combo.value)
    }

    @Test
    fun `combo cae al default KOTLIN si settings es default`() {
        val dialog = buildDialog(AdditionalSettings())
        val combo = onFx { findEngineCombo(dialog) }
        assertEquals("KOTLIN", combo.value)
    }

    @Test
    fun `resultConverter en OK propaga el valor seleccionado por el usuario`() {
        val dialog = buildDialog(AdditionalSettings(terminalEngine = "KOTLIN"))
        val result = onFx {
            findEngineCombo(dialog).value = "NATIVE"
            dialog.resultConverter.call(ButtonType.OK)
        }
        assertEquals("NATIVE", result?.terminalEngine)
    }

    @Test
    fun `resultConverter en CANCEL devuelve null sin alterar settings`() {
        val dialog = buildDialog(AdditionalSettings(terminalEngine = "NATIVE"))
        val result = onFx { dialog.resultConverter.call(ButtonType.CANCEL) }
        assertEquals(null, result)
    }

    private fun buildDialog(initial: AdditionalSettings): AdditionalSettingsDialog =
        onFx { AdditionalSettingsDialog(initial) }

    /**
     * El dialog tiene dos combos con "NATIVE" entre sus opciones (Serial y VT engine).
     * El del motor VT es el único cuyos items son exactamente {KOTLIN, NATIVE}, así
     * lo localizamos sin acoplarnos al orden de las tabs ni a reflexión sobre campos
     * privados.
     */
    @Suppress("UNCHECKED_CAST")
    private fun findEngineCombo(dialog: AdditionalSettingsDialog): ComboBox<String> {
        val tabPane = dialog.dialogPane.content as TabPane
        for (tab in tabPane.tabs) {
            val grid = tab.content as? GridPane ?: continue
            for (node in grid.children) {
                if (node is ComboBox<*>) {
                    val items = node.items
                    if (items.size == 2 && "KOTLIN" in items && "NATIVE" in items) {
                        return node as ComboBox<String>
                    }
                }
            }
        }
        error("Combo del motor VT no encontrado en el árbol del dialog")
    }

    private fun <T> onFx(block: () -> T): T {
        if (Platform.isFxApplicationThread()) return block()
        val future = CompletableFuture<T>()
        Platform.runLater {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future.get(10, TimeUnit.SECONDS)
    }
}