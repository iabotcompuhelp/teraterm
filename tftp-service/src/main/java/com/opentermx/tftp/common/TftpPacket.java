package com.opentermx.tftp.common;

import java.util.LinkedHashMap;
import java.util.Map;

public sealed interface TftpPacket
        permits TftpPacket.Rrq, TftpPacket.Wrq, TftpPacket.Data,
                TftpPacket.Ack, TftpPacket.Error, TftpPacket.Oack {

    Opcode opcode();

    record Rrq(String filename, TransferMode mode, Map<String, String> options) implements TftpPacket {
        public Rrq {
            options = options == null ? Map.of() : Map.copyOf(options);
        }
        public Rrq(String filename, TransferMode mode) {
            this(filename, mode, Map.of());
        }
        @Override public Opcode opcode() { return Opcode.RRQ; }
    }

    record Wrq(String filename, TransferMode mode, Map<String, String> options) implements TftpPacket {
        public Wrq {
            options = options == null ? Map.of() : Map.copyOf(options);
        }
        public Wrq(String filename, TransferMode mode) {
            this(filename, mode, Map.of());
        }
        @Override public Opcode opcode() { return Opcode.WRQ; }
    }

    record Data(int blockNumber, byte[] payload, int offset, int length) implements TftpPacket {
        public Data(int blockNumber, byte[] payload) {
            this(blockNumber, payload, 0, payload.length);
        }
        @Override public Opcode opcode() { return Opcode.DATA; }
    }

    record Ack(int blockNumber) implements TftpPacket {
        @Override public Opcode opcode() { return Opcode.ACK; }
    }

    record Error(ErrorCode errorCode, String message) implements TftpPacket {
        @Override public Opcode opcode() { return Opcode.ERROR; }
    }

    record Oack(Map<String, String> options) implements TftpPacket {
        public Oack {
            // Preserve insertion order for deterministic encoding.
            options = options == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(options));
        }
        @Override public Opcode opcode() { return Opcode.OACK; }
    }
}