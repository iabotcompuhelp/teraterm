package com.opentermx.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class XmodemRoundtripTest {

    @Test
    @Timeout(20)
    void senderToReceiverDeliversFile() throws Exception {
        byte[] payload = randomBytes(1024 + 50, 0xC0FFEE);

        PipedOutputStream sendOut = new PipedOutputStream();
        PipedInputStream recvIn = new PipedInputStream(sendOut, 65_536);
        PipedOutputStream recvOut = new PipedOutputStream();
        PipedInputStream sendIn = new PipedInputStream(recvOut, 65_536);

        TransferStream senderStream = TransferStream.forStreams(sendIn, sendOut);
        TransferStream receiverStream = TransferStream.forStreams(recvIn, recvOut);

        ByteArrayOutputStream received = new ByteArrayOutputStream();

        AtomicReference<Throwable> senderError = new AtomicReference<>();
        AtomicReference<Throwable> receiverError = new AtomicReference<>();

        Thread sender = new Thread(() -> {
            try {
                Xmodem.send(senderStream,
                        new ByteArrayInputStream(payload),
                        payload.length,
                        TransferListener.NOOP);
            } catch (Throwable t) {
                senderError.set(t);
            } finally {
                senderStream.close();
            }
        }, "xmodem-sender");

        Thread receiver = new Thread(() -> {
            try {
                Xmodem.receive(receiverStream, received, TransferListener.NOOP);
            } catch (Throwable t) {
                receiverError.set(t);
            } finally {
                receiverStream.close();
            }
        }, "xmodem-receiver");

        sender.start();
        receiver.start();
        sender.join(15_000);
        receiver.join(15_000);

        assertNull(senderError.get(), () -> "sender error: " + senderError.get());
        assertNull(receiverError.get(), () -> "receiver error: " + receiverError.get());

        byte[] result = received.toByteArray();
        // XMODEM pads last block to 128 bytes with SUB
        int expectedSize = ((payload.length + 127) / 128) * 128;
        assertEquals(expectedSize, result.length);

        byte[] payloadOnly = new byte[payload.length];
        System.arraycopy(result, 0, payloadOnly, 0, payload.length);
        assertArrayEquals(payload, payloadOnly);
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
    }
}