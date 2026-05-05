package com.opentermx.ssh;

import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KnownHosts;
import com.opentermx.common.connection.HostKeyDecision;
import com.opentermx.common.connection.HostKeyPrompt;
import com.opentermx.common.connection.HostKeyStatus;
import com.opentermx.common.connection.HostKeyVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TofuHostKeyRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void newHostAcceptAndSavePersists() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TofuHostKeyRepository repo = newRepository(prompt -> {
            calls.incrementAndGet();
            return HostKeyDecision.ACCEPT_AND_SAVE;
        });
        byte[] key = sshKey("ssh-ed25519", filled(32, (byte) 0x11));

        assertEquals(HostKeyRepository.OK, repo.check("example.com", key));
        assertEquals(1, calls.get());
        // Second check finds the key in the delegate; verifier is not consulted again.
        assertEquals(HostKeyRepository.OK, repo.check("example.com", key));
        assertEquals(1, calls.get());
    }

    @Test
    void newHostAcceptOnceDoesNotPersist() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TofuHostKeyRepository repo = newRepository(prompt -> {
            calls.incrementAndGet();
            return HostKeyDecision.ACCEPT_ONCE;
        });
        byte[] key = sshKey("ssh-ed25519", filled(32, (byte) 0x22));

        assertEquals(HostKeyRepository.OK, repo.check("example.com", key));
        // Same repo, second check: still asks (delegate empty).
        assertEquals(HostKeyRepository.OK, repo.check("example.com", key));
        assertEquals(2, calls.get());
    }

    @Test
    void newHostRejectReturnsNotIncluded() throws Exception {
        TofuHostKeyRepository repo = newRepository(prompt -> HostKeyDecision.REJECT);
        byte[] key = sshKey("ssh-ed25519", filled(32, (byte) 0x33));

        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("example.com", key));
    }

    @Test
    void changedKeyRejectReturnsChanged() throws Exception {
        // First, register key1 via ACCEPT_AND_SAVE.
        KnownHosts kh = freshKnownHosts();
        TofuHostKeyRepository acceptRepo = wrap(kh, prompt -> HostKeyDecision.ACCEPT_AND_SAVE);
        byte[] key1 = sshKey("ssh-ed25519", filled(32, (byte) 0x44));
        assertEquals(HostKeyRepository.OK, acceptRepo.check("example.com", key1));

        // Then check a different key under REJECT and inspect the prompt.
        List<HostKeyPrompt> prompts = new ArrayList<>();
        TofuHostKeyRepository rejectRepo = wrap(kh, prompt -> {
            prompts.add(prompt);
            return HostKeyDecision.REJECT;
        });
        byte[] key2 = sshKey("ssh-ed25519", filled(32, (byte) 0x55));
        assertEquals(HostKeyRepository.CHANGED, rejectRepo.check("example.com", key2));
        assertEquals(1, prompts.size());
        HostKeyPrompt p = prompts.get(0);
        assertEquals(HostKeyStatus.CHANGED, p.getStatus());
        assertEquals("ssh-ed25519", p.getKeyType());
        assertTrue(p.getFingerprintSha256().startsWith("SHA256:"));
        assertEquals(1, p.getPreviousFingerprints().size());
        assertTrue(p.getPreviousFingerprints().get(0).startsWith("SHA256:"));
    }

    @Test
    void verifierExceptionRejects() throws Exception {
        TofuHostKeyRepository repo = newRepository(prompt -> {
            throw new RuntimeException("boom");
        });
        byte[] key = sshKey("ssh-ed25519", filled(32, (byte) 0x66));

        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("example.com", key));
    }

    @Test
    void parseKeyTypeRecognizesEd25519() {
        byte[] key = sshKey("ssh-ed25519", filled(32, (byte) 0x77));
        assertEquals("ssh-ed25519", TofuHostKeyRepository.parseKeyType(key));
    }

    @Test
    void parseKeyTypeReturnsUnknownOnMalformed() {
        assertEquals("ssh-unknown", TofuHostKeyRepository.parseKeyType(new byte[]{0, 0, 0}));
        assertEquals("ssh-unknown", TofuHostKeyRepository.parseKeyType(new byte[]{0, 0, 0, 0}));
        assertEquals("ssh-unknown", TofuHostKeyRepository.parseKeyType(new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
    }

    @Test
    void sha256FingerprintFormat() {
        String fp = TofuHostKeyRepository.sha256Fingerprint(new byte[]{1, 2, 3});
        assertNotNull(fp);
        assertTrue(fp.startsWith("SHA256:"));
        // Base64 of SHA-256 (32 bytes) without padding = 43 chars.
        assertEquals(7 + 43, fp.length());
    }

    // --- helpers ---

    private TofuHostKeyRepository newRepository(HostKeyVerifier verifier) throws Exception {
        return wrap(freshKnownHosts(), verifier);
    }

    private TofuHostKeyRepository wrap(KnownHosts kh, HostKeyVerifier verifier) {
        return new TofuHostKeyRepository(kh, verifier);
    }

    private KnownHosts freshKnownHosts() throws IOException, com.jcraft.jsch.JSchException {
        Path file = Files.createTempFile(tempDir, "known_hosts", "");
        JSch jsch = new JSch();
        jsch.setKnownHosts(file.toAbsolutePath().toString());
        return (KnownHosts) jsch.getHostKeyRepository();
    }

    private static byte[] sshKey(String type, byte[] pubKey) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(4 + typeBytes.length + 4 + pubKey.length);
        buf.putInt(typeBytes.length);
        buf.put(typeBytes);
        buf.putInt(pubKey.length);
        buf.put(pubKey);
        return buf.array();
    }

    private static byte[] filled(int len, byte value) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = value;
        return out;
    }
}