package com.opentermx.tftp;

import com.opentermx.tftp.common.ErrorCode;
import com.opentermx.tftp.common.Opcode;
import com.opentermx.tftp.common.Packets;
import com.opentermx.tftp.common.TftpPacket;
import com.opentermx.tftp.common.TransferMode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PacketsTest {

    @Test
    void rrq_roundtrip_with_options() {
        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("blksize", "1024");
        opts.put("tsize", "0");
        TftpPacket.Rrq original = new TftpPacket.Rrq("firmware.bin", TransferMode.OCTET, opts);

        byte[] wire = Packets.encode(original);
        TftpPacket decoded = Packets.decode(wire, wire.length);

        assertInstanceOf(TftpPacket.Rrq.class, decoded);
        TftpPacket.Rrq r = (TftpPacket.Rrq) decoded;
        assertEquals("firmware.bin", r.filename());
        assertEquals(TransferMode.OCTET, r.mode());
        assertEquals("1024", r.options().get("blksize"));
        assertEquals("0", r.options().get("tsize"));
    }

    @Test
    void data_roundtrip() {
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xff);
        byte[] wire = Packets.encode(new TftpPacket.Data(7, payload));
        TftpPacket decoded = Packets.decode(wire, wire.length);
        assertInstanceOf(TftpPacket.Data.class, decoded);
        TftpPacket.Data d = (TftpPacket.Data) decoded;
        assertEquals(7, d.blockNumber());
        assertEquals(payload.length, d.length());
        for (int i = 0; i < payload.length; i++) {
            assertEquals(payload[i], d.payload()[d.offset() + i]);
        }
    }

    @Test
    void error_roundtrip() {
        byte[] wire = Packets.encode(new TftpPacket.Error(ErrorCode.FILE_NOT_FOUND, "missing"));
        TftpPacket decoded = Packets.decode(wire, wire.length);
        TftpPacket.Error e = assertInstanceOf(TftpPacket.Error.class, decoded);
        assertEquals(ErrorCode.FILE_NOT_FOUND, e.errorCode());
        assertEquals("missing", e.message());
    }

    @Test
    void ack_roundtrip() {
        byte[] wire = Packets.encode(new TftpPacket.Ack(42));
        TftpPacket decoded = Packets.decode(wire, wire.length);
        TftpPacket.Ack a = assertInstanceOf(TftpPacket.Ack.class, decoded);
        assertEquals(42, a.blockNumber());
        assertEquals(Opcode.ACK, a.opcode());
    }

    @Test
    void oack_roundtrip() {
        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("blksize", "8192");
        opts.put("timeout", "3");
        byte[] wire = Packets.encode(new TftpPacket.Oack(opts));
        TftpPacket decoded = Packets.decode(wire, wire.length);
        TftpPacket.Oack o = assertInstanceOf(TftpPacket.Oack.class, decoded);
        assertEquals("8192", o.options().get("blksize"));
        assertEquals("3", o.options().get("timeout"));
    }
}