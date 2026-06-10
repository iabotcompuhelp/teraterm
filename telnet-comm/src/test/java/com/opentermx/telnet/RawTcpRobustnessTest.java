package com.opentermx.telnet;

import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.TcpRawConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Robustez del ciclo de vida de conexiones (bloque post-revisión 2026-06):
 *  - el array entregado a DataHandler es del receptor (copia por invocación),
 *  - el reader thread termina de forma observable tras disconnect(),
 *  - connect() sobre una conexión viva libera el socket anterior.
 *
 * Se prueba sobre RawTcpConnection porque no negocia protocolo, pero el patrón
 * (readLoop + teardown) es el mismo en SSH, Telnet y los dos backends seriales.
 */
class RawTcpRobustnessTest {

    @Test
    @Timeout(15)
    void dataHandlerRecibeUnArrayIndependientePorInvocacion() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch twoChunks = new CountDownLatch(2);
            Thread acceptor = new Thread(() -> {
                try (Socket s = server.accept(); OutputStream out = s.getOutputStream()) {
                    out.write("AAAA".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    // Esperar a que el cliente procese el primer chunk para que ambos
                    // lleguen en read() distintos y no coalescidos en uno.
                    Thread.sleep(300);
                    out.write("BBBB".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    twoChunks.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }, "test-server");
            acceptor.setDaemon(true);
            acceptor.start();

            List<byte[]> received = new CopyOnWriteArrayList<>();
            RawTcpConnection conn = new RawTcpConnection(new TcpRawConfig("127.0.0.1", server.getLocalPort()));
            conn.setDataHandler((data, length) -> {
                received.add(data); // a propósito SIN copiar: eso es lo que el contrato permite
                twoChunks.countDown();
            });
            try {
                conn.connect();
                assertTrue(twoChunks.await(5, TimeUnit.SECONDS), "no llegaron los dos chunks en 5s");
                assertEquals(2, received.size());
                assertNotSame(received.get(0), received.get(1),
                        "la conexión entregó el mismo array dos veces: el buffer interno se filtró al handler");
                assertArrayEquals("AAAA".getBytes(StandardCharsets.UTF_8), received.get(0),
                        "el primer chunk fue pisado por el segundo: falta la copia defensiva en readLoop");
                assertArrayEquals("BBBB".getBytes(StandardCharsets.UTF_8), received.get(1));
            } finally {
                conn.disconnect();
            }
        }
    }

    @Test
    @Timeout(15)
    void readerThreadTerminaTrasDisconnect() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket s = server.accept()) {
                    // Mantener la conexión abierta sin enviar nada: el reader queda
                    // bloqueado en read(), el peor caso para la terminación.
                    Thread.sleep(10_000);
                } catch (Exception ignored) {
                }
            }, "test-server");
            acceptor.setDaemon(true);
            acceptor.start();

            AtomicReference<Thread> readerRef = new AtomicReference<>();
            RawTcpConnection conn = new RawTcpConnection(new TcpRawConfig("127.0.0.1", server.getLocalPort()));
            conn.setDataHandler((data, length) -> readerRef.compareAndSet(null, Thread.currentThread()));
            conn.connect();

            // Capturar el reader por nombre (el handler no corre si el server no manda datos).
            Thread reader = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().startsWith("rawtcp-reader-"))
                    .findFirst().orElse(null);
            assertTrue(reader != null && reader.isAlive(), "no se encontró el reader thread vivo");

            conn.disconnect();
            // disconnect() hace join(2s) internamente: al volver, el reader debe estar muerto.
            assertFalse(reader.isAlive(), "el reader sigue vivo después de disconnect()");
            assertEquals(ConnectionState.DISCONNECTED, conn.getState());
        }
    }

    @Test
    @Timeout(15)
    void connectSobreConexionVivaLiberaElSocketAnterior() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<Socket> firstAccepted = new AtomicReference<>();
            CountDownLatch secondGreeting = new CountDownLatch(1);
            Thread acceptor = new Thread(() -> {
                try {
                    Socket s1 = server.accept();
                    firstAccepted.set(s1);
                    s1.getOutputStream().write("ONE".getBytes(StandardCharsets.UTF_8));
                    s1.getOutputStream().flush();
                    try (Socket s2 = server.accept(); OutputStream out2 = s2.getOutputStream()) {
                        out2.write("TWO".getBytes(StandardCharsets.UTF_8));
                        out2.flush();
                        secondGreeting.await(5, TimeUnit.SECONDS);
                    }
                } catch (Exception ignored) {
                }
            }, "test-server");
            acceptor.setDaemon(true);
            acceptor.start();

            StringBuilder seen = new StringBuilder();
            CountDownLatch one = new CountDownLatch(1);
            RawTcpConnection conn = new RawTcpConnection(new TcpRawConfig("127.0.0.1", server.getLocalPort()));
            conn.setDataHandler((data, length) -> {
                synchronized (seen) {
                    seen.append(new String(data, 0, length, StandardCharsets.UTF_8));
                }
                if (seen.toString().contains("ONE")) one.countDown();
                if (seen.toString().contains("TWO")) secondGreeting.countDown();
            });
            try {
                conn.connect();
                assertTrue(one.await(5, TimeUnit.SECONDS), "no llegó el greeting de la primera conexión");

                // Reconectar SIN disconnect() previo: antes esto filtraba el socket viejo.
                conn.connect();
                assertEquals(ConnectionState.CONNECTED, conn.getState());
                assertTrue(secondGreeting.await(5, TimeUnit.SECONDS), "no llegó el greeting de la segunda conexión");

                // El socket de la primera conexión tiene que haber sido cerrado por el cliente:
                // el read() del lado server devuelve -1 (EOF) en lugar de quedarse colgado.
                Socket s1 = firstAccepted.get();
                s1.setSoTimeout(5_000);
                assertEquals(-1, s1.getInputStream().read(),
                        "el socket de la primera conexión sigue abierto: connect() no liberó la conexión previa");
            } finally {
                conn.disconnect();
            }
        }
    }
}
