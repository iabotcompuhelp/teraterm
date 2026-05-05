package com.opentermx.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Minimal ZMODEM sender/receiver. Uses:
 *  - Hex headers for control frames (ZRQINIT, ZRINIT, ZRPOS, ZEOF, ZFIN, ZACK)
 *  - ZBIN32 headers for ZFILE / ZDATA
 *  - CRC-32 for binary headers and data subpackets
 *  - Streaming mode (no sliding-window flow control — sends file then ZEOF)
 *
 * Compatible with lrzsz's {@code rz} and {@code sz} for the common single-file
 * upload/download path. Does NOT implement: crash recovery, batch transfers
 * (multiple files in one session), encryption, compression, or sliding window.
 */
public final class Zmodem {

    private static final Logger log = LoggerFactory.getLogger(Zmodem.class);

    // Frame markers
    private static final int ZPAD = 0x2A;
    private static final int ZDLE = 0x18;
    private static final int ZBIN = 0x41;
    private static final int ZHEX = 0x42;
    private static final int ZBIN32 = 0x43;

    // Frame types
    private static final int ZRQINIT = 0;
    private static final int ZRINIT = 1;
    private static final int ZACK = 3;
    private static final int ZFILE = 4;
    private static final int ZSKIP = 5;
    private static final int ZABORT = 7;
    private static final int ZFIN = 8;
    private static final int ZRPOS = 9;
    private static final int ZDATA = 10;
    private static final int ZEOF = 11;

    // Subpacket terminators
    private static final int ZCRCE = 0x68;
    private static final int ZCRCG = 0x69;
    private static final int ZCRCQ = 0x6A;
    private static final int ZCRCW = 0x6B;

    // ZRINIT capability flags (P0)
    private static final int CANFDX = 0x01;
    private static final int CANOVIO = 0x02;
    private static final int CANFC32 = 0x20;

    // ZFILE management (ZF0)
    private static final int ZCBIN = 1;

