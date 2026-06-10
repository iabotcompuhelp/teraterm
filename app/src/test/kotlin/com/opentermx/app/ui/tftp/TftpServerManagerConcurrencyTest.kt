package com.opentermx.app.ui.tftp

import com.opentermx.tftp.server.TftpServerConfig
import javafx.application.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regresión del fix de sincronización (bloque post-revisión 2026-06): el estado del
 * manager pasó de 4 campos `@Volatile` independientes a un único holder inmutable.
 * Antes, un lector concurrente a un start/stop podía observar mezcla (running=true
 * con config=null); ahora `snapshot()` siempre devuelve un conjunto coherente.
 */
class TftpServerManagerConcurrencyTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun initToolkit() {
            try {
                Platform.startup { /* no-op: updateProps necesita el toolkit vivo */ }
            } catch (_: IllegalStateException) {
                // El toolkit ya fue iniciado por otra clase de tests en esta JVM.
            }
        }
    }

    @AfterEach
    fun cleanup() {
        // El manager es un singleton process-wide: dejarlo parado para otros tests.
        TftpServerManager.stop()
    }

    @Test
    @Timeout(60)
    fun `start-stop concurrente nunca expone snapshot incoherente`(@TempDir root: Path) {
        val cfg = TftpServerConfig(0, root, true, false, 5, 5)
        val mutators = 4
        val readers = 2
        val iterations = 200
        val pool = Executors.newFixedThreadPool(mutators + readers)
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(mutators)
        val failure = AtomicReference<Throwable>()

        repeat(mutators) {
            pool.submit {
                startGate.await()
                try {
                    repeat(iterations) {
                        if (ThreadLocalRandom.current().nextBoolean()) {
                            TftpServerManager.start(cfg, "").getOrThrow()
                        } else {
                            TftpServerManager.stop()
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }
        repeat(readers) {
            pool.submit {
                startGate.await()
                try {
                    while (done.count > 0) {
                        val snap = TftpServerManager.snapshot()
                        if (snap.running) {
                            // La incoherencia que el holder elimina: running sin config/port.
                            check(snap.config != null) { "snapshot running con config=null" }
                            check(snap.port > 0) { "snapshot running con port=${snap.port}" }
                            check(snap.csvPath == "") { "snapshot con csvPath ajeno: '${snap.csvPath}'" }
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }

        startGate.countDown()
        assertTrue(done.await(50, TimeUnit.SECONDS), "los mutators no terminaron a tiempo")
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "el pool no terminó")
        failure.get()?.let { throw AssertionError("falla concurrente: ${it.message}", it) }

        // Estado final consistente tras parar todo.
        TftpServerManager.stop()
        assertFalse(TftpServerManager.isRunning)
        assertEquals(-1, TftpServerManager.actualPort)
        assertNull(TftpServerManager.config)
        assertEquals("", TftpServerManager.csvPath)
    }

    @Test
    @Timeout(30)
    fun `start es idempotente y stop limpia todo el estado compuesto`(@TempDir root: Path) {
        val cfg = TftpServerConfig(0, root, true, false, 5, 5)
        val port1 = TftpServerManager.start(cfg, "").getOrThrow()
        val port2 = TftpServerManager.start(cfg, "").getOrThrow()
        assertEquals(port1, port2, "el segundo start debe devolver el puerto del server ya corriendo")

        val snap = TftpServerManager.snapshot()
        assertTrue(snap.running)
        assertEquals(port1, snap.port)
        assertEquals(root, snap.config?.rootDirectory())

        TftpServerManager.stop()
        val stopped = TftpServerManager.snapshot()
        assertFalse(stopped.running)
        assertEquals(-1, stopped.port)
        assertNull(stopped.config)
        assertEquals("", stopped.csvPath)
        assertTrue(TftpServerManager.history().isEmpty(), "stop() debe limpiar el history")
    }
}
