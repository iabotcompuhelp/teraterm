package com.opentermx.tftp.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;

/**
 * Appends one CSV row per {@link TftpServerEvent} to a file. The header is written only when
 * the file is created so multiple sessions can append safely. Closing flushes and releases
 * the underlying writer; the listener can be attached to multiple servers if desired.
 */
public final class TftpCsvLogger implements TftpServerListener, AutoCloseable {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final PrintWriter writer;

    public TftpCsvLogger(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        boolean fresh = !Files.exists(file);
        writer = new PrintWriter(Files.newBufferedWriter(
                file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND));
        if (fresh) {
            writer.println("timestamp,kind,peer,file,bytes,message");
            writer.flush();
        }
    }

    @Override
    public synchronized void onEvent(TftpServerEvent event) {
        writer.println(
                ISO.format(event.timestamp()) + ","
                        + event.kind() + ","
                        + quote(event.peer() == null ? "" : event.peer().toString()) + ","
                        + quote(event.file()) + ","
                        + event.bytes() + ","
                        + quote(event.message()));
        writer.flush();
    }

    private static String quote(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    @Override
    public synchronized void close() {
        writer.flush();
        writer.close();
    }
}