package com.opentermx.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * XMODEM/CRC implementation. Supports the standard 128-byte block format with
 * CRC-16 verification, automatic fallback to checksum mode, and 1K (STX) blocks
 * on the receiver path.
 */
public final class Xmodem {

    private static final Logger log = LoggerFactory.getLogger(Xmodem.class);

    static final byte SOH = 0x01;
    static final byte STX = 0x02;
    static final byte EOT = 0x04;
    static final byte ACK = 0x06;
    static final byte NAK = 0x15;
    static final byte CAN = 0x18;
    static final byte SUB = 0x1A;
    static final byte CRC_INIT = 0x43; // 'C'

    private static final int BLOCK_128 = 128;
    private static final int BLOCK_1024 = 1024;
    private static final int MAX_RETRIES = 10;
    private static final long INITIATE_TIMEOUT_MS = 60_000;
    private static final long ACK_TIMEOUT_MS = 10_000;
    private static final long BYTE_TIMEOUT_MS = 5_000;

    private Xmodem() {
    }

    public static void send(TransferStream stream, InputStream file, long fileSize, TransferListener listener)
            throws Exception {
        boolean useCrc = waitForInitiator(stream, listener);
        listener.onProgress(0, fileSize);

        byte[] block = new byte[BLOCK_128];
        int blockNum = 1;
        long totalSent = 0;

        while (true) {
            if (listener.isCancelled()) {
                cancel(stream);
                throw new TransferException("Transferencia cancelada");
            }
            int n = readFully(file, block);
            if (n <= 0) break;
            for (int i = n; i < BLOCK_128; i++) block[i] = SUB;

            int retries = 0;
            while (retries < MAX_RETRIES) {
                stream.writeByte(SOH);
                stream.writeByte((byte) blockNum);
                stream.writeByte((byte) (255 - blockNum));
                stream.write(block, 0, BLOCK_128);
                if (useCrc) {
                    int crc = Crc16.compute(block, 0, BLOCK_128);
                    stream.writeByte((byte) ((crc >> 8) & 0xFF));
                    stream.writeByte((byte) (crc & 0xFF));
                } else {
                    int sum = 0;
                    for (byte b : block) sum += b & 0xFF;
                    stream.writeByte((byte) (sum & 0xFF));
                }

                int response = stream.pollByte(ACK_TIMEOUT_MS);
                if (response == ACK) break;
                if (response == CAN) throw new TransferException("Cancelado por el receptor");
                retries++;
                listener.onMessage("Reintento bloque " + blockNum + " (" + retries + ")");
            }
            if (retries >= MAX_RETRIES) {
                cancel(stream);
                throw new TransferException("Demasiados NAKs en bloque " + blockNum);
            }

            blockNum = (blockNum + 1) & 0xFF;
            totalSent += n;
            listener.onProgress(totalSent, fileSize);

            if (n < BLOCK_128) break;
        }

        // EOT handshake
        int retries = 0;
        while (retries < MAX_RETRIES) {
            stream.writeByte(EOT);
            int r = stream.pollByte(ACK_TIMEOUT_MS);
            if (r == ACK) {
                listener.onMessage("EOT confirmado");
                return;
            }
            retries++;
        }
        throw new TransferException("EOT no reconocido");
    }

