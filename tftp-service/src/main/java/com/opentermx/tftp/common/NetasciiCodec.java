package com.opentermx.tftp.common;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Implements the netascii line-ending convention from RFC 764: every line ends with CR LF on the
 * wire, and bare CR/LF in the payload must be escaped (CR → CR NUL, LF → CR LF).
 *
 * <p>Stateful because a CR may straddle a buffer boundary.
 */
public final class NetasciiCodec {

    private boolean lastWasCr;

    /** Encode bytes from a local file into netascii on the wire. */
    public int encode(byte[] in, int offset, int length, byte[] out) {
        int o = 0;
        String sep = System.lineSeparator();
        // Match the host's line separator. On Windows we already have CR LF; on Unix we have LF
        // and need to inject the CR.
        boolean platformIsLf = sep.equals("\n");
        for (int i = 0; i < length; i++) {
            byte b = in[offset + i];
            if (platformIsLf && b == '\n') {
                out[o++] = '\r';
                out[o++] = '\n';
            } else if (b == '\r') {
                out[o++] = '\r';
                out[o++] = 0;
            } else {
                out[o++] = b;
            }
        }
        return o;
    }

    /** Decode netascii bytes received from the wire and write them to {@code sink}. */
    public void decode(byte[] in, int offset, int length, OutputStream sink) throws IOException {
        String sep = System.lineSeparator();
        boolean platformIsLf = sep.equals("\n");
        for (int i = 0; i < length; i++) {
            byte b = in[offset + i];
            if (lastWasCr) {
                lastWasCr = false;
                if (b == '\n') {
                    sink.write(platformIsLf ? '\n' : '\r');
                    if (!platformIsLf) sink.write('\n');
                } else if (b == 0) {
                    sink.write('\r');
                } else {
                    sink.write('\r');
                    sink.write(b);
                }
            } else if (b == '\r') {
                lastWasCr = true;
            } else {
                sink.write(b);
            }
        }
    }
}