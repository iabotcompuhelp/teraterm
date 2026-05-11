package com.opentermx.macro;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.event.ConnectionEvent;
import com.opentermx.common.event.EventBus;
import com.opentermx.common.event.Subscription;
import com.opentermx.tftp.client.TftpClient;
import com.opentermx.tftp.client.TftpClientOptions;
import com.opentermx.tftp.server.TftpServer;
import com.opentermx.tftp.server.TftpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public final class MacroContext {

    private static final Logger log = LoggerFactory.getLogger(MacroContext.class);

    private final Connection connection;
    private final String sessionId;
    private final MacroUiBridge ui;
    private final MacroAiBridge ai;
    private final Consumer<MacroLogEntry> onLog;
    private final MacroBuffer buffer = new MacroBuffer();
    private final List<MacroLogEntry> entries = Collections.synchronizedList(new ArrayList<>());
    private final long startTime = System.currentTimeMillis();

    private Subscription subscription;
    private FileWriter fileLog;
    private volatile boolean cancelled = false;
    private TftpServer tftpServer;

    public MacroContext(Connection connection, String sessionId, MacroUiBridge ui, Consumer<MacroLogEntry> onLog) {
        this(connection, sessionId, ui, null, onLog);
    }

    public MacroContext(Connection connection, String sessionId, MacroUiBridge ui, MacroAiBridge ai, Consumer<MacroLogEntry> onLog) {
        this.connection = connection;
        this.sessionId = sessionId;
        this.ui = (ui != null) ? ui : new MacroUiBridge.NoOp();
        this.ai = (ai != null) ? ai : new MacroAiBridge.NoOp();
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
        TftpServer ts = tftpServer;
        if (ts != null) {
            try { ts.stop(); } catch (Exception ignored) {}
            tftpServer = null;
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

    public void tftpPut(String host, int port, String remoteFile, String localFile) throws IOException {
        Path local = Path.of(localFile);
        long size = Files.size(local);
        log("tftp_put " + localFile + " → " + host + ":" + port + "/" + remoteFile + " (" + size + " bytes)");
        TftpClient client = new TftpClient();
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(local))) {
            client.put(host, port, remoteFile, in, size, TftpClientOptions.defaults(), null);
        }
        log("tftp_put completed");
    }

    public void tftpGet(String host, int port, String remoteFile, String localFile) throws IOException {
        Path local = Path.of(localFile);
        log("tftp_get " + host + ":" + port + "/" + remoteFile + " → " + localFile);
        TftpClient client = new TftpClient();
        try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(local))) {
            client.get(host, port, remoteFile, out, TftpClientOptions.defaults(), null);
        }
        log("tftp_get completed");
    }

    public synchronized void tftpServerStart(int port, String rootDir, boolean allowGet, boolean allowPut)
            throws IOException {
        if (tftpServer != null && tftpServer.isRunning()) {
            throw new MacroException("TFTP server already running on port " + tftpServer.actualPort());
        }
        Path root = Path.of(rootDir);
        if (!Files.isDirectory(root)) {
            throw new MacroException("Root directory does not exist: " + rootDir);
        }
        TftpServer server = new TftpServer(new TftpServerConfig(port, root, allowGet, allowPut, 5, 5));
        server.addListener(event -> log("tftp_server: " + event.kind() + " " + event.message()));
        server.start();
        tftpServer = server;
        log("tftp_server_start port=" + server.actualPort() + " root=" + root);
    }

    /**
     * Pregunta a la IA configurada y devuelve la respuesta en texto plano. Pensado para
     * que un macro pueda condicionar su flujo según la respuesta (e.g. parseando el texto).
     * Bloquea hasta recibir respuesta.
     */
    public String aiAsk(String prompt) throws Exception {
        log("ai_ask: " + truncate(prompt, 200));
        String response = ai.ask(prompt, sessionId);
        log("ai_ask response (" + response.length() + " chars)");
        return response;
    }

    /**
     * Genera comandos con IA y los ejecuta tras aprobación del operador. En entornos
     * sin operador (REST) lanza {@link UnsupportedOperationException}. Devuelve un
     * {@link AiExecuteResult} con contadores y outcome para que el macro decida cómo
     * seguir según el resultado.
     */
    public AiExecuteResult aiExecute(String prompt) throws Exception {
        log("ai_execute: " + truncate(prompt, 200));
        AiExecuteResult result = ai.execute(prompt, sessionId);
        log("ai_execute outcome=" + result.outcome()
                + " executed=" + result.executedCount() + " failed=" + result.failedCount());
        return result;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public synchronized void tftpServerStop() {
        TftpServer server = tftpServer;
        if (server == null) {
            log("tftp_server_stop: not running");
            return;
        }
        server.stop();
        tftpServer = null;
        log("tftp_server_stop done");
    }

    public List<MacroLogEntry> getLogEntries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }
}