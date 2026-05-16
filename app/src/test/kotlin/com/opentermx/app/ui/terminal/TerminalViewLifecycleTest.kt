package com.opentermx.app.ui.terminal

import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Regresión del NPE en `NGCanvas$RenderBuf.validate` cuando se abría cualquier sesión.
 *
 * Causa: el AnimationTimer iniciado en el constructor de [TerminalView] llamaba
 * `renderer.paint(canvas, ...)` desde la primera pulse, incluso antes de que el Canvas
 * estuviese en una Scene con Window mostrada. Eso encolaba draw commands en el NGCanvas;
 * cuando la pulse intentaba reconciliar y allocar la RTTexture, `createGraphics()` devolvía
 * null y se propagaba un NPE en `renderForcedContent`.
 *
 * Fix: el animator hace bail-out temprano si `canvas.scene?.window?.isShowing != true`.
 * Estos tests verifican el contrato:
 *  - Construir y mantener varias vistas sin Scene mostrada NO pinta (ni explota).
 *  - Una vez attached a un `Stage.show()`, el animator sí pinta.
 */
class TerminalViewLifecycleTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun initToolkit() {
            try {
                Platform.startup { /* no-op */ }
            } catch (_: IllegalStateException) {
                // Otro test arrancó el toolkit.
            }
        }
    }

    /**
     * Reproduce el escenario de producción: abrir CUALQUIER sesión instancia un
     * `TerminalView` que NO está aún en el Scene/Tab mostrado al momento de arrancar
     * su AnimationTimer. Hacerlo 30 veces en rápida sucesión simula el "abrir muchas
     * sesiones" y deja un margen para que las pulses corran.
     *
     * Pre-fix: el toolkit acumulaba el NPE en su uncaught-handler interno (pero el
     * test podía pasar igual porque la excepción no propaga al hilo del test).
     * Post-fix: la lectura de `lastPaintedVersion` confirma que el animator
     * gateó correctamente.
     */
    @Test
    fun `30 vistas sin Scene mostrada no pintan y no explotan`() {
        val views = onFx { (1..30).map { TerminalView() } }

        // Dejá correr ~6 pulses de JavaFX (60 FPS → ~100ms son ~6 frames).
        Thread.sleep(150)

        onFx {
            views.forEach { view ->
                val version = readLastPaintedVersion(view)
                assertEquals(
                    -1L,
                    version,
                    "Animator no debería pintar antes de que la Scene esté mostrada",
                )
            }
        }

        // Limpieza para no dejar nativeTerminals colgando si el motor fuese NATIVE.
        onFx { views.forEach { it.dispose() } }
    }

    /**
     * El gate también tiene que des-gatear cuando la vista pasa a estar en un
     * `Stage.show()` real. Headless con `prism.order=sw` (que ya seteamos en
     * `app/build.gradle.kts` para tests) basta para validar el contrato.
     */
    @Test
    fun `vista en Stage mostrado pasa el gate y pinta`() {
        val view = onFx { TerminalView() }

        // Antes de show(): el gate sigue cerrado.
        onFx {
            val stage = Stage()
            stage.scene = Scene(view, 320.0, 200.0)
            stage.show()
            stage.opacity = 0.0 // evitamos flicker visible en runners locales
            // Forzamos algo de contenido para que `buffer.version` cambie.
            val bytes = "hola\r\n".toByteArray()
            view.appendBytes(bytes, bytes.size)
        }

        // Dejá correr unas pulses para que el animator atienda el cambio.
        Thread.sleep(300)

        onFx {
            val version = readLastPaintedVersion(view)
            assertNotEquals(
                -1L,
                version,
                "Después de show() el animator tiene que haber pintado al menos una vez",
            )
            view.dispose()
        }
    }

    private fun readLastPaintedVersion(view: TerminalView): Long {
        val field = TerminalView::class.java.getDeclaredField("lastPaintedVersion")
        field.isAccessible = true
        return field.getLong(view)
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
