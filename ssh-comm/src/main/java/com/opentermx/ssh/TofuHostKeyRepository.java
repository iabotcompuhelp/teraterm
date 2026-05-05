package com.opentermx.ssh;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.UserInfo;
import com.opentermx.common.connection.HostKeyDecision;
import com.opentermx.common.connection.HostKeyPrompt;
import com.opentermx.common.connection.HostKeyStatus;
import com.opentermx.common.connection.HostKeyVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Wraps JSch's known-hosts repository with a Trust-On-First-Use policy: when a
 * host is unknown or its key has changed, the wrapped {@link HostKeyVerifier} is
 * asked for a decision. With {@code StrictHostKeyChecking=yes} on the session,
 * returning anything but OK from {@link #check(String, byte[])} aborts the
 * handshake, so this class fully owns the host-key gate.
 *
 * <p>The delegate is typed as the public {@link HostKeyRepository} interface
 * because JSch's concrete {@code KnownHosts} class is package-private. All file
 * persistence is driven by the delegate's own behaviour (jsch syncs to the file
 * passed via {@code JSch.setKnownHosts}).
 */
public final class TofuHostKeyRepository implements HostKeyRepository {

    private static final Logger log = LoggerFactory.getLogger(TofuHostKeyRepository.class);

    private final HostKeyRepository delegate;
    private final HostKeyVerifier verifier;

    public TofuHostKeyRepository(HostKeyRepository delegate, HostKeyVerifier verifier) {
        this.delegate = delegate;
        this.verifier = verifier;
    }

    @Override
    public int check(String host, byte[] key) {
        int status = delegate.check(host, key);
        if (status == OK) {
            return OK;
        }

        HostKeyStatus tofuStatus = (status == CHANGED) ? HostKeyStatus.CHANGED : HostKeyStatus.NEW;
        String keyType = parseKeyType(key);
        String fingerprint = sha256Fingerprint(key);
        List<String> previous = (tofuStatus == HostKeyStatus.CHANGED) ? collectPreviousFingerprints(host) : List.of();

        HostKeyPrompt prompt = new HostKeyPrompt(
                stripPort(host), extractPort(host), keyType, fingerprint, tofuStatus, previous);

        HostKeyDecision decision;
        try {
            decision = verifier.verify(prompt);
        } catch (Throwable t) {
            log.warn("HostKeyVerifier falló; rechazando host key", t);
            return status;
        }

        switch (decision) {
            case ACCEPT_AND_SAVE -> {
                if (tofuStatus == HostKeyStatus.CHANGED) {
                    log.warn("Host key cambió para {}; persistencia requiere edición manual del known_hosts, " +
                            "se trata como ACCEPT_ONCE", host);
                    return OK;
                }
                try {
                    delegate.add(new HostKey(host, key), null);
                } catch (JSchException e) {
                    log.warn("No se pudo persistir host key para {}", host, e);
                }
                return OK;
            }
            case ACCEPT_ONCE -> {
                return OK;
            }
            case REJECT -> {
                return status;
            }
            default -> {
                return status;
            }
        }
    }

    private List<String> collectPreviousFingerprints(String host) {
        HostKey[] previous = delegate.getHostKey(stripPort(host), null);
        if (previous == null || previous.length == 0) return List.of();
        List<String> out = new ArrayList<>(previous.length);
        for (HostKey hk : previous) {
            String b64 = hk.getKey();
            if (b64 == null || b64.isEmpty()) continue;
            try {
                byte[] raw = Base64.getDecoder().decode(b64);
                out.add(sha256Fingerprint(raw));
            } catch (IllegalArgumentException e) {
                log.debug("Clave previa con base64 inválido para {}", host);
            }
        }
        return out;
    }

    @Override
    public void add(HostKey hostkey, UserInfo ui) {
        delegate.add(hostkey, ui);
    }

    @Override
    public void remove(String host, String type) {
        delegate.remove(host, type);
    }

    @Override
    public void remove(String host, String type, byte[] key) {
        delegate.remove(host, type, key);
    }

    @Override
    public String getKnownHostsRepositoryID() {
        return delegate.getKnownHostsRepositoryID();
    }

    @Override
    public HostKey[] getHostKey() {
        return delegate.getHostKey();
    }

    @Override
    public HostKey[] getHostKey(String host, String type) {
        return delegate.getHostKey(host, type);
    }

    static String sha256Fingerprint(byte[] key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String parseKeyType(byte[] key) {
        if (key == null || key.length < 4) return "ssh-unknown";
        ByteBuffer buf = ByteBuffer.wrap(key);
        int len = buf.getInt();
        if (len <= 0 || len > 64 || len > buf.remaining()) return "ssh-unknown";
        byte[] name = new byte[len];
        buf.get(name);
        String parsed = new String(name, StandardCharsets.US_ASCII);
        for (int i = 0; i < parsed.length(); i++) {
            char c = parsed.charAt(i);
            if (c < 0x20 || c > 0x7E) return "ssh-unknown";
        }
        return parsed;
    }

    private static String stripPort(String host) {
        if (host == null || host.isEmpty() || host.charAt(0) != '[') return host;
        int close = host.indexOf(']');
        return (close > 0) ? host.substring(1, close) : host;
    }

    private static int extractPort(String host) {
        if (host == null || host.isEmpty() || host.charAt(0) != '[') return 22;
        int close = host.indexOf(']');
        if (close < 0 || close + 2 >= host.length() || host.charAt(close + 1) != ':') return 22;
        try {
            return Integer.parseInt(host.substring(close + 2));
        } catch (NumberFormatException e) {
            return 22;
        }
    }
}