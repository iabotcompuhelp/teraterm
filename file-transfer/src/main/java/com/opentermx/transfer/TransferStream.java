package com.opentermx.transfer;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.DataHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public abstract class TransferStream implements AutoCloseable {

    private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private byte[] head = null;
    private int headPos = 0;

    protected final void enqueue(byte[] data, int length) {
        if (length <= 0) return;
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        incoming.offer(copy);
    }

    public final int pollByte(long timeoutMillis) throws InterruptedException {
        if (head == null || headPos >= head.length) {
            head = incoming.poll(Math.max(0, timeoutMillis), TimeUnit.MILLISECONDS);
            headPos = 0;
            if (head == null) return -1;
        }
        return head[headPos++] & 0xFF;
    }

    public final byte[] readBytes(int length, long timeoutMillis) throws Exception {
        byte[] out = new byte[length];
        long deadline = System.currentTimeMillis() + timeoutMillis;
        for (int i = 0; i < length; i++) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) throw new TransferException("Timeout leyendo " + length + " bytes");
            int b = pollByte(remaining);
            if (b < 0) throw new TransferException("Timeout leyendo " + length + " bytes");
            out[i] = (byte) b;
        }
        return out;
    }

    public abstract void write(byte[] data, int offset, int length) throws Exception;

    public final void writeByte(byte b) throws Exception {
        write(new byte[]{b}, 0, 1);
    }

    public final void writeAll(byte[] data) throws Exception {
        write(data, 0, data.length);
    }

    @Override
    public abstract void close();

    public static TransferStream forConnection(Connection connection) {
        return new ConnectionStream(connection);
    }

    public static TransferStream forStreams(InputStream in, OutputStream out) {
        return new PipedStream(in, out);
    }

    private static final class ConnectionStream extends TransferStream {
        private final Connection conn;
        private final DataHandler previous;

        ConnectionStream(Connection conn) {
            this.conn = conn;
            this.previous = conn.getDataHandler();
            conn.setDataHandler((data, length) -> enqueue(data, length));
        }

        @Override
        public void write(byte[] data, int offset, int length) throws Exception {
            conn.send(data, offset, length);
        }

        @Override
        public void close() {
            conn.setDataHandler(previous);
        }
    }

    private static final class PipedStream extends TransferStream {
        private final OutputStream out;
        private final Thread reader;

        PipedStream(InputStream in, OutputStream out) {
            this.out = out;
            this.reader = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (n > 0) enqueue(buf, n);
                    }
                } catch (Exception ignored) {
                    // pipe closed; reader exits
                }
            }, "transfer-stream-reader");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        @Override
        public void write(byte[] data, int offset, int length) throws Exception {
            out.write(data, offset, length);
            out.flush();
        }

        @Override
        public void close() {
            try { out.close(); } catch (IOException ignored) {}
            reader.interrupt();
        }
    }
}