    private static final int CHUNK_SIZE = 1024;
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    // CRC tables
    private static final int[] CRC16_TABLE = new int[256];
    private static final int[] CRC32_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
            }
            CRC16_TABLE[i] = crc & 0xFFFF;
        }
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = ((c & 1) != 0) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            CRC32_TABLE[i] = c;
        }
    }

    private static int updcrc16(int b, int crc) {
        return CRC16_TABLE[((crc >>> 8) ^ (b & 0xFF)) & 0xFF] ^ ((crc << 8) & 0xFFFF);
    }

    private static int updcrc32(int b, int crc) {
        return (crc >>> 8) ^ CRC32_TABLE[(crc ^ b) & 0xFF];
    }

    private Zmodem() {
    }

    private static final class Header {
        final int type;
        final int p0, p1, p2, p3;
        final boolean crc32;

        Header(int type, int p0, int p1, int p2, int p3, boolean crc32) {
            this.type = type;
            this.p0 = p0;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.crc32 = crc32;
        }

        long position() {
            return (p0 & 0xFFL)
                | ((p1 & 0xFFL) << 8)
                | ((p2 & 0xFFL) << 16)
                | ((p3 & 0xFFL) << 24);
        }

        static Header ctrl(int type, int p0, int p1, int p2, int p3) {
            return new Header(type, p0, p1, p2, p3, false);
        }

        static Header pos(int type, long position) {
            return new Header(type,
                (int) (position & 0xFF),
                (int) ((position >> 8) & 0xFF),
                (int) ((position >> 16) & 0xFF),
                (int) ((position >> 24) & 0xFF),
                false);
        }
    }

    private static final class DataSubpacket {
        final byte[] data;
        final int terminator;

        DataSubpacket(byte[] data, int terminator) {
            this.data = data;
            this.terminator = terminator;
        }
    }

    // ===== Public API =====

    public static void send(TransferStream stream, InputStream file, long fileSize, String filename,
                            TransferListener listener) throws Exception {
        sendHexHeader(stream, Header.ctrl(ZRQINIT, 0, 0, 0, 0));

        Header h = waitForHeader(stream, DEFAULT_TIMEOUT_MS, listener);
        while (h.type == ZRQINIT) {
            sendHexHeader(stream, Header.ctrl(ZRQINIT, 0, 0, 0, 0));
            h = waitForHeader(stream, DEFAULT_TIMEOUT_MS, listener);
        }
        if (h.type != ZRINIT) throw new TransferException("Esperaba ZRINIT, recibí tipo " + h.type);

        // Send ZFILE + metadata subpacket (CRC-32)
        String meta = filename + '\0' + fileSize + " 0 0 1 1 " + fileSize + '\0';
        byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8);
        sendBin32Header(stream, Header.ctrl(ZFILE, ZCBIN, 0, 0, 0));
        sendDataSubpacket32(stream, metaBytes, 0, metaBytes.length, ZCRCW);

        // The receiver may have emitted ZRINIT spontaneously *and* in response to our
        // ZRQINIT, so a second ZRINIT can still be queued by the time we look for ZRPOS.
        // Skip any leftover ZRINIT frames (lrzsz does the same).
        h = waitForHeader(stream, DEFAULT_TIMEOUT_MS, listener);
        while (h.type == ZRINIT) {
            h = waitForHeader(stream, DEFAULT_TIMEOUT_MS, listener);
        }
        if (h.type == ZSKIP) {
            listener.onMessage("Receptor ignoró el archivo");
            return;
        }
        if (h.type != ZRPOS) throw new TransferException("Esperaba ZRPOS, recibí tipo " + h.type);
        long startPos = h.position();
        if (startPos != 0) throw new TransferException("Resume no soportado (pos=" + startPos + ")");

        // Send ZDATA + stream of subpackets
        sendBin32Header(stream, Header.pos(ZDATA, 0));

        byte[] chunk = new byte[CHUNK_SIZE];
        long sent = 0;
        listener.onProgress(0, fileSize);
        boolean lastSent = false;
        while (!lastSent) {
            if (listener.isCancelled()) {
                cancelStream(stream);
                throw new TransferException("Cancelado");
            }
            int n = readFully(file, chunk);
            if (n <= 0) {
                sendDataSubpacket32(stream, new byte[0], 0, 0, ZCRCE);
                lastSent = true;
            } else {
                int term = (sent + n >= fileSize) ? ZCRCE : ZCRCG;
                sendDataSubpacket32(stream, chunk, 0, n, term);
                sent += n;
                listener.onProgress(sent, fileSize);
                if (term == ZCRCE) lastSent = true;
            }
        }

        // ZEOF + final handshake
        sendHexHeader(stream, Header.pos(ZEOF, fileSize));

        h = waitForHeader(stream, DEFAULT_TIMEOUT_MS, listener);
        if (h.type == ZRPOS) {
            throw new TransferException("Receptor pide retransmisión a pos " + h.position() + " (no soportado)");
        }
        if (h.type != ZRINIT) throw new TransferException("Esperaba ZRINIT tras ZEOF, recibí tipo " + h.type);

        sendHexHeader(stream, Header.ctrl(ZFIN, 0, 0, 0, 0));
        h = waitForHeader(stream, 10_000, listener);
        if (h.type != ZFIN) throw new TransferException("Esperaba ZFIN, recibí tipo " + h.type);

        stream.writeAll(new byte[]{'O', 'O'});
        listener.onMessage("ZMODEM completado");
    }

    public static void receive(TransferStream stream, Path outputDir, TransferListener listener) throws Exception {
        sendHexHeader(stream, Header.ctrl(ZRINIT, 0, 0, 0, CANFDX | CANOVIO | CANFC32));

        OutputStream fileOut = null;
        Path filePath = null;
        long pos = 0;
        long expectedSize = -1;
        int receivedFiles = 0;

        try {
            while (true) {
                if (listener.isCancelled()) {
                    cancelStream(stream);
                    throw new TransferException("Cancelado");
                }
                Header h = waitForHeader(stream, DEFAULT_TIMEOUT_MS, listener);
                switch (h.type) {
                    case ZRQINIT:
                        sendHexHeader(stream, Header.ctrl(ZRINIT, 0, 0, 0, CANFDX | CANOVIO | CANFC32));
                        break;
                    case ZFILE: {
                        DataSubpacket meta = readDataSubpacket(stream, h.crc32);
                        ParsedMeta pm = parseMeta(meta.data);
                        Files.createDirectories(outputDir);
                        filePath = outputDir.resolve(pm.filename);
                        if (fileOut != null) try { fileOut.close(); } catch (Exception ignored) {}
                        fileOut = Files.newOutputStream(filePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        expectedSize = pm.size;
                        pos = 0;
                        listener.onMessage("Recibiendo " + pm.filename + " (" + expectedSize + " bytes)");
                        listener.onProgress(0, expectedSize);
                        sendHexHeader(stream, Header.pos(ZRPOS, 0));
                        break;
                    }
                    case ZDATA: {
                        long startPos = h.position();
                        if (startPos != pos) {
                            sendHexHeader(stream, Header.pos(ZRPOS, pos));
                            break;
                        }
                        boolean done = false;
                        while (!done) {
                            DataSubpacket dp = readDataSubpacket(stream, h.crc32);
                            if (fileOut != null && dp.data.length > 0) {
                                fileOut.write(dp.data);
                                pos += dp.data.length;
                                listener.onProgress(pos, expectedSize);
                            }
                            switch (dp.terminator) {
                                case ZCRCE:
                                    done = true;
                                    break;
                                case ZCRCG:
                                    break;
                                case ZCRCQ:
                                    sendHexHeader(stream, Header.pos(ZACK, pos));
                                    break;
                                case ZCRCW:
                                    sendHexHeader(stream, Header.pos(ZACK, pos));
                                    done = true;
                                    break;
                                default:
                                    throw new TransferException("Terminador inesperado: " + dp.terminator);
                            }
                        }
                        break;
                    }
                    case ZEOF: {
                        long endPos = h.position();
                        if (endPos != pos) {
                            sendHexHeader(stream, Header.pos(ZRPOS, pos));
                            break;
                        }
                        if (fileOut != null) {
                            fileOut.close();
                            fileOut = null;
                        }
                        receivedFiles++;
                        sendHexHeader(stream, Header.ctrl(ZRINIT, 0, 0, 0, CANFDX | CANOVIO | CANFC32));
                        break;
                    }
                    case ZFIN:
                        sendHexHeader(stream, Header.ctrl(ZFIN, 0, 0, 0, 0));
                        try {
                            stream.pollByte(2000);
                            stream.pollByte(2000);
                        } catch (Exception ignored) {
                        }
                        listener.onMessage("ZMODEM completado (" + receivedFiles + " archivo)");
                        return;
                    case ZABORT:
                        throw new TransferException("Emisor abortó");
                    default:
                        log.debug("Frame ignorado tipo {}", h.type);
                        break;
                }
            }
        } finally {
            if (fileOut != null) try { fileOut.close(); } catch (Exception ignored) {}
        }
    }

    // ===== Headers =====

    private static void sendHexHeader(TransferStream s, Header h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ZPAD);
        out.write(ZPAD);
        out.write(ZDLE);
        out.write(ZHEX);
        int t = h.type & 0x7F;
        // updcrc16 is left-shift, MSB-first: it already returns M(x) * x^16 mod p(x),
        // i.e. the CRC value that the receiver will see cancel to zero when it feeds
        // the message followed by these two CRC bytes. Do NOT add further "augmentation".
        int crc = 0;
        crc = updcrc16(t, crc);
        crc = updcrc16(h.p0 & 0xFF, crc);
        crc = updcrc16(h.p1 & 0xFF, crc);
        crc = updcrc16(h.p2 & 0xFF, crc);
        crc = updcrc16(h.p3 & 0xFF, crc);
        appendHex(out, t);
        appendHex(out, h.p0 & 0xFF);
        appendHex(out, h.p1 & 0xFF);
        appendHex(out, h.p2 & 0xFF);
        appendHex(out, h.p3 & 0xFF);
        appendHex(out, (crc >> 8) & 0xFF);
        appendHex(out, crc & 0xFF);
        out.write(0x0D);  // CR
        out.write(0x8A);  // LF | 0x80
        if (h.type != ZFIN && h.type != ZACK) out.write(0x11); // XON
        s.writeAll(out.toByteArray());
    }

    private static void sendBin32Header(TransferStream s, Header h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ZPAD);
        out.write(ZDLE);
        out.write(ZBIN32);
        int crc = 0xFFFFFFFF;
        crc = updcrc32(h.type & 0xFF, crc);
        crc = updcrc32(h.p0 & 0xFF, crc);
        crc = updcrc32(h.p1 & 0xFF, crc);
        crc = updcrc32(h.p2 & 0xFF, crc);
        crc = updcrc32(h.p3 & 0xFF, crc);
        int finalCrc = ~crc;
        appendZdle(out, h.type & 0xFF);
        appendZdle(out, h.p0 & 0xFF);
        appendZdle(out, h.p1 & 0xFF);
        appendZdle(out, h.p2 & 0xFF);
        appendZdle(out, h.p3 & 0xFF);
        appendZdle(out, finalCrc & 0xFF);
        appendZdle(out, (finalCrc >> 8) & 0xFF);
        appendZdle(out, (finalCrc >> 16) & 0xFF);
        appendZdle(out, (finalCrc >> 24) & 0xFF);
        s.writeAll(out.toByteArray());
    }

    private static void appendZdle(ByteArrayOutputStream out, int b) {
        b = b & 0xFF;
        switch (b) {
            case 0x18: case 0x10: case 0x11: case 0x13: case 0x90: case 0x91: case 0x93:
                out.write(ZDLE);
                out.write(b ^ 0x40);
                return;
            default:
                out.write(b);
        }
    }

    private static void appendHex(ByteArrayOutputStream out, int b) {
        String hex = String.format("%02x", b & 0xFF);
        out.write(hex.charAt(0));
        out.write(hex.charAt(1));
    }

    // ===== Data subpackets (CRC-32 only) =====

    private static void sendDataSubpacket32(TransferStream s, byte[] data, int offset, int length, int terminator)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < length; i++) {
            int b = data[offset + i] & 0xFF;
            crc = updcrc32(b, crc);
            appendZdle(out, b);
        }
        out.write(ZDLE);
        out.write(terminator);
        crc = updcrc32(terminator, crc);
        crc = ~crc;
        appendZdle(out, crc & 0xFF);
        appendZdle(out, (crc >> 8) & 0xFF);
        appendZdle(out, (crc >> 16) & 0xFF);
        appendZdle(out, (crc >> 24) & 0xFF);
        s.writeAll(out.toByteArray());
    }

    // ===== Receive helpers =====

    private static Header waitForHeader(TransferStream s, long timeoutMs, TransferListener listener) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int padCount = 0;
        while (System.currentTimeMillis() < deadline) {
            if (listener.isCancelled()) throw new TransferException("Cancelado");
            int b = s.pollByte(500);
            if (b < 0) continue;
            if (b == ZPAD) {
                if (padCount < 2) padCount++;
                continue;
            }
            if (b == ZDLE && padCount >= 1) {
                int marker = requireByte(s, 5_000);
                switch (marker) {
                    case ZHEX: return readHexHeader(s);
                    case ZBIN: return readBinHeader(s, false);
                    case ZBIN32: return readBinHeader(s, true);
                    default:
                        padCount = 0;
                        break;
                }
            } else {
                padCount = 0;
            }
        }
        throw new TransferException("Timeout esperando frame ZMODEM");
    }

    private static int requireByte(TransferStream s, long timeoutMs) throws Exception {
        int b = s.pollByte(timeoutMs);
        if (b < 0) throw new TransferException("Timeout");
        return b;
    }

    private static int readZdleByte(TransferStream s, long timeoutMs) throws Exception {
        int b = requireByte(s, timeoutMs);
        if (b == ZDLE) {
            int next = requireByte(s, timeoutMs);
            return next ^ 0x40;
        }
        return b;
    }

    private static Header readHexHeader(TransferStream s) throws Exception {
        int[] bytes = new int[7];
        int crc = 0;
        for (int i = 0; i < 7; i++) {
            int hi = requireByte(s, 5000);
            int lo = requireByte(s, 5000);
            int v = (hexDigit(hi) << 4) | hexDigit(lo);
            bytes[i] = v & 0xFF;
            crc = updcrc16(v, crc);
        }
        if (crc != 0) {
            throw new TransferException("CRC inválido en hex header");
        }
        // Drain CR LF [XON]
        s.pollByte(500);
        s.pollByte(500);
        s.pollByte(50);
        return new Header(bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], false);
    }

    private static Header readBinHeader(TransferStream s, boolean use32) throws Exception {
        int type = readZdleByte(s, 5000);
        int p0 = readZdleByte(s, 5000);
        int p1 = readZdleByte(s, 5000);
        int p2 = readZdleByte(s, 5000);
        int p3 = readZdleByte(s, 5000);
        if (use32) {
            int crc = 0xFFFFFFFF;
            crc = updcrc32(type, crc);
            crc = updcrc32(p0, crc);
            crc = updcrc32(p1, crc);
            crc = updcrc32(p2, crc);
            crc = updcrc32(p3, crc);
            int rxCrc = readZdleByte(s, 5000)
                | (readZdleByte(s, 5000) << 8)
                | (readZdleByte(s, 5000) << 16)
                | (readZdleByte(s, 5000) << 24);
            if ((~crc) != rxCrc) throw new TransferException("CRC32 inválido en binary header");
        } else {
            int crc = 0;
            crc = updcrc16(type, crc);
            crc = updcrc16(p0, crc);
            crc = updcrc16(p1, crc);
            crc = updcrc16(p2, crc);
            crc = updcrc16(p3, crc);
            int crcHi = readZdleByte(s, 5000);
            int crcLo = readZdleByte(s, 5000);
            crc = updcrc16(crcHi, crc);
            crc = updcrc16(crcLo, crc);
            if (crc != 0) throw new TransferException("CRC16 inválido en binary header");
        }
        return new Header(type, p0, p1, p2, p3, use32);
    }

    private static DataSubpacket readDataSubpacket(TransferStream s, boolean use32) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int crc = use32 ? 0xFFFFFFFF : 0;
        while (true) {
            int b = requireByte(s, 30_000);
            if (b == ZDLE) {
                int n = requireByte(s, 5000);
                if (n == ZCRCE || n == ZCRCG || n == ZCRCQ || n == ZCRCW) {
                    if (use32) {
                        crc = updcrc32(n, crc);
                        int rxCrc = readZdleByte(s, 5000)
                            | (readZdleByte(s, 5000) << 8)
                            | (readZdleByte(s, 5000) << 16)
                            | (readZdleByte(s, 5000) << 24);
                        if ((~crc) != rxCrc) throw new TransferException("CRC32 inválido en data subpacket");
                    } else {
                        crc = updcrc16(n, crc);
                        int crcHi = readZdleByte(s, 5000);
                        int crcLo = readZdleByte(s, 5000);
                        crc = updcrc16(crcHi, crc);
                        crc = updcrc16(crcLo, crc);
                        if (crc != 0) throw new TransferException("CRC16 inválido en data subpacket");
                    }
                    return new DataSubpacket(out.toByteArray(), n);
                } else {
                    int unesc = n ^ 0x40;
                    out.write(unesc);
                    crc = use32 ? updcrc32(unesc, crc) : updcrc16(unesc, crc);
                }
            } else {
                out.write(b);
                crc = use32 ? updcrc32(b, crc) : updcrc16(b, crc);
            }
        }
    }

    // ===== Misc =====

    private static int hexDigit(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return 0;
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

    private static void cancelStream(TransferStream s) {
        try {
            byte[] cancel = new byte[10];
            for (int i = 0; i < 8; i++) cancel[i] = ZDLE; // 8x CAN
            cancel[8] = 0x08;
            cancel[9] = 0x08; // 2x BS
            s.writeAll(cancel);
        } catch (Exception ignored) {
        }
    }

    private static final class ParsedMeta {
        final String filename;
        final long size;

        ParsedMeta(String filename, long size) {
            this.filename = filename;
            this.size = size;
        }
    }

    private static ParsedMeta parseMeta(byte[] meta) {
        int nullAt = -1;
        for (int i = 0; i < meta.length; i++) {
            if (meta[i] == 0) {
                nullAt = i;
                break;
            }
        }
        if (nullAt < 0) return new ParsedMeta("unknown", -1);
        String filename = new String(meta, 0, nullAt, StandardCharsets.UTF_8)
            .replace('/', '_').replace('\\', '_').replace("..", "_");
        int restEnd = meta.length;
        for (int i = nullAt + 1; i < meta.length; i++) {
            if (meta[i] == 0) {
                restEnd = i;
                break;
            }
        }
        String rest = new String(meta, nullAt + 1, restEnd - nullAt - 1, StandardCharsets.UTF_8);
        long size = -1;
        try {
            int spaceAt = rest.indexOf(' ');
            String sizeStr = (spaceAt > 0) ? rest.substring(0, spaceAt) : rest;
            size = Long.parseLong(sizeStr.trim());
        } catch (Exception ignored) {
        }
        return new ParsedMeta(filename, size);
    }
}