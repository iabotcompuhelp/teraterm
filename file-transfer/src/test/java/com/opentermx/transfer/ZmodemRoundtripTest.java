package com.opentermx.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZmodemRoundtripTest {

    @Test
    @Timeout(30)
    void senderToReceiverDeliversFile(@TempDir Path tmp) throws Exception {
        byte[] payload = randomBytes(4096 + 73, 0xBEEF);
        String filename = "payload.bin";

        PipedOutputStream sendOut = new PipedOutputStream();
        PipedInputStream recvIn = new PipedInputStream(sendOut, 256 * 1024);
        PipedOutputStream recvOut = new PipedOutputStream();
        PipedInputStream sendIn = new PipedInputStream(recvOut, 256 * 1024);

        TransferStream senderStream = TransferStream.forStreams(sendIn, sendOut);
        TransferStream receiverStream = TransferStream.forStreams(recvIn, recvOut);

        AtomicReference<Throwable> senderError = new AtomicReference<>();
        AtomicReference<Throwable> receiverError = new AtomicReference<>();

        Thread sender = new Thread(() -> {
            try {
                Zmodem.send(senderStream,
                    new ByteArrayInputStream(payload),
                    payload.length,
                    filename,
                    TransferListener.NOOP);
            } catch (Throwable t) {
                senderError.set(t);
            } finally {
                senderStream.close();
            }
        }, "zmodem-sender");

        Thread receiver = new Thread(() -> {
            try {
                Zmodem.receive(receiverStream, tmp, TransferListener.NOOP);
            } catch (Throwable t) {
                receiverError.set(t);
            } finally {
                receiverStream.close();
            }
        }, "zmodem-receiver");

        sender.start();
        receiver.start();
        sender.join(25_000);
        receiver.join(25_000);

        assertNull(senderError.get(), () -> "sender error: " + senderError.get());
        assertNull(receiverError.get(), () -> "receiver error: " + receiverError.get());

        Path landed = tmp.resolve(filename);
        assertTrue(Files.exists(landed), "no se creó " + landed);
        byte[] received = Files.readAllBytes(landed);
        assertArrayEquals(payload, received);
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
    }
}