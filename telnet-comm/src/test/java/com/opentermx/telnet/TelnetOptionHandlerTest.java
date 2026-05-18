package com.opentermx.telnet;

import org.apache.commons.net.telnet.EchoOptionHandler;
import org.apache.commons.net.telnet.SuppressGAOptionHandler;
import org.apache.commons.net.telnet.TelnetOptionHandler;
import org.apache.commons.net.telnet.TerminalTypeOptionHandler;
import org.apache.commons.net.telnet.WindowSizeOptionHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresión de Phase 2.5 T3. La hipótesis del spec era que el {@code EchoOptionHandler}
 * estaba con los flags invertidos respecto a lo que necesita un CLI de equipo de red.
 * Validamos contra un 3Com Baseline 2928 (lab @ 192.168.100.249): con
 * {@code (true,false,true,false)} cada char tipeado se mostraba dos veces
 * (Username:aaddmmiinn, password en plaintext, &lt;HOME-NET&gt;ssuumm). Tras invertir a
 * {@code (false,true,false,true)} sólo el server hace echo. Este test fija los flags
 * para evitar regresión silenciosa.
 */
class TelnetOptionHandlerTest {

    @Test
    void echoOptionHandlerInverted_serverDoesTheEcho() {
        TelnetOptionHandler[] handlers = TelnetConnection.buildOptionHandlers("xterm-256color", 80, 24);
        EchoOptionHandler echo = null;
        for (TelnetOptionHandler h : handlers) {
            if (h instanceof EchoOptionHandler) {
                echo = (EchoOptionHandler) h;
                break;
            }
        }
        assertTrue(echo != null, "EchoOptionHandler debe estar en la lista de handlers");
        assertFalse(echo.getInitLocal(), "initLocal=false: no propongo eco local");
        assertTrue(echo.getInitRemote(), "initRemote=true: pido al server DO ECHO");
        assertFalse(echo.getAcceptLocal(), "acceptLocal=false: rechazo eco local pedido por server");
        assertTrue(echo.getAcceptRemote(), "acceptRemote=true: acepto WILL ECHO del server");
    }

    @Test
    void buildOptionHandlersIncludesAllFour() {
        TelnetOptionHandler[] handlers = TelnetConnection.buildOptionHandlers("xterm-256color", 80, 24);
        assertEquals(4, handlers.length);
        assertInstanceOf(TerminalTypeOptionHandler.class, handlers[0]);
        assertInstanceOf(EchoOptionHandler.class, handlers[1]);
        assertInstanceOf(SuppressGAOptionHandler.class, handlers[2]);
        assertInstanceOf(WindowSizeOptionHandler.class, handlers[3]);
    }
}
