package com.opentermx.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * YMODEM batch implementation. Supports multi-file transfer with block 0
 * carrying filename + size + mtime. Always uses CRC-16 (the YMODEM standard).
 * Uses 128-byte blocks (SOH); receiver also accepts 1K (STX) for incoming.
 *
 * End-of-batch is signalled by a block 0 with an empty filename (all zeros).
 *
 * Compatible with lrzsz's {@code rb -y} and {@code sb -y} (sb/rb are the
 * YMODEM variants of sx/rx).
 */
public final class Ymodem {

    private static final Logger log = LoggerFactory.getLogger(Ymodem.class);

    private static final byte SOH = 0x01;
    private static final byte STX = 0x02;
    private static final byte EOT = 0x04;
    private static final byte ACK = 0x06;
    private static final byte NAK = 0x15;
    private static final byte CAN = 0x18;
    private static final byte CPMEOF = 0x1A;
    private static final byte CRC_INIT = 0x43; // 'C'

    private static final int BLOCK_128 = 128;
    private static final int BLOCK_1024 = 1024;
    private static final int MAX_RETRIES = 10;
    private static final long INITIATE_TIMEOUT_MS = 60_000;
    private static final long ACK_TIMEOUT_MS = 10_000;
    private static final long BYTE_TIMEOUT_MS = 5_000;

    private Ymodem() {
    }

    // ===== Sender =====

    public static void send(TransferStream stream, List<File> files, TransferListener listener) throws Exception {
        long totalBytes = 0;
        for (File f : files) totalBytes += f.length();
        long sentSoFar = 0;
        for (File file : files) {
            if (listener.isCancelled()) {
                cancel(stream);
                throw new TransferException("Cancelado");
            }
            sendOneFile(stream, file, sentSoFar, totalBytes, listener);
            sentSoFar += file.length();
        }
        sendEndBlock(stream, listener);
        listener.onMessage("YMODEM completado (" + files.size() + " archivo(s))");
    }

    private static void sendOneFile(
            TransferStream stream,
            File file,
            long batchSoFar,
            long batchTotal,
            TransferListener listener) throws Exception {

        listener.onMessage("Enviando " + file.getName());
        waitForCharacter(stream, CRC_INIT, INITIATE_TIMEOUT_MS, listener);

        byte[] header = buildBlock0(file);
        sendBlock(stream, 0, header);

        waitForCharacter(stream, CRC_INIT, INITIATE_TIMEOUT_MS, listener);

        listener.onProgress(batchSoFar, batchTotal);
        try (FileInputStream in = new FileInputStream(file)) {
            int blockNum = 1;
            long sent = 0;
            byte[] buf = new byte[BLOCK_128];
            while (true) {
                if (listener.isCancelled()) {
                    cancel(stream);
                    throw new TransferException("Cancelado");
                }
                int n = readFully(in, buf);
                if (n <= 0) break;
                for (int i = n; i < BLOCK_128; i++) buf[i] = CPMEOF;
                sendBlock(stream, blockNum, buf);
                blockNum = (blockNum + 1) & 0xFF;
                sent += n;
                listener.onProgress(batchSoFar + sent, batchTotal);
                if (n < BLOCK_128) break;
            }
        }

        sendEot(stream);
    }

