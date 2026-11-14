package com.stocktracker.packs.compute;

import com.stocktracker.packs.ComputeTarget;
import com.stocktracker.packs.ComputeTargetRepository;
import com.stocktracker.packs.PackException;
import com.stocktracker.packs.SecretVault;
import com.stocktracker.packs.runtime.CommandRunner;
import com.stocktracker.packs.runtime.Ssh;
import com.stocktracker.packs.runtime.SshCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.stocktracker.packs.PackException.Code.*;

/**
 * "Bring your own machine": an existing Linux GPU host reachable over SSH.
 *
 * <p>Registration is two-step trust-on-first-use made explicit: Ananke scans
 * the host key and shows its fingerprint; nothing connects with credentials
 * until the user confirms it, and every later connection pins exactly that key.
 */
@Component
public class SshComputeProvider implements ComputeProvider {

    public static final String KEY_KIND = "ssh_private_key";

    private final ComputeTargetRepository targets;
    private final SecretVault vault;
    private final CommandRunner runner;
    private final String keyscanBin;
    private final String keygenBin;

    public SshComputeProvider(ComputeTargetRepository targets, SecretVault vault, CommandRunner runner,
                              @Value("${ananke.packs.ssh-keyscan-binary:ssh-keyscan}") String keyscanBin,
                              @Value("${ananke.packs.ssh-keygen-binary:ssh-keygen}") String keygenBin) {
        this.targets = targets;
        this.vault = vault;
        this.runner = runner;
        this.keyscanBin = keyscanBin;
        this.keygenBin = keygenBin;
    }

    @Override public String id() { return "ssh"; }
    @Override public String label() { return "Existing machine (SSH)"; }
    @Override public boolean canProvision() { return false; }

    public record ScannedKey(String type, String blob, String fingerprint, String knownHostsLine) { }

    /** Registers a target (unverified) and returns the host key the user must confirm. */
    public ComputeTarget register(String label, String host, int port, String username, String privateKey) {
        String h = Ssh.validHost(host);
        int p = Ssh.validPort(port);
        String u = Ssh.validUser(username);
        String publicKey = checkPrivateKey(privateKey);
        ScannedKey k = scanHostKey(h, p);

        ComputeTarget t = new ComputeTarget();
        t.setProvider(id());
        t.setLabel(label == null || label.isBlank() ? u + "@" + h : label.strip().substring(0, Math.min(100, label.strip().length())));
        t.setHost(h);
        t.setPort(p);
        t.setUsername(u);
        t.setHostKey(k.knownHostsLine());
        t.setHostKeyFingerprint(k.fingerprint());
        t.setStatus("UNVERIFIED");
        t = targets.save(t);
        vault.put(t.scope(), KEY_KIND, privateKey.strip() + "\n");
        vault.put(t.scope(), "ssh_public_key", publicKey);
        return t;
    }

    /** The user compared the fingerprint out of band and accepts it. */
    public ComputeTarget confirmHostKey(ComputeTarget t, String fingerprint) {
        if (t.getHostKeyFingerprint() == null || !t.getHostKeyFingerprint().equals(fingerprint)) {
            throw new PackException(SSH_HOSTKEY, "Fingerprint does not match the scanned host key.");
        }
        t.setStatus("HOSTKEY_CONFIRMED");
        return targets.save(t);
    }

    @Override
    public SshCredentials credentials(ComputeTarget t) {
        if ("UNVERIFIED".equals(t.getStatus())) {
            throw new PackException(SSH_HOSTKEY, "Confirm the machine's host key fingerprint before connecting.");
        }
        // Cloud VMs (§7) pass through here too once provisioned; until then there is nothing to reach.
        if (t.getHostKey() == null || java.util.Set.of("PROVISIONING", "STOPPED", "STOPPING", "STARTING", "TERMINATING")
                .contains(t.getStatus())) {
            throw new PackException(SSH_UNREACHABLE, "This machine is " + t.getStatus().toLowerCase()
                    + "; start it (or wait for it) before deploying.");
        }
        String key = vault.get(t.scope(), KEY_KIND).orElseThrow(() ->
                new PackException(SSH_AUTH, "No SSH key is stored for this machine; re-add it."));
        return new SshCredentials(t.getHost(), t.getPort(), t.getUsername(), t.getHostKey(), key);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    ScannedKey scanHostKey(String host, int port) {
        CommandRunner.Result r = runner.run(List.of(keyscanBin, "-T", "8", "-p", String.valueOf(port),
                "-t", "ed25519,ecdsa,rsa", "--", host), "", Duration.ofSeconds(20));
        List<String[]> found = new ArrayList<>();
        for (String line : (r.stdout() == null ? "" : r.stdout()).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.trim().split("\\s+");
            if (f.length >= 3) found.add(new String[]{f[1], f[2]});
        }
        if (found.isEmpty()) throw new PackException(SSH_UNREACHABLE, "No SSH server answered at " + host + ":" + port + ".");
        String[] pick = found.stream().filter(k -> k[0].equals("ssh-ed25519")).findFirst()
                .or(() -> found.stream().filter(k -> k[0].startsWith("ecdsa")).findFirst())
                .orElse(found.get(0));
        String line = Ssh.knownHostsHost(host, port) + " " + pick[0] + " " + pick[1];
        return new ScannedKey(pick[0], pick[1], Ssh.fingerprint(pick[1]), line);
    }

    /** Derives the public key with an empty passphrase: rejects malformed and passphrase-protected keys. */
    String checkPrivateKey(String privateKey) {
        if (privateKey == null || !privateKey.contains("-----BEGIN") || !privateKey.contains("PRIVATE KEY-----")) {
            throw new PackException(INVALID_INPUT, "Paste an OpenSSH or PEM private key (the whole -----BEGIN … block).");
        }
        if (privateKey.length() > 16_384) throw new PackException(INVALID_INPUT, "That key is too large.");
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ananke-key-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path f = dir.resolve("id");
            Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(f, privateKey.strip() + "\n");
            CommandRunner.Result r = runner.run(List.of(keygenBin, "-y", "-P", "", "-f", f.toString()), "", Duration.ofSeconds(10));
            if (r.exitCode() != 0 || r.stdout() == null || r.stdout().isBlank()) {
                throw new PackException(INVALID_INPUT,
                        "The key could not be read. Passphrase-protected keys aren't supported yet — use a dedicated, unencrypted deploy key.");
            }
            return r.stdout().strip();
        } catch (java.io.IOException e) {
            throw new PackException(INVALID_INPUT, "Could not check the key.");
        } finally {
            if (dir != null) {
                try (var w = Files.walk(dir)) {
                    w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (java.io.IOException ignored) { }
                    });
                } catch (java.io.IOException ignored) { }
            }
        }
    }
}
