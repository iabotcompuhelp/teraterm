package com.opentermx.app.ui.web

import javafx.concurrent.Worker
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.web.WebView
import org.slf4j.LoggerFactory

/**
 * Sesión WEB embebida en un tab de OpenTermX. Layout:
 *  - Barra superior: back, forward, reload, stop, campo URL editable, Go.
 *  - Centro: `WebView` (motor WebKit que viene con JavaFX 21.0.7).
 *  - Barra inferior: status (estado de carga + URL actual).
 *
 * Si [autofillUsername]/[autofillPassword] están seteados y [autofill] es `true`, al
 * terminar de cargar la página se inyecta un fragmento JS que busca el primer
 * `input[type=password]` y le pone el password; el input de texto/email inmediatamente
 * anterior al password recibe el usuario. Funciona en login forms simples (típicas de
 * admin UIs de Cisco/Aruba/MikroTik/HPE/FortiNet). Si la página tiene un flujo más
 * complejo (OAuth, SPA con render diferido) puede fallar — el usuario tipea manualmente.
 *
 * Para no leakear credenciales en navegaciones inesperadas, la inyección ocurre solo si
 * el hostname final de la página matchea (case-insensitive) el hostname del URL original.
 */
class WebSessionView(
    initialUrl: String,
    private val autofillUsername: String = "",
    private val autofillPassword: String = "",
    private val autofill: Boolean = true,
) : BorderPane() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val webView = WebView()
    private val engine = webView.engine
    private val urlField = TextField(initialUrl)
    private val statusLabel = Label("Listo")
    private val originalHost: String = runCatching { java.net.URI(initialUrl).host?.lowercase() }.getOrNull().orEmpty()
    private var autofillDone = false

    init {
        styleClass += "web-session-view"

        val backBtn = Button("◀").apply { setOnAction { engine.executeScript("history.back()") } }
        val fwdBtn = Button("▶").apply { setOnAction { engine.executeScript("history.forward()") } }
        val reloadBtn = Button("⟳").apply { setOnAction { engine.reload() } }
        val stopBtn = Button("✕").apply { setOnAction { engine.loadWorker.cancel() } }
        val goBtn = Button("Ir").apply { setOnAction { load(urlField.text) } }
        urlField.setOnAction { load(urlField.text) }
        HBox.setHgrow(urlField, Priority.ALWAYS)

        val topBar = HBox(6.0, backBtn, fwdBtn, reloadBtn, stopBtn, urlField, goBtn).apply {
            padding = Insets(6.0)
        }
        val bottomBar = HBox(statusLabel).apply { padding = Insets(4.0, 8.0, 4.0, 8.0) }

        top = topBar
        center = webView
        bottom = bottomBar

        engine.locationProperty().addListener { _, _, newLoc -> urlField.text = newLoc }
        engine.loadWorker.stateProperty().addListener { _, _, state ->
            statusLabel.text = when (state) {
                Worker.State.SCHEDULED -> "Programando…"
                Worker.State.RUNNING -> "Cargando…"
                Worker.State.SUCCEEDED -> "Listo"
                Worker.State.FAILED -> "Fallo: ${engine.loadWorker.exception?.message ?: "error desconocido"}"
                Worker.State.CANCELLED -> "Cancelado"
                else -> state.name
            }
            if (state == Worker.State.SUCCEEDED && autofill && !autofillDone && shouldAutofillFor(engine.location)) {
                // El primer intento ocurre inmediatamente; si la página tiene un form
                // renderizado por JS post-load, retry-eamos varias veces con delay.
                tryAutofillWithRetries()
            }
        }
        engine.loadWorker.exceptionProperty().addListener { _, _, e ->
            if (e != null) log.warn("WebView load error: {}", e.message)
        }

        load(initialUrl)
    }

    fun load(url: String) {
        val normalized = if (!url.contains("://")) "http://$url" else url
        engine.load(normalized)
    }

    /**
     * Solo permite el auto-fill si el hostname de la página actual matchea el del URL inicial,
     * para no inyectar credenciales en un redirect inesperado (ej. captive portal, CDN).
     */
    private fun shouldAutofillFor(currentLocation: String?): Boolean {
        if (currentLocation.isNullOrBlank() || originalHost.isBlank()) return false
        val currentHost = runCatching { java.net.URI(currentLocation).host?.lowercase() }.getOrNull().orEmpty()
        return currentHost == originalHost
    }

    /**
     * Reintenta el inject hasta [maxAttempts] veces con [delayMs] de delay entre intentos.
     * Necesario para admin UIs que renderizan el form de login post-load via JavaScript
     * (frameset HP/Aruba antiguos, paneles SPA-light).
     */
    private fun tryAutofillWithRetries(maxAttempts: Int = 6, delayMs: Double = 500.0) {
        if (autofillDone) return
        var attempt = 0
        // Primer intento inmediato.
        if (injectAutofill()) {
            autofillDone = true
            return
        }
        attempt = 1
        val timeline = javafx.animation.Timeline()
        val keyframe = javafx.animation.KeyFrame(
            javafx.util.Duration.millis(delayMs),
            javafx.event.EventHandler<javafx.event.ActionEvent> {
                attempt++
                val ok = injectAutofill()
                if (ok || attempt >= maxAttempts) {
                    if (ok) autofillDone = true
                    else log.info("Auto-fill agotó {} intentos en {}", maxAttempts, engine.location)
                    timeline.stop()
                }
            },
        )
        timeline.keyFrames.add(keyframe)
        timeline.cycleCount = maxAttempts - 1
        timeline.play()
    }

    private fun injectAutofill(): Boolean {
        val user = jsEscape(autofillUsername)
        val pass = jsEscape(autofillPassword)
        // Heurística:
        //  1. Recolectar el `document` principal + cada `iframe`/`frame` accesible (same-origin).
        //  2. En cada documento, buscar `input[type=password]` visible. Fallback: input cuyo
        //     name o id contiene "password" (UIs viejas usan type=text con masking).
        //  3. Como user, tomar el input text/email/tel/username inmediatamente anterior.
        //  4. Setear via el setter nativo del prototipo (algunas SPAs lo overridean) y
        //     dispatch input+change events para que frameworks detecten el cambio.
        val script = """
            (function() {
                try {
                    function findInDoc(doc) {
                        var pw = null;
                        var pws = doc.querySelectorAll('input[type="password"]');
                        for (var i = 0; i < pws.length; i++) {
                            if (pws[i].offsetParent !== null) { pw = pws[i]; break; }
                        }
                        if (!pw && pws.length > 0) pw = pws[0];
                        if (!pw) {
                            var named = doc.querySelectorAll(
                                'input[name="password" i], input[id="password" i], ' +
                                'input[name*="passwd" i], input[id*="passwd" i]'
                            );
                            if (named.length > 0) pw = named[0];
                        }
                        if (!pw) return null;
                        var userInput = null;
                        var all = Array.prototype.slice.call(doc.querySelectorAll('input'));
                        var idx = all.indexOf(pw);
                        for (var j = idx - 1; j >= 0; j--) {
                            var t = (all[j].type || '').toLowerCase();
                            if (t === 'text' || t === 'email' || t === 'tel' || t === '' || t === 'username') {
                                userInput = all[j]; break;
                            }
                        }
                        // Fallback: input cuyo name/id contiene "user", "login", "name"
                        if (!userInput) {
                            var named = doc.querySelectorAll(
                                'input[name*="user" i], input[id*="user" i], ' +
                                'input[name*="login" i], input[id*="login" i]'
                            );
                            if (named.length > 0) userInput = named[0];
                        }
                        return { pw: pw, user: userInput };
                    }
                    function setVal(el, v) {
                        try {
                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                            setter.call(el, v);
                        } catch (e) { el.value = v; }
                        el.dispatchEvent(new Event('input', {bubbles: true}));
                        el.dispatchEvent(new Event('change', {bubbles: true}));
                    }
                    var docs = [document];
                    var iframes = document.querySelectorAll('iframe, frame');
                    for (var k = 0; k < iframes.length; k++) {
                        try { if (iframes[k].contentDocument) docs.push(iframes[k].contentDocument); }
                        catch (e) { /* cross-origin: skip */ }
                    }
                    for (var d = 0; d < docs.length; d++) {
                        var found = findInDoc(docs[d]);
                        if (found) {
                            if (found.user && '$user' !== '') setVal(found.user, '$user');
                            if ('$pass' !== '') setVal(found.pw, '$pass');
                            return true;
                        }
                    }
                    return false;
                } catch (e) { return false; }
            })();
        """.trimIndent()
        return try {
            engine.executeScript(script) == true
        } catch (t: Throwable) {
            log.warn("Auto-fill JS falló en {}: {}", engine.location, t.message)
            false
        }
    }

    /** Escapa para inserción dentro de comillas simples en JS. */
    private fun jsEscape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("</", "<\\/")

    fun dispose() {
        runCatching { engine.load(null) }
    }
}