    private static void sendEot(TransferStream stream) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            stream.writeByte(EOT);
            int r = stream.pollByte(ACK_TIMEOUT_MS);
            if (r == (ACK & 0xFF)) return;
            if (r == (CAN & 0xFF)) throw new TransferException("Cancelado por receptor en EOT");
        }
        throw new TransferException("EOT no reconocido");
    }

    private static void sendEndBlock(TransferStream stream, TransferListener listener) throws Exception {
        waitForCharacter(stream, CRC_INIT, INITIATE_TIMEOUT_MS, listener);
        byte[] empty = new byte[BLOCK_128];
        sendBlock(stream, 0, empty);
    }

    private static void sendBlock(TransferStream stream, int blockNum, byte[] data) throws Exception {
        int retries = 0;
        while (retries < MAX_RETRIES) {
            stream.writeByte(SOH);
            stream.writeByte((byte) blockNum);
            stream.writeByte((byte) (255 - blockNum));
            stream.write(data, 0, BLOCK_128);
            int crc = Crc16.compute(data, 0, BLOCK_128);
            stream.writeByte((byte) ((crc >> 8) & 0xFF));
            stream.writeByte((byte) (crc & 0xFF));

            int r = stream.pollByte(ACK_TIMEOUT_MS);
            if (r == (ACK & 0xFF)) return;
            if (r == (CAN & 0xFF)) throw new TransferException("Cancelado por receptor en bloque " + blockNum);
            retries++;
        }
        throw new TransferException("Demasiados NAKs en bloque " + blockNum);
    }

    private static byte[] buildBlock0(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(file.getName().getBytes(StandardCharsets.UTF_8));
        out.write(0);
        out.write(String.valueOf(file.length()).getBytes(StandardCharsets.US_ASCII));
        out.write(' ');
        out.write(Long.toOctalString(file.lastModified() / 1000L).getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        byte[] padded = new byte[BLOCK_128];
        byte[] raw = out.toByteArray();
        System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, BLOCK_128));
        return padded;
    }

    private static void waitForCharacter(TransferStream stream, byte expected, long timeoutMs, TransferListener listener)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (listener.isCancelled()) throw new TransferException("Cancelado");
            int b = stream.pollByte(1000);
            if (b == (expected & 0xFF)) return;
            if (b == (CAN & 0xFF)) throw new TransferException("Cancelado por peer");
        }
        throw new TransferException("Timeout esperando 0x" + Integer.toHexString(expected & 0xFF));
    }

    // ===== Receiver =====

    public static List<Path> receive(TransferStream stream, Path outputDir, TransferListener listener) throws Exception {
        Files.createDirectories(outputDir);
        List<Path> received = new ArrayList<>();
        while (true) {
            if (listener.isCancelled()) {
                cancel(stream);
                throw new TransferException("Cancelado");
            }
            FileMeta meta = receiveBlock0(stream, listener);
            if (meta == null) {
                listener.onMessage("Lote completo (" + received.size() + " archivo(s))");
                return received;
            }
            String safeName = meta.filename.replace('/', '_').replace('\\', '_').replace("..", "_");
            Path destFile = outputDir.resolve(safeName);
            received.add(destFile);
            receiveOneFile(stream, destFile, meta.size, listener);
        }
    }

    private static FileMeta receiveBlock0(TransferStream stream, TransferListener listener) throws Exception {
        for (int retry = 0; retry < 6; retry++) {
            if (listener.isCancelled()) throw new TransferException("Cancelado");
            stream.writeByte(CRC_INIT);
            int header = stream.pollByte(3000);
            if (header < 0) continue;
            if (header == (EOT & 0xFF)) {
                stream.writeByte(ACK);
                return null;
            }
            if (header != (SOH & 0xFF) && header != (STX & 0xFF)) continue;

            int blockNum = stream.pollByte(BYTE_TIMEOUT_MS);
            int blockComp = stream.pollByte(BYTE_TIMEOUT_MS);
            if (blockNum != 0 || blockComp != 0xFF) {
                stream.writeByte(NAK);
                continue;
            }
            int blockSize = (header == (STX & 0xFF)) ? BLOCK_1024 : BLOCK_128;
            byte[] data = stream.readBytes(blockSize, 30_000);
            int crcHi = stream.pollByte(BYTE_TIMEOUT_MS);
            int crcLo = stream.pollByte(BYTE_TIMEOUT_MS);
            int rxCrc = ((crcHi & 0xFF) << 8) | (crcLo & 0xFF);
            int calcCrc = Crc16.compute(data, 0, blockSize);
            if (rxCrc != calcCrc) {
                stream.writeByte(NAK);
                continue;
            }

            stream.writeByte(ACK);
            if (data[0] == 0) return null;
            return parseBlock0(data);
        }
        throw new TransferException("Sin block 0 del emisor");
    }

    private static FileMeta parseBlock0(byte[] data) {
        int nullAt = -1;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                nullAt = i;
                break;
            }
        }
        if (nullAt <= 0) return new FileMeta("unknown", -1);
        String filename = new String(data, 0, nullAt, StandardCharsets.UTF_8);
        int restEnd = data.length;
        for (int i = nullAt + 1; i < data.length; i++) {
            if (data[i] == 0) {
                restEnd = i;
                break;
            }
        }
        String rest = new String(data, nullAt + 1, restEnd - nullAt - 1, StandardCharsets.US_ASCII).trim();
        long size = -1;
        try {
            int spaceAt = rest.indexOf(' ');
            String sizeStr = (spaceAt > 0) ? rest.substring(0, spaceAt) : rest;
            size = Long.parseLong(sizeStr);
        } catch (Exception ignored) {
        }
        return new FileMeta(filename, size);
    }

    private static void receiveOneFile(TransferStream stream, Path destFile, long expectedSize, TransferListener listener)
            throws Exception {
        listener.onMessage("Recibiendo " + destFile.getFileName() + " (" + expectedSize + " bytes)");
        listener.onProgress(0, expectedSize);

        int expectedBlock = 1;
        long received = 0;
        boolean initiated = false;

        try (OutputStream out = Files.newOutputStream(destFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (true) {
                if (!initiated) {
                    stream.writeByte(CRC_INIT);
                    initiated = true;
                }
                if (listener.isCancelled()) {
                    cancel(stream);
                    throw new TransferException("Cancelado");
                }
                int header = stream.pollByte(BYTE_TIMEOUT_MS * 2);
                if (header < 0) {
                    stream.writeByte(NAK);
                    continue;
                }
                if (header == (EOT & 0xFF)) {
                    stream.writeByte(NAK);
                    int second = stream.pollByte(BYTE_TIMEOUT_MS);
                    stream.writeByte(ACK);
                    return;
                }
                if (header == (CAN & 0xFF)) throw new TransferException("Cancelado por emisor");
                if (header != (SOH & 0xFF) && header != (STX & 0xFF)) {
                    stream.writeByte(NAK);
                    continue;
                }
                int blockSize = (header == (STX & 0xFF)) ? BLOCK_1024 : BLOCK_128;
                int blockNum = stream.pollByte(BYTE_TIMEOUT_MS);
                int blockComp = stream.pollByte(BYTE_TIMEOUT_MS);
                if (blockNum < 0 || blockComp < 0
                        || ((blockNum + blockComp) & 0xFF) != 0xFF) {
                    drain(stream, blockSize + 2);
                    stream.writeByte(NAK);
                    continue;
                }
                byte[] block;
                try {
                    block = stream.readBytes(blockSize, 30_000);
                } catch (Exception e) {
                    stream.writeByte(NAK);
                    continue;
                }
                int crcHi = stream.pollByte(BYTE_TIMEOUT_MS);
                int crcLo = stream.pollByte(BYTE_TIMEOUT_MS);
                int rxCrc = ((crcHi & 0xFF) << 8) | (crcLo & 0xFF);
                int calcCrc = Crc16.compute(block, 0, blockSize);
                if (rxCrc != calcCrc) {
                    stream.writeByte(NAK);
                    continue;
                }

                if (blockNum == expectedBlock) {
                    long writeBytes = blockSize;
                    if (expectedSize > 0 && received + writeBytes > expectedSize) {
                        writeBytes = expectedSize - received;
                    }
                    if (writeBytes > 0) {
                        out.write(block, 0, (int) writeBytes);
                        received += writeBytes;
                    }
                    stream.writeByte(ACK);
                    expectedBlock = (expectedBlock + 1) & 0xFF;
                    listener.onProgress(received, expectedSize);
                } else if (blockNum == ((expectedBlock - 1) & 0xFF)) {
                    stream.writeByte(ACK);
                } else {
                    stream.writeByte(CAN);
                    stream.writeByte(CAN);
                    throw new TransferException("Bloque fuera de secuencia: esperaba " + expectedBlock + ", recibí " + blockNum);
                }
            }
        }
    }

    // ===== Helpers =====

    private static void cancel(TransferStream stream) {
        try {
            for (int i = 0; i < 8; i++) stream.writeByte(CAN);
            stream.writeByte((byte) 0x08);
            stream.writeByte((byte) 0x08);
        } catch (Exception ignored) {
        }
    }

    private static void drain(TransferStream stream, int n) throws InterruptedException {
        for (int i = 0; i < n; i++) {
            if (stream.pollByte(100) < 0) return;
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static final class FileMeta {
        final String filename;
        final long size;

        FileMeta(String filename, long size) {
            this.filename = filename;
            this.size = size;
        }
    }
}