package com.opentermx.telnet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Resolves a hostname under the user's preferred DNS family (AUTO/IPV4/IPV6). For AUTO we delegate
 * to {@link InetAddress#getByName(String)}, which honours {@code java.net.preferIPv4Stack}. For
 * the other modes we enumerate all addresses and pick the first matching family, falling back to
 * the JDK's default if nothing matches (so a misconfigured network produces a connect-time error
 * with the original host instead of a confusing UnknownHostException).
 */
public final class HostResolver {

    private static final Logger log = LoggerFactory.getLogger(HostResolver.class);

    private HostResolver() {}

    public static InetAddress resolve(String host, String dnsMode) throws UnknownHostException {
        String mode = dnsMode == null ? "AUTO" : dnsMode.toUpperCase(Locale.ROOT);
        if ("IPV4".equals(mode) || "IPV6".equals(mode)) {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress a : all) {
                if (mode.equals("IPV4") && a instanceof Inet4Address) return a;
                if (mode.equals("IPV6") && a instanceof Inet6Address) return a;
            }
            log.info("DNS mode {} requested for {} but no matching address found; using AUTO", mode, host);
        }
        return InetAddress.getByName(host);
    }
}