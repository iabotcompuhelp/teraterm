package com.opentermx.macro;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.event.ConnectionEvent;
import com.opentermx.common.event.EventBus;
import com.opentermx.common.event.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public final class MacroContext {

    private static final Logger log = LoggerFactory.getLogger(MacroContext.class);

    private final Connection connection;
    private final String sessionId;
    private final MacroUiBridge ui;
    private final Consumer<MacroLogEntry> onLog;
    private final MacroBuffer buffer = new MacroBuffer();
    private final List<MacroLogEntry> entries = Collections.synchronizedList(new ArrayList<>());
    private final long startTime = System.currentTimeMillis();

    private Subscription subscription;
    private FileWriter fileLog;
    private volatile boolean cancelled = false;

    public MacroContext(Connection connection, String sessionId, MacroUiBridge ui, Consumer<MacroLogEntry> onLog) {
        this.connection = connection;
        this.sessionId = sessionId;
        this.ui = (ui != null) ? ui : new MacroUiBridge.NoOp();
        this.onLog = (onLog != null) ? onLog : entry -> {};
    }

    public void start() {
        if (sessionId == null) return;
        subscription = EventBus.subscribe(event -> {
            if (event instanceof ConnectionEvent.DataReceived dr
                    && sessionId.equals(dr.getSessionId())) {
                String text = new String(dr.getData(), 0, dr.getLength(), StandardCharsets.UTF_8);
                buffer.append(text);
                FileWriter fl = fileLog;
                if (fl != null) {
                    try {
                        fl.write(text);
                        fl.flush();
                    } catch (IOException e) {
                        log.warn("filelog write failed", e);
                    }
                }
            }
        });
    }

    public void stop() {
        Subscription s = subscription;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        FileWriter fl = fileLog;
        if (fl != null) {
            try { fl.close(); } catch (IOException ignored) {}
        }
    }

    public void cancel() {
        cancelled = true;
    }

    public void send(String text) throws Exception {
        if (connection == null) throw new MacroException("No hay conexión activa");
        connection.send(text.getBytes(StandardCharsets.UTF_8));
        log("send: " + text.replace("\r", "\\r").replace("\n", "\\n"));
    }

    public void sendln(String text) throws Exception {
        send(text + "\r");
    }

    public boolean waitfor(String pattern, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
        log("waitfor: '" + pattern + "' timeout=" + timeoutMillis + "ms");
        while (!cancelled && System.currentTimeMillis() < deadline) {
            int idx = buffer.indexOf(pattern);
            if (idx >= 0) {
                buffer.consume(idx + pattern.length());
                log("waitfor matched");
                return true;
            }
            Thread.sleep(50);
        }
        log(cancelled ? "waitfor cancelled" : "waitfor timed out");
        return false;
    }

    public void pause(long millis) throws InterruptedException {
        log("pause " + millis + "ms");
        Thread.sleep(millis);
    }

    public void messagebox(String message) {
        log("messagebox: " + message);
        ui.showMessage(message);
    }

    public String inputbox(String prompt, String defaultValue) {
        log("inputbox: " + prompt);
        String r = ui.prompt(prompt, defaultValue);
        return r != null ? r : "";
    }

    public void filelog(String path) throws IOException {
        log("filelog: " + path);
        FileWriter fl = fileLog;
        if (fl != null) {
            try { fl.close(); } catch (IOException ignored) {}
        }
        fileLog = new FileWriter(path, true);
    }

    public String getClipboard() {
        return ui.getClipboard();
    }

    public void setClipboard(String text) {
        ui.setClipboard(text);
    }

    public void log(String message) {
        MacroLogEntry entry = new MacroLogEntry(System.currentTimeMillis() - startTime, message);
        entries.add(entry);
        try { onLog.accept(entry); } catch (Throwable t) { log.warn("log consumer threw", t); }
    }

    public List<MacroLogEntry> getLogEntries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }
}