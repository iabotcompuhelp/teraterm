package com.opentermx.tftp.server;

import com.opentermx.tftp.common.ErrorCode;
import com.opentermx.tftp.common.Opcode;
import com.opentermx.tftp.common.Packets;
import com.opentermx.tftp.common.TftpException;
import com.opentermx.tftp.common.TftpPacket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class TftpSession implements AutoCloseable {

    private final TftpServer server;
    private final TftpServerConfig config;
    private final InetSocketAddress peer;
    private final DatagramSocket socket;

    TftpSession(TftpServer server, TftpServerConfig config, InetSocketAddress peer) throws IOException {
        this.server = server;
        this.config = config;
        this.peer = peer;
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(config.timeoutSeconds() * 1000);
    }

    @Override public void close() {
        socket.close();
    }

    void handleRead(TftpPacket.Rrq rrq) throws IOException {
        Path file = resolveSafe(rrq.filename());
        if (file == null || !Files.exists(file) || Files.isDirectory(file)) {
            replyError(ErrorCode.FILE_NOT_FOUND, rrq.filename());
            server.emit(TftpServerEvent.transferFailed(peer, rrq.filename(), "File not found"));
            return;
        }
        if (!Files.isReadable(file)) {
            replyError(ErrorCode.ACCESS_VIOLATION, "Not readable");
            server.emit(TftpServerEvent.transferFailed(peer, rrq.filename(), "Not readable"));
            return;
        }

        long fileSize = Files.size(file);
        Negotiated neg = negotiate(rrq.options(), fileSize);
        if (neg.replyOack) {
            sendOack(neg.ackOptions);
            // Wait for ACK 0 to confirm.
            if (!awaitAck(0)) return;
        }

        server.emit(TftpServerEvent.transferStarted(peer, rrq.filename(), "GET"));

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[neg.blockSize];
            int block = 1;
            long total = 0;
            while (true) {
                int read = readFully(in, buf);
                if (read < 0) read = 0;
                byte[] payload = Packets.dataPacket(block, buf, read);
                if (!sendAndAwaitAck(payload, block)) return;
                total += read;
                if (read < neg.blockSize) {
                    server.emit(TftpServerEvent.transferCompleted(peer, rrq.filename(), total));
                    return;
                }
                block = (block + 1) & 0xffff;
            }
        }
    }

    void handleWrite(TftpPacket.Wrq wrq) throws IOException {
        Path file = resolveSafe(wrq.filename());
        if (file == null) {
            replyError(ErrorCode.ACCESS_VIOLATION, "Path escapes root");
            server.emit(TftpServerEvent.transferFailed(peer, wrq.filename(), "Path traversal"));
            return;
        }
        if (Files.exists(file)) {
            replyError(ErrorCode.FILE_EXISTS, wrq.filename());
            server.emit(TftpServerEvent.transferFailed(peer, wrq.filename(), "Already exists"));
            return;
        }
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);

        Negotiated neg = negotiate(wrq.options(), -1);
        if (neg.replyOack) {
            sendOack(neg.ackOptions);
        } else {
            // No options: ACK block 0 to start the upload.
            sendDatagram(Packets.encode(new TftpPacket.Ack(0)));
        }

        server.emit(TftpServerEvent.transferStarted(peer, wrq.filename(), "PUT"));

        long total = 0;
        int expectedBlock = 1;
        byte[] recvBuf = new byte[neg.blockSize + 4];
        try (OutputStream out = Files.newOutputStream(file)) {
            while (true) {
                DatagramPacket dgram = new DatagramPacket(recvBuf, recvBuf.length);
                int retries = 0;
                TftpPacket.Data data = null;
                while (data == null) {
                    try {
                        socket.receive(dgram);
                    } catch (SocketTimeoutException ste) {
                        if (++retries > config.maxRetries()) {
                            server.emit(TftpServerEvent.transferFailed(peer, wrq.filename(), "Timeout"));
                            return;
                        }
                        // Re-ACK previous block (or OACK) to nudge.
                        if (expectedBlock == 1 && neg.replyOack) sendOack(neg.ackOptions);
                        else sendDatagram(Packets.encode(new TftpPacket.Ack((expectedBlock - 1) & 0xffff)));
                        continue;
                    }
                    if (!dgram.getSocketAddress().equals(peer)) {
                        sendErrorTo(dgram.getSocketAddress(), ErrorCode.UNKNOWN_TID, "Unknown TID");
                        continue;
                    }
                    TftpPacket pkt;
                    try { pkt = Packets.decode(dgram.getData(), dgram.getLength()); }
                    catch (TftpException ex) {
                        replyError(ErrorCode.ILLEGAL_OPERATION, ex.getMessage());
                        server.emit(TftpServerEvent.transferFailed(peer, wrq.filename(), ex.getMessage()));
                        return;
                    }
                    if (pkt instanceof TftpPacket.Error err) {
                        server.emit(TftpServerEvent.transferFailed(peer, wrq.filename(),
                                "Client error: " + err.message()));
                        return;
                    }
                    if (!(pkt instanceof TftpPacket.Data d)) {
                        replyError(ErrorCode.ILLEGAL_OPERATION, "Expected DATA");
                        server.emit(TftpServerEvent.transferFailed(peer, wrq.filename(),
                                "Expected DATA, got " + pkt.opcode()));
                        return;
                    }
                    if (d.blockNumber() != expectedBlock) {
                        sendDatagram(Packets.encode(new TftpPacket.Ack(d.blockNumber())));
                        continue;
                    }
                    data = d;
                }
                out.write(data.payload(), data.offset(), data.length());
                sendDatagram(Packets.encode(new TftpPacket.Ack(data.blockNumber())));
                total += data.length();
                if (data.length() < neg.blockSize) {
                    server.emit(TftpServerEvent.transferCompleted(peer, wrq.filename(), total));
                    return;
                }
                expectedBlock = (expectedBlock + 1) & 0xffff;
            }
        }
    }

    void replyError(ErrorCode code, String message) {
        try {
            sendDatagram(Packets.encode(new TftpPacket.Error(code, message)));
        } catch (IOException ignored) {}
    }

    private void sendErrorTo(java.net.SocketAddress target, ErrorCode code, String message) {
        try {
            byte[] payload = Packets.encode(new TftpPacket.Error(code, message));
            socket.send(new DatagramPacket(payload, payload.length, target));
        } catch (IOException ignored) {}
    }

    private boolean sendAndAwaitAck(byte[] payload, int block) throws IOException {
        DatagramPacket dgram = new DatagramPacket(payload, payload.length, peer);
        byte[] recvBuf = new byte[1024];
        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            socket.send(dgram);
            try {
                DatagramPacket reply = new DatagramPacket(recvBuf, recvBuf.length);
                socket.receive(reply);
                if (!reply.getSocketAddress().equals(peer)) {
                    sendErrorTo(reply.getSocketAddress(), ErrorCode.UNKNOWN_TID, "Unknown TID");
                    attempt--;
                    continue;
                }
                TftpPacket pkt = Packets.decode(reply.getData(), reply.getLength());
                if (pkt instanceof TftpPacket.Error err) {
                    server.emit(TftpServerEvent.transferFailed(peer, "?",
                            "Client error: " + err.message()));
                    return false;
                }
                if (pkt instanceof TftpPacket.Ack ack && ack.blockNumber() == block) {
                    return true;
                }
            } catch (SocketTimeoutException ignored) {}
        }
        server.emit(TftpServerEvent.transferFailed(peer, "?", "No ACK for block " + block));
        return false;
    }

    private boolean awaitAck(int block) throws IOException {
        byte[] buf = new byte[1024];
        for (int attempt = 0; attempt < config.maxRetries(); attempt++) {
            try {
                DatagramPacket dgram = new DatagramPacket(buf, buf.length);
                socket.receive(dgram);
                if (!dgram.getSocketAddress().equals(peer)) continue;
                TftpPacket pkt = Packets.decode(dgram.getData(), dgram.getLength());
                if (pkt instanceof TftpPacket.Ack ack && ack.blockNumber() == block) return true;
                if (pkt instanceof TftpPacket.Error) return false;
            } catch (SocketTimeoutException ignored) {}
        }
        return false;
    }

    private void sendOack(Map<String, String> options) throws IOException {
        sendDatagram(Packets.encode(new TftpPacket.Oack(options)));
    }

    private void sendDatagram(byte[] payload) throws IOException {
        socket.send(new DatagramPacket(payload, payload.length, peer));
    }

    private Path resolveSafe(String filename) {
        Path root = config.rootDirectory().toAbsolutePath().normalize();
        Path target = root.resolve(filename).normalize().toAbsolutePath();
        return target.startsWith(root) ? target : null;
    }

    private Negotiated negotiate(Map<String, String> requested, long fileSize) {
        int blockSize = Packets.DEFAULT_BLOCK_SIZE;
        Map<String, String> ack = new LinkedHashMap<>();
        if (requested.containsKey(Packets.OPT_BLOCKSIZE)) {
            blockSize = Packets.parseBlockSize(requested.get(Packets.OPT_BLOCKSIZE));
            ack.put(Packets.OPT_BLOCKSIZE, Integer.toString(blockSize));
        }
        if (requested.containsKey(Packets.OPT_TIMEOUT)) {
            String t = requested.get(Packets.OPT_TIMEOUT);
            // Echo back what the client asked for (within reason).
            ack.put(Packets.OPT_TIMEOUT, t);
        }
        if (requested.containsKey(Packets.OPT_TSIZE)) {
            // RRQ: respond with actual file size.  WRQ: echo the client's size.
            String value = fileSize >= 0 ? Long.toString(fileSize) : requested.get(Packets.OPT_TSIZE);
            ack.put(Packets.OPT_TSIZE, value);
        }
        return new Negotiated(blockSize, !ack.isEmpty(), ack);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private record Negotiated(int blockSize, boolean replyOack, Map<String, String> ackOptions) {}

    @SuppressWarnings("unused")
    private static String opcodeName(Opcode op) { return op.name(); }
}