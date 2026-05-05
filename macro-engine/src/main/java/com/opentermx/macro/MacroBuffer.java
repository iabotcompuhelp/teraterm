package com.opentermx.macro;

public final class MacroBuffer {

    private static final int MAX_SIZE = 1_000_000;

    private final StringBuilder buffer = new StringBuilder();

    public synchronized void append(String text) {
        buffer.append(text);
        if (buffer.length() > MAX_SIZE) {
            buffer.delete(0, buffer.length() - MAX_SIZE);
        }
    }

    public synchronized int indexOf(String pattern) {
        return buffer.indexOf(pattern);
    }

    public synchronized void consume(int upTo) {
        if (upTo > 0) {
            buffer.delete(0, Math.min(upTo, buffer.length()));
        }
    }

    public synchronized String snapshot() {
        return buffer.toString();
    }

    public synchronized void clear() {
        buffer.setLength(0);
    }

    public synchronized int size() {
        return buffer.length();
    }
}