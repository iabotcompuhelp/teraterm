package com.opentermx.tftp.server;

import com.opentermx.tftp.common.ErrorCode;
import com.opentermx.tftp.common.Packets;
import com.opentermx.tftp.common.TftpException;
import com.opentermx.tftp.common.TftpPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class TftpServer {

    private static final Logger log = LoggerFactory.getLogger(TftpServer.class);

    private final TftpServerConfig config;
    private final CopyOnWriteArrayList<TftpServerListener> listeners = new CopyOnWriteArrayList<>();

    private DatagramSocket listenSocket;
    private Thread listenerThread;
    private ExecutorService workerPool;
    private volatile boolean running;
    private int actualPort = -1;

    public TftpServer(TftpServerConfig config) {
        this.config = config;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        try {
            listenSocket = new DatagramSocket(config.port());
        } catch (BindException be) {
            throw new IOException("Cannot bind UDP " + config.port()
                    + " — try a port > 1024 (e.g. 6969) or run with elevated privileges", be);
        }
        actualPort = listenSocket.getLocalPort();
        running = true;
        AtomicLong counter = new AtomicLong();
        workerPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tftp-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        listenerThread = new Thread(this::listenLoop, "tftp-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
        emit(TftpServerEvent.started(actualPort));
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (listenSocket != null) listenSocket.close();
        if (workerPool != null) {
            workerPool.shutdownNow();
            try {
                workerPool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        emit(TftpServerEvent.stopped());
    }

    public boolean isRunning() {
        return running;
    }

    public int actualPort() {
        return actualPort;
    }

    public TftpServerConfig config() {
        return config;
    }

    public void addListener(TftpServerListener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(TftpServerListener listener) {
        listeners.remove(listener);
    }

    void emit(TftpServerEvent event) {
        for (TftpServerListener l : listeners) {
            try { l.onEvent(event); } catch (RuntimeException re) {
                log.warn("Listener threw", re);
            }
        }
    }

    private void listenLoop() {
        // Maximum reasonable size for the initial RRQ/WRQ.
        byte[] buffer = new byte[1024];
        while (running) {
            DatagramPacket dgram = new DatagramPacket(buffer, buffer.length);
            try {
                listenSocket.receive(dgram);
            } catch (IOException e) {
                if (running) log.warn("Listener receive failed", e);
                continue;
            }
            byte[] copy = new byte[dgram.getLength()];
            System.arraycopy(dgram.getData(), 0, copy, 0, copy.length);
            InetSocketAddress peer = (InetSocketAddress) dgram.getSocketAddress();
            workerPool.submit(() -> handleInitialPacket(peer, copy));
        }
    }

    private void handleInitialPacket(InetSocketAddress peer, byte[] payload) {
        TftpPacket pkt;
        try {
            pkt = Packets.decode(payload, payload.length);
        } catch (TftpException ex) {
            log.warn("Bad initial packet from {}: {}", peer, ex.getMessage());
            return;
        }
        try (TftpSession session = new TftpSession(this, config, peer)) {
            switch (pkt) {
                case TftpPacket.Rrq rrq -> {
                    if (!config.allowGet()) {
                        session.replyError(ErrorCode.ACCESS_VIOLATION, "GET not allowed");
                        emit(TftpServerEvent.transferFailed(peer, rrq.filename(), "GET denied"));
                        return;
                    }
                    session.handleRead(rrq);
                }
                case TftpPacket.Wrq wrq -> {
                    if (!config.allowPut()) {
                        session.replyError(ErrorCode.ACCESS_VIOLATION, "PUT not allowed");
                        emit(TftpServerEvent.transferFailed(peer, wrq.filename(), "PUT denied"));
                        return;
                    }
                    session.handleWrite(wrq);
                }
                default -> {
                    session.replyError(ErrorCode.ILLEGAL_OPERATION,
                            "Expected RRQ or WRQ, got " + pkt.opcode());
                }
            }
        } catch (Exception ex) {
            log.warn("Worker failed for {}", peer, ex);
            emit(TftpServerEvent.transferFailed(peer, "?", ex.getMessage()));
        }
    }
}