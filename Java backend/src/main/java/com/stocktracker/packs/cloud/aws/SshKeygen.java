package com.stocktracker.packs.cloud.aws;

import com.stocktracker.packs.PackException;
import com.stocktracker.packs.runtime.CommandRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * A fresh ed25519 keypair per cloud VM (§7.2), made by the system {@code ssh-keygen} in a
 * {@code 0700} temp dir that is deleted immediately; the private key goes straight to SecretVault.
 */
public class SshKeygen {

    public record KeyPair(String privateKey, String publicKey) {
        @Override public String toString() { return "KeyPair[" + publicKey + "]"; }
    }

    private final CommandRunner runner;
    private final String keygenBin;

    public SshKeygen(CommandRunner runner, String keygenBin) {
        this.runner = runner;
        this.keygenBin = keygenBin;
    }

    public KeyPair ed25519(String comment) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ananke-vmkey-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path f = dir.resolve("id");
            CommandRunner.Result r = runner.run(List.of(keygenBin, "-q", "-t", "ed25519", "-N", "", "-C", comment, "-f", f.toString()),
                    "", Duration.ofSeconds(15));
            if (r.exitCode() != 0 || !Files.exists(f) || !Files.exists(dir.resolve("id.pub"))) {
                throw new PackException(PackException.Code.PROVISION_FAILED, "Could not generate an SSH key for the VM.");
            }
            return new KeyPair(Files.readString(f), Files.readString(dir.resolve("id.pub")).strip());
        } catch (IOException e) {
            throw new PackException(PackException.Code.PROVISION_FAILED, "Could not generate an SSH key for the VM.");
        } finally {
            if (dir != null) {
                try (var w = Files.walk(dir)) {
                    w.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) { }
            }
        }
    }
}
