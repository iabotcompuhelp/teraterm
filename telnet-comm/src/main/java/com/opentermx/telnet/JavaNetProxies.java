package com.opentermx.telnet;

import com.opentermx.common.connection.ProxyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * Maps {@link ProxyConfig} to {@link java.net.Proxy} for the plain socket transports
 * (Apache Commons Net telnet and raw TCP). Returns null when the config is disabled.
 *
 * <p>Java's {@code Proxy.Type.SOCKS} negotiates SOCKS5 (and falls back to SOCKS4 if the server
 * speaks v4 only), so we map both SOCKS4 and SOCKS5 to the same type. HTTP CONNECT auth
 * isn't supported by the JDK's plain socket stack — credentials in that case are ignored
 * and a warning is logged.
 */
public final class JavaNetProxies {

    private static final Logger log = LoggerFactory.getLogger(JavaNetProxies.class);

    private JavaNetProxies() {}

    public static Proxy create(ProxyConfig pc) {
        if (pc == null || !pc.isEnabled()) return null;
        InetSocketAddress addr = new InetSocketAddress(pc.getHost(), pc.getPort());
        return switch (pc.getType()) {
            case HTTP -> {
                if (!pc.getUsername().isEmpty()) {
                    log.warn("HTTP proxy auth (user={}) requires Java Authenticator wiring; "
                            + "credentials ignored on this transport", pc.getUsername());
                }
                yield new Proxy(Proxy.Type.HTTP, addr);
            }
            case SOCKS4, SOCKS5 -> new Proxy(Proxy.Type.SOCKS, addr);
            default -> null;
        };
    }
}