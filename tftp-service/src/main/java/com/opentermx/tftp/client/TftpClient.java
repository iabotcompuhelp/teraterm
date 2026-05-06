package com.opentermx.tftp.client;

import com.opentermx.tftp.common.ErrorCode;
import com.opentermx.tftp.common.Opcode;
import com.opentermx.tftp.common.Packets;
import com.opentermx.tftp.common.TftpException;
import com.opentermx.tftp.common.TftpPacket;
import com.opentermx.tftp.common.TransferMode;
import com.opentermx.tftp.common.TransferProgress;
import com.opentermx.tftp.server.TftpServerEvent;
import com.opentermx.tftp.server.TftpServerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Synchronous TFTP client. Each call to {@link #put} or {@link #get} runs the full handshake on a
 * fresh ephemeral socket and blocks until completion or failure. Use a separate thread if the
 * caller needs concurrency.
 *
 * <p>Supports RFC 2347 (option extension), RFC 2348 (blocksize) and RFC 2349 (timeout, tsize).
 */
public final class TftpClient {

    private static final Logger log = LoggerFactory.getLogger(TftpClient.class);

    private final CopyOnWriteArrayList<TftpServerListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public void addListener(TftpServerListener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(TftpServerListener listener) {
        listeners.remove(listener);
    }

    private void emit(TftpServerEvent event) {
        for (TftpServerListener l : listeners) {
            try { l.onEvent(event); } catch (RuntimeException re) {
                log.warn("Listener threw", re);
            }
        }
    }

    public void put(String host, int port, String remoteFile, InputStream source, long sourceSize,
                    TftpClientOptions opts, TransferProgress progress) throws IOException {
        if (opts == null) opts = TftpClientOptions.defaults();
        if (progress == null) progress = TransferProgress.noop();
        cancelled = false;

        InetAddress address = InetAddress.getByName(host);
        InetSocketAddress targetPeer = new InetSocketAddress(address, port);
        emit(TftpServerEvent.transferStarted(targetPeer, remoteFile, "PUT"));
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(opts.timeoutSeconds() * 1000);

            Map<String, String> wantedOptions = buildOptions(opts, sourceSize, true);
            byte[] wrqBytes = Packets.encode(new TftpPacket.Wrq(remoteFile, opts.mode(), wantedOptions));
            socket.send(new DatagramPacket(wrqBytes, wrqBytes.length, address, port));

            HandshakeResult hs = waitForServerReply(socket, address, port, wrqBytes, opts);
            int negotiatedBlockSize = hs.blockSize;
            SocketAddress peer = hs.peer;

            byte[] buffer = new byte[negotiatedBlockSize];
            int block = 1;
            long totalSent = 0;
            int read;
            boolean done = false;
            while (!done) {
                checkCancelled(socket, peer);
                read = readFully(source, buffer);
                if (read < 0) read = 0;
                totalSent += read;
                byte[] dataPkt = Packets.dataPacket(block, buffer, read);
                sendAndAwaitAck(socket, peer, dataPkt, block, opts);
                progress.onProgress(totalSent, sourceSize);
                if (read < negotiatedBlockSize) {
                    done = true;
                }
                block = (block + 1) & 0xffff;
            }
            log.info("PUT {} ({} bytes) completed in {} blocks", remoteFile, totalSent, block);
            emit(TftpServerEvent.transferCompleted(targetPeer, remoteFile, totalSent));
        } catch (RuntimeException | IOException ex) {
            emit(TftpServerEvent.transferFailed(targetPeer, remoteFile, ex.getMessage()));
            throw ex;
        }
    }

    public void get(String host, int port, String remoteFile, OutputStream sink,
                    TftpClientOptions opts, TransferProgress progress) throws IOException {
        if (opts == null) opts = TftpClientOptions.defaults();
        if (progress == null) progress = TransferProgress.noop();
        cancelled = false;

        InetAddress address = InetAddress.getByName(host);
        InetSocketAddress targetPeer = new InetSocketAddress(address, port);
        emit(TftpServerEvent.transferStarted(targetPeer, remoteFile, "GET"));
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(opts.timeoutSeconds() * 1000);

            Map<String, String> wantedOptions = buildOptions(opts, -1, false);
            byte[] rrqBytes = Packets.encode(new TftpPacket.Rrq(remoteFile, opts.mode(), wantedOptions));
            socket.send(new DatagramPacket(rrqBytes, rrqBytes.length, address, port));

            HandshakeResult hs = waitForServerReply(socket, address, port, rrqBytes, opts);
            int negotiatedBlockSize = hs.blockSize;
            long announcedSize = hs.tsize;
            SocketAddress peer = hs.peer;

            int expectedBlock;
            if (hs.firstData != null) {
                writeData(sink, hs.firstData);
                expectedBlock = (hs.firstData.blockNumber() + 1) & 0xffff;
                ackBlock(socket, peer, hs.firstData.blockNumber());
                progress.onProgress(hs.firstData.length(), announcedSize);
                if (hs.firstData.length() < negotiatedBlockSize) {
                    return;
                }
            } else {
                // OACK path: ACK block 0 to confirm options.
                ackBlock(socket, peer, 0);
                expectedBlock = 1;
            }

            long total = (hs.firstData != null) ? hs.firstData.length() : 0L;
            byte[] recvBuf = new byte[negotiatedBlockSize + 4];
            while (true) {
                checkCancelled(socket, peer);
                DatagramPacket dgram = new DatagramPacket(recvBuf, recvBuf.length);
                int retries = 0;
                TftpPacket.Data data = null;
                while (data == null) {
                    try {
                        socket.receive(dgram);
                    } catch (SocketTimeoutException ste) {
                        if (++retries > opts.maxRetries()) {
                            throw new TftpException(ErrorCode.NOT_DEFINED,
                                    "Timed out waiting for block " + expectedBlock);
                        }
                        // Re-ACK the previous block to nudge the server.
                        ackBlock(socket, peer, (expectedBlock - 1) & 0xffff);
                        continue;
                    }
                    if (!dgram.getSocketAddress().equals(peer)) {
                        // Stray datagram from a different TID — reply with ERROR(5).
                        sendError(socket, dgram.getSocketAddress(), ErrorCode.UNKNOWN_TID, "Unknown TID");
                        continue;
                    }
                    TftpPacket pkt = Packets.decode(dgram.getData(), dgram.getLength());
                    if (pkt instanceof TftpPacket.Error err) {
                        throw new TftpException(err.errorCode(), err.message());
                    }
                    if (!(pkt instanceof TftpPacket.Data d)) {
                        sendError(socket, peer, ErrorCode.ILLEGAL_OPERATION, "Expected DATA");
                        throw new TftpException(ErrorCode.ILLEGAL_OPERATION,
                                "Expected DATA, got " + pkt.opcode());
                    }
                    if (d.blockNumber() != expectedBlock) {
                        // Duplicate from earlier block — re-ACK and keep waiting.
                        ackBlock(socket, peer, d.blockNumber());
                        continue;
                    }
                    data = d;
                }
                writeData(sink, data);
                ackBlock(socket, peer, data.blockNumber());
                total += data.length();
                progress.onProgress(total, announcedSize);
                if (data.length() < negotiatedBlockSize) {
                    log.info("GET {} ({} bytes) completed", remoteFile, total);
                    emit(TftpServerEvent.transferCompleted(targetPeer, remoteFile, total));
                    return;
                }
                expectedBlock = (expectedBlock + 1) & 0xffff;
            }
        } catch (RuntimeException | IOException ex) {
            emit(TftpServerEvent.transferFailed(targetPeer, remoteFile, ex.getMessage()));
            throw ex;
        }
    }

    private void sendAndAwaitAck(DatagramSocket socket, SocketAddress peer, byte[] payload,
                                 int block, TftpClientOptions opts) throws IOException {
        DatagramPacket dgram = new DatagramPacket(payload, payload.length, peer);
        byte[] recvBuf = new byte[1024];
        for (int attempt = 0; attempt <= opts.maxRetries(); attempt++) {
            socket.send(dgram);
            try {
                DatagramPacket reply = new DatagramPacket(recvBuf, recvBuf.length);
                socket.receive(reply);
                if (!reply.getSocketAddress().equals(peer)) {
                    sendError(socket, reply.getSocketAddress(), ErrorCode.UNKNOWN_TID, "Unknown TID");
                    attempt--;
                    continue;
                }
                TftpPacket pkt = Packets.decode(reply.getData(), reply.getLength());
                if (pkt instanceof TftpPacket.Error err) {
                    throw new TftpException(err.errorCode(), err.message());
                }
                if (pkt instanceof TftpPacket.Ack ack && ack.blockNumber() == block) {
                    return;
                }
                // Stale ACK or different opcode — keep retrying within this attempt budget.
            } catch (SocketTimeoutException ste) {
                // Loop and resend.
            }
        }
        throw new TftpException(ErrorCode.NOT_DEFINED, "No ACK for block " + block + " after retries");
    }

    private HandshakeResult waitForServerReply(DatagramSocket socket, InetAddress address, int port,
                                               byte[] originalRequest, TftpClientOptions opts)
            throws IOException {
        byte[] recvBuf = new byte[Math.max(opts.blockSize() + 4, 1024)];
        DatagramPacket dgram = new DatagramPacket(recvBuf, recvBuf.length);
        int retries = 0;
        while (true) {
            try {
                socket.receive(dgram);
            } catch (SocketTimeoutException ste) {
                if (++retries > opts.maxRetries()) {
                    throw new TftpException(ErrorCode.NOT_DEFINED, "No response from " + address);
                }
                socket.send(new DatagramPacket(originalRequest, originalRequest.length, address, port));
                continue;
            }
            SocketAddress peer = dgram.getSocketAddress();
            TftpPacket pkt = Packets.decode(dgram.getData(), dgram.getLength());

            if (pkt instanceof TftpPacket.Error err) {
                throw new TftpException(err.errorCode(), err.message());
            }
            if (pkt instanceof TftpPacket.Oack oack) {
                int negotiatedBlockSize = Packets.DEFAULT_BLOCK_SIZE;
                long tsize = -1L;
                String bs = oack.options().get(Packets.OPT_BLOCKSIZE);
                if (bs != null) negotiatedBlockSize = Packets.parseBlockSize(bs);
                String ts = oack.options().get(Packets.OPT_TSIZE);
                if (ts != null) {
                    try { tsize = Long.parseLong(ts.trim()); } catch (NumberFormatException ignored) {}
                }
                return new HandshakeResult(peer, negotiatedBlockSize, tsize, null);
            }
            if (pkt instanceof TftpPacket.Ack ack) {
                // PUT path: server accepted WRQ without options.
                if (ack.blockNumber() != 0) {
                    throw new TftpException(ErrorCode.ILLEGAL_OPERATION,
                            "Expected ACK 0, got " + ack.blockNumber());
                }
                return new HandshakeResult(peer, Packets.DEFAULT_BLOCK_SIZE, -1L, null);
            }
            if (pkt instanceof TftpPacket.Data data) {
                // GET path: server skipped option negotiation.
                return new HandshakeResult(peer, Packets.DEFAULT_BLOCK_SIZE, -1L, data);
            }
            // Unexpected — keep waiting.
        }
    }

    private Map<String, String> buildOptions(TftpClientOptions opts, long sourceSize, boolean isPut) {
        Map<String, String> map = new LinkedHashMap<>();
        if (opts.blockSize() != Packets.DEFAULT_BLOCK_SIZE) {
            map.put(Packets.OPT_BLOCKSIZE, Integer.toString(opts.blockSize()));
        }
        if (opts.timeoutSeconds() != 5) {
            map.put(Packets.OPT_TIMEOUT, Integer.toString(opts.timeoutSeconds()));
        }
        if (opts.negotiateTsize()) {
            // RFC 2349: client sends 0 on RRQ to ask the server, sends actual size on WRQ.
            map.put(Packets.OPT_TSIZE, Long.toString(isPut ? Math.max(sourceSize, 0) : 0L));
        }
        return map;
    }

    private void ackBlock(DatagramSocket socket, SocketAddress peer, int block) throws IOException {
        byte[] ack = Packets.encode(new TftpPacket.Ack(block));
        socket.send(new DatagramPacket(ack, ack.length, peer));
    }

    private void sendError(DatagramSocket socket, SocketAddress peer, ErrorCode code, String message) {
        try {
            byte[] err = Packets.encode(new TftpPacket.Error(code, message));
            socket.send(new DatagramPacket(err, err.length, peer));
        } catch (IOException e) {
            log.warn("Failed sending TFTP ERROR", e);
        }
    }

    private void writeData(OutputStream sink, TftpPacket.Data data) throws IOException {
        sink.write(data.payload(), data.offset(), data.length());
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

    private void checkCancelled(DatagramSocket socket, SocketAddress peer) {
        if (cancelled) {
            sendError(socket, peer, ErrorCode.NOT_DEFINED, "Cancelled by client");
            throw new TftpException(ErrorCode.NOT_DEFINED, "Transfer cancelled");
        }
    }

    /** @param firstData populated only on the GET no-options path. */
    private record HandshakeResult(SocketAddress peer, int blockSize, long tsize,
                                   TftpPacket.Data firstData) {
        @Override
        public String toString() {
            return "HandshakeResult(blockSize=" + blockSize + ", tsize=" + tsize + ")";
        }

        @SuppressWarnings("unused")
        private static String key(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
    }
}