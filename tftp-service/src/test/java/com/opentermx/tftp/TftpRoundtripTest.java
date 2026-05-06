package com.opentermx.tftp;

import com.opentermx.tftp.client.TftpClient;
import com.opentermx.tftp.client.TftpClientOptions;
import com.opentermx.tftp.common.TransferMode;
import com.opentermx.tftp.server.TftpServer;
import com.opentermx.tftp.server.TftpServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TftpRoundtripTest {

    private TftpServer server;
    private Path root;

    @BeforeEach
    void start(@TempDir Path tempDir) throws Exception {
        root = tempDir;
        server = new TftpServer(new TftpServerConfig(0, root, true, true, 5, 3));
        server.start();
        assertTrue(server.isRunning());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    @Test
    void put_then_get_roundtrip_default_blocksize() throws Exception {
        byte[] payload = randomBytes(2048);
        TftpClient client = new TftpClient();

        client.put("127.0.0.1", server.actualPort(), "out.bin",
                new ByteArrayInputStream(payload), payload.length,
                new TftpClientOptions(TransferMode.OCTET, 512, 5, 3, false), null);

        assertArrayEquals(payload, Files.readAllBytes(root.resolve("out.bin")));

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        client.get("127.0.0.1", server.actualPort(), "out.bin", sink,
                new TftpClientOptions(TransferMode.OCTET, 512, 5, 3, false), null);
        assertArrayEquals(payload, sink.toByteArray());
    }

    @Test
    void put_then_get_with_negotiated_blocksize() throws Exception {
        byte[] payload = randomBytes(20_000);
        Files.write(root.resolve("seed.bin"), payload);

        TftpClient client = new TftpClient();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        long[] last = new long[2];
        client.get("127.0.0.1", server.actualPort(), "seed.bin", sink,
                new TftpClientOptions(TransferMode.OCTET, 4096, 5, 3, true),
                (b, t) -> { last[0] = b; last[1] = t; });

        assertArrayEquals(payload, sink.toByteArray());
        assertEquals(payload.length, last[0]);
        // tsize negotiation should report the file size.
        assertEquals(payload.length, last[1]);
    }

    @Test
    void file_exact_multiple_of_blocksize_terminates_with_empty_block() throws Exception {
        // 1024 bytes with blocksize 512 → 2 full blocks, then a final empty block.
        byte[] payload = randomBytes(1024);
        TftpClient client = new TftpClient();
        client.put("127.0.0.1", server.actualPort(), "exact.bin",
                new ByteArrayInputStream(payload), payload.length,
                new TftpClientOptions(TransferMode.OCTET, 512, 5, 3, false), null);
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("exact.bin")));
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new Random(12345).nextBytes(b);
        return b;
    }
}