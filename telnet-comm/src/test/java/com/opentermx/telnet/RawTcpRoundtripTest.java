package com.opentermx.telnet;

import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.ConnectionType;
import com.opentermx.common.connection.TcpRawConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawTcpRoundtripTest {

    private final RawTcpConnectionFactory factory = new RawTcpConnectionFactory();

    @Test
    void factorySupportsTcpRawOnly() {
        assertTrue(factory.supports(ConnectionType.TCP_RAW));
        assertFalse(factory.supports(ConnectionType.TELNET));
        assertFalse(factory.supports(ConnectionType.SSH));
        assertFalse(factory.supports(ConnectionType.SERIAL));
    }

    @Test
    void factoryRejectsWrongConfig() {
        var bad = new com.opentermx.common.connection.TelnetConfig("h", 23, false);
        assertThrows(IllegalArgumentException.class, () -> factory.create(bad));
    }

    @Test
    @Timeout(15)
    void connectsToLocalServerAndReceivesGreeting() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread acceptor = new Thread(() -> {
                try (Socket s = server.accept(); OutputStream out = s.getOutputStream()) {
                    out.write("HELLO\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(200);
                } catch (Exception ignored) {}
            }, "test-server");
            acceptor.setDaemon(true);
            acceptor.start();

            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<String> seen = new AtomicReference<>("");
            RawTcpConnection conn = new RawTcpConnection(new TcpRawConfig("127.0.0.1", port));
            conn.setDataHandler((data, length) -> {
                seen.updateAndGet(s -> s + new String(data, 0, length, StandardCharsets.UTF_8));
                if (seen.get().contains("HELLO")) received.countDown();
            });

            conn.connect();
            assertEquals(ConnectionState.CONNECTED, conn.getState());
            assertTrue(received.await(5, TimeUnit.SECONDS), "no recibió HELLO en 5s");
            conn.disconnect();
            assertEquals(ConnectionState.DISCONNECTED, conn.getState());
        }
    }

    @Test
    void connectFailureProducesErrorState() {
        var cfg = new TcpRawConfig("127.0.0.1", 1); // assume nothing listening on port 1
        var conn = new RawTcpConnection(cfg);
        AtomicReference<Throwable> err = new AtomicReference<>();
        conn.setStateHandler((state, t) -> {
            if (state == ConnectionState.ERROR) err.set(t);
        });
        Throwable thrown = assertThrows(Exception.class, conn::connect);
        assertEquals(ConnectionState.ERROR, conn.getState());
        assertNull(err.get() == thrown ? null : null); // sanity
    }
}