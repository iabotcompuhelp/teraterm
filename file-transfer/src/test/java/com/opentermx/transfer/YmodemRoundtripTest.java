package com.opentermx.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YmodemRoundtripTest {

    @Test
    @Timeout(45)
    void batchOfTwoFiles(@TempDir Path tmp) throws Exception {
        Path srcA = tmp.resolve("src-a.bin");
        Path srcB = tmp.resolve("src-b.txt");
        byte[] payloadA = randomBytes(800, 1234);
        byte[] payloadB = "Hola mundo desde YMODEM\nÚltima línea.".getBytes();
        Files.write(srcA, payloadA);
        Files.write(srcB, payloadB);

        Path destDir = tmp.resolve("out");
        Files.createDirectories(destDir);

        PipedOutputStream sendOut = new PipedOutputStream();
        PipedInputStream recvIn = new PipedInputStream(sendOut, 256 * 1024);
        PipedOutputStream recvOut = new PipedOutputStream();
        PipedInputStream sendIn = new PipedInputStream(recvOut, 256 * 1024);

        TransferStream senderStream = TransferStream.forStreams(sendIn, sendOut);
        TransferStream receiverStream = TransferStream.forStreams(recvIn, recvOut);

        AtomicReference<Throwable> senderError = new AtomicReference<>();
        AtomicReference<Throwable> receiverError = new AtomicReference<>();
        AtomicReference<List<Path>> receivedRef = new AtomicReference<>();

        Thread sender = new Thread(() -> {
            try {
                Ymodem.send(senderStream, List.of(srcA.toFile(), srcB.toFile()), TransferListener.NOOP);
            } catch (Throwable t) {
                senderError.set(t);
            } finally {
                senderStream.close();
            }
        }, "ymodem-sender");

        Thread receiver = new Thread(() -> {
            try {
                receivedRef.set(Ymodem.receive(receiverStream, destDir, TransferListener.NOOP));
            } catch (Throwable t) {
                receiverError.set(t);
            } finally {
                receiverStream.close();
            }
        }, "ymodem-receiver");

        sender.start();
        receiver.start();
        sender.join(40_000);
        receiver.join(40_000);

        assertNull(senderError.get(), () -> "sender error: " + senderError.get());
        assertNull(receiverError.get(), () -> "receiver error: " + receiverError.get());

        List<Path> received = receivedRef.get();
        assertEquals(2, received.size());

        Path dstA = destDir.resolve("src-a.bin");
        Path dstB = destDir.resolve("src-b.txt");
        assertTrue(Files.exists(dstA));
        assertTrue(Files.exists(dstB));
        assertArrayEquals(payloadA, Files.readAllBytes(dstA));
        assertArrayEquals(payloadB, Files.readAllBytes(dstB));
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
    }
}