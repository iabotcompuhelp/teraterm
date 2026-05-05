package com.opentermx.serial;

public final class CircularByteBuffer {

    private final byte[] buf;
    private int tail;
    private int size;

    public CircularByteBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.buf = new byte[capacity];
    }

    public synchronized void write(byte[] data, int offset, int length) {
        if (length <= 0) return;
        if (length >= buf.length) {
            System.arraycopy(data, offset + length - buf.length, buf, 0, buf.length);
            tail = 0;
            size = buf.length;
            return;
        }
        int firstChunk = Math.min(length, buf.length - tail);
        System.arraycopy(data, offset, buf, tail, firstChunk);
        int remaining = length - firstChunk;
        if (remaining > 0) {
            System.arraycopy(data, offset + firstChunk, buf, 0, remaining);
        }
        tail = (tail + length) % buf.length;
        size = Math.min(size + length, buf.length);
    }

    public synchronized byte[] snapshot() {
        byte[] out = new byte[size];
        int start = (tail - size + buf.length) % buf.length;
        int firstChunk = Math.min(size, buf.length - start);
        System.arraycopy(buf, start, out, 0, firstChunk);
        if (size > firstChunk) {
            System.arraycopy(buf, 0, out, firstChunk, size - firstChunk);
        }
        return out;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return buf.length;
    }

    public synchronized void clear() {
        tail = 0;
        size = 0;
    }
}