package com.opentermx.tftp.common;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class Packets {

    public static final int DEFAULT_BLOCK_SIZE = 512;
    public static final int MIN_BLOCK_SIZE = 8;
    public static final int MAX_BLOCK_SIZE = 65464;

    public static final String OPT_BLOCKSIZE = "blksize";
    public static final String OPT_TIMEOUT = "timeout";
    public static final String OPT_TSIZE = "tsize";

    private Packets() {}

    public static byte[] encode(TftpPacket packet) {
        return switch (packet) {
            case TftpPacket.Rrq r -> encodeRequest(Opcode.RRQ, r.filename(), r.mode(), r.options());
            case TftpPacket.Wrq w -> encodeRequest(Opcode.WRQ, w.filename(), w.mode(), w.options());
            case TftpPacket.Data d -> encodeData(d);
            case TftpPacket.Ack a -> encodeAck(a);
            case TftpPacket.Error e -> encodeError(e);
            case TftpPacket.Oack o -> encodeOack(o);
        };
    }

    public static TftpPacket decode(byte[] buf, int length) {
        if (length < 2) throw new TftpException(ErrorCode.ILLEGAL_OPERATION, "Packet too short");
        int op = ((buf[0] & 0xff) << 8) | (buf[1] & 0xff);
        Opcode opcode;
        try { opcode = Opcode.of(op); }
        catch (IllegalArgumentException ex) {
            throw new TftpException(ErrorCode.ILLEGAL_OPERATION, "Unknown opcode " + op);
        }

        return switch (opcode) {
            case RRQ -> decodeRequest(buf, length, true);
            case WRQ -> decodeRequest(buf, length, false);
            case DATA -> decodeData(buf, length);
            case ACK -> decodeAck(buf, length);
            case ERROR -> decodeError(buf, length);
            case OACK -> decodeOack(buf, length);
        };
    }

    private static byte[] encodeRequest(Opcode op, String filename, TransferMode mode,
                                        Map<String, String> options) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeShort(out, op.code());
        writeNullTerminated(out, filename);
        writeNullTerminated(out, mode.wireName());
        for (Map.Entry<String, String> e : options.entrySet()) {
            writeNullTerminated(out, e.getKey());
            writeNullTerminated(out, e.getValue());
        }
        return out.toByteArray();
    }

    private static byte[] encodeData(TftpPacket.Data d) {
        byte[] out = new byte[4 + d.length()];
        out[0] = 0; out[1] = (byte) Opcode.DATA.code();
        out[2] = (byte) ((d.blockNumber() >> 8) & 0xff);
        out[3] = (byte) (d.blockNumber() & 0xff);
        System.arraycopy(d.payload(), d.offset(), out, 4, d.length());
        return out;
    }

    private static byte[] encodeAck(TftpPacket.Ack a) {
        return new byte[] {
                0, (byte) Opcode.ACK.code(),
                (byte) ((a.blockNumber() >> 8) & 0xff),
                (byte) (a.blockNumber() & 0xff)
        };
    }

    private static byte[] encodeError(TftpPacket.Error e) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, Opcode.ERROR.code());
        writeShort(out, e.errorCode().code());
        String msg = e.message() == null ? e.errorCode().defaultMessage() : e.message();
        writeNullTerminated(out, msg);
        return out.toByteArray();
    }

    private static byte[] encodeOack(TftpPacket.Oack o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, Opcode.OACK.code());
        for (Map.Entry<String, String> e : o.options().entrySet()) {
            writeNullTerminated(out, e.getKey());
            writeNullTerminated(out, e.getValue());
        }
        return out.toByteArray();
    }

    private static TftpPacket decodeRequest(byte[] buf, int length, boolean read) {
        int pos = 2;
        StringRead fr = readString(buf, pos, length);
        StringRead mr = readString(buf, fr.next, length);
        TransferMode mode = TransferMode.parse(mr.value);
        Map<String, String> opts = readOptions(buf, mr.next, length);
        return read
                ? new TftpPacket.Rrq(fr.value, mode, opts)
                : new TftpPacket.Wrq(fr.value, mode, opts);
    }

    private static TftpPacket decodeData(byte[] buf, int length) {
        if (length < 4) throw new TftpException(ErrorCode.ILLEGAL_OPERATION, "DATA too short");
        int block = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
        byte[] payload = new byte[length - 4];
        System.arraycopy(buf, 4, payload, 0, payload.length);
        return new TftpPacket.Data(block, payload);
    }

    private static TftpPacket decodeAck(byte[] buf, int length) {
        if (length < 4) throw new TftpException(ErrorCode.ILLEGAL_OPERATION, "ACK too short");
        int block = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
        return new TftpPacket.Ack(block);
    }

    private static TftpPacket decodeError(byte[] buf, int length) {
        if (length < 5) throw new TftpException(ErrorCode.ILLEGAL_OPERATION, "ERROR too short");
        int code = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
        StringRead msg = readString(buf, 4, length);
        return new TftpPacket.Error(ErrorCode.of(code), msg.value);
    }

    private static TftpPacket decodeOack(byte[] buf, int length) {
        Map<String, String> opts = readOptions(buf, 2, length);
        return new TftpPacket.Oack(opts);
    }

    private static Map<String, String> readOptions(byte[] buf, int pos, int length) {
        if (pos >= length) return Map.of();
        Map<String, String> opts = new LinkedHashMap<>();
        while (pos < length) {
            StringRead k = readString(buf, pos, length);
            if (k.value.isEmpty()) break;
            if (k.next >= length) {
                throw new TftpException(ErrorCode.ILLEGAL_OPERATION, "Truncated option " + k.value);
            }
            StringRead v = readString(buf, k.next, length);
            opts.put(k.value.toLowerCase(Locale.ROOT), v.value);
            pos = v.next;
        }
        return opts;
    }

    private record StringRead(String value, int next) {}

    private static StringRead readString(byte[] buf, int from, int length) {
        int end = from;
        while (end < length && buf[end] != 0) end++;
        if (end >= length) {
            throw new TftpException(ErrorCode.ILLEGAL_OPERATION, "Unterminated string in packet");
        }
        String value = new String(buf, from, end - from, StandardCharsets.UTF_8);
        return new StringRead(value, end + 1);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeNullTerminated(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        out.write(0);
    }

    public static int parseBlockSize(String value) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n < MIN_BLOCK_SIZE) return MIN_BLOCK_SIZE;
            if (n > MAX_BLOCK_SIZE) return MAX_BLOCK_SIZE;
            return n;
        } catch (NumberFormatException e) {
            return DEFAULT_BLOCK_SIZE;
        }
    }

    /** Convenience: serialize a payload buffer where only the first {@code len} bytes are valid. */
    public static byte[] dataPacket(int block, byte[] payload, int len) {
        ByteBuffer bb = ByteBuffer.allocate(4 + len);
        bb.putShort((short) Opcode.DATA.code());
        bb.putShort((short) block);
        bb.put(payload, 0, len);
        return bb.array();
    }
}