    public static void receive(TransferStream stream, OutputStream out, TransferListener listener) throws Exception {
        boolean crcMode = true;
        int initRetries = 0;
        int header;

        while (true) {
            if (listener.isCancelled()) {
                cancel(stream);
                throw new TransferException("Transferencia cancelada");
            }
            stream.writeByte(crcMode ? CRC_INIT : NAK);
            header = stream.pollByte(3000);
            if (header == SOH || header == STX) break;
            if (header == EOT) {
                stream.writeByte(ACK);
                listener.onMessage("EOT inmediato (archivo vacío)");
                return;
            }
            initRetries++;
            if (initRetries >= 6) {
                if (crcMode) {
                    crcMode = false;
                    initRetries = 0;
                    listener.onMessage("Fallback a modo checksum");
                } else {
                    throw new TransferException("Sin respuesta del emisor");
                }
            }
        }

        int expectedBlock = 1;
        long received = 0;

        while (true) {
            if (listener.isCancelled()) {
                cancel(stream);
                throw new TransferException("Transferencia cancelada");
            }
            int blockSize = (header == STX) ? BLOCK_1024 : BLOCK_128;
            if (header != SOH && header != STX && header != EOT && header != CAN) {
                stream.writeByte(NAK);
                header = stream.pollByte(BYTE_TIMEOUT_MS);
                continue;
            }
            if (header == EOT) {
                stream.writeByte(ACK);
                listener.onMessage("EOT recibido");
                return;
            }
            if (header == CAN) throw new TransferException("Cancelado por el emisor");

            int blockNum = stream.pollByte(BYTE_TIMEOUT_MS);
            int blockNumComp = stream.pollByte(BYTE_TIMEOUT_MS);
            if (blockNum < 0 || blockNumComp < 0
                    || ((blockNum + blockNumComp) & 0xFF) != 0xFF) {
                drain(stream, blockSize + (crcMode ? 2 : 1));
                stream.writeByte(NAK);
                header = stream.pollByte(BYTE_TIMEOUT_MS);
                continue;
            }

            byte[] block;
            try {
                block = stream.readBytes(blockSize, 30_000);
            } catch (Exception e) {
                stream.writeByte(NAK);
                header = stream.pollByte(BYTE_TIMEOUT_MS);
                continue;
            }

            boolean ok;
            if (crcMode) {
                int hi = stream.pollByte(BYTE_TIMEOUT_MS);
                int lo = stream.pollByte(BYTE_TIMEOUT_MS);
                if (hi < 0 || lo < 0) ok = false;
                else {
                    int receivedCrc = (hi << 8) | lo;
                    int calc = Crc16.compute(block, 0, blockSize);
                    ok = receivedCrc == calc;
                }
            } else {
                int recvSum = stream.pollByte(BYTE_TIMEOUT_MS);
                int calc = 0;
                for (byte b : block) calc += b & 0xFF;
                ok = recvSum >= 0 && (calc & 0xFF) == recvSum;
            }

            if (!ok) {
                stream.writeByte(NAK);
                header = stream.pollByte(BYTE_TIMEOUT_MS);
                continue;
            }

            if (blockNum == expectedBlock) {
                out.write(block);
                received += blockSize;
                stream.writeByte(ACK);
                expectedBlock = (expectedBlock + 1) & 0xFF;
                listener.onProgress(received, -1);
            } else if (blockNum == ((expectedBlock - 1) & 0xFF)) {
                stream.writeByte(ACK); // duplicate, ack and ignore
            } else {
                stream.writeByte(CAN);
                stream.writeByte(CAN);
                throw new TransferException("Bloque fuera de secuencia: esperaba " + expectedBlock + ", recibí " + blockNum);
            }

            header = stream.pollByte(BYTE_TIMEOUT_MS);
            if (header < 0) throw new TransferException("Timeout esperando próximo bloque");
        }
    }

    private static boolean waitForInitiator(TransferStream stream, TransferListener listener) throws Exception {
        long deadline = System.currentTimeMillis() + INITIATE_TIMEOUT_MS;
        listener.onMessage("Esperando que el receptor inicie…");
        while (System.currentTimeMillis() < deadline) {
            if (listener.isCancelled()) throw new TransferException("Transferencia cancelada");
            int b = stream.pollByte(1000);
            if (b == CRC_INIT) return true;
            if (b == NAK) return false;
            if (b == CAN) throw new TransferException("Cancelado por el receptor");
        }
        throw new TransferException("Timeout esperando al receptor");
    }

    private static void cancel(TransferStream stream) {
        try {
            stream.writeByte(CAN);
            stream.writeByte(CAN);
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
}