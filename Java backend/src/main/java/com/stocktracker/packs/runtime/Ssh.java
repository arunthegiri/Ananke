package com.stocktracker.packs.runtime;

import com.stocktracker.packs.PackException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import static com.stocktracker.packs.PackException.Code.*;

/**
 * Helpers around the system OpenSSH client: input validation, the fixed argv
 * every connection uses, short-lived credential files, and error mapping.
 */
public final class Ssh {

    private Ssh() { }

    private static final Pattern HOSTNAME = Pattern.compile("^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:]{2,39}$");
    private static final Pattern USER = Pattern.compile("^[a-z_][a-z0-9_.-]{0,31}$");

    public static String validHost(String host) {
        String h = host == null ? "" : host.strip();
        if (h.startsWith("-") || !(HOSTNAME.matcher(h).matches() || IPV6.matcher(h).matches())) {
            throw new PackException(INVALID_INPUT, "Host must be a hostname or IP address.");
        }
        return h;
    }

    public static String validUser(String user) {
        String u = user == null ? "" : user.strip();
        if (!USER.matcher(u).matches()) throw new PackException(INVALID_INPUT, "Username must be a valid Linux user name.");
        return u;
    }

    public static int validPort(int port) {
        if (port < 1 || port > 65535) throw new PackException(INVALID_INPUT, "SSH port must be 1–65535.");
        return port;
    }

    public static String knownHostsHost(String host, int port) {
        return port == 22 ? host : "[" + host + "]:" + port;
    }

    /** "SHA256:…" fingerprint of a base64 public key blob, same format as ssh-keygen -l. */
    public static String fingerprint(String base64Blob) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(base64Blob));
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new PackException(SSH_HOSTKEY, "Host key could not be parsed.");
        }
    }

    /** A private, owner-only directory holding the key and pinned known_hosts for one connection. */
    public static final class CredFiles implements AutoCloseable {
        public final Path dir;
        public final Path key;
        public final Path knownHosts;

        public CredFiles(SshCredentials c) {
            try {
                dir = Files.createTempDirectory("ananke-ssh-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                key = dir.resolve("id");
                knownHosts = dir.resolve("known_hosts");
                writePrivate(key, c.privateKey().strip() + "\n");
                writePrivate(knownHosts, (c.knownHostsLine() == null ? "" : c.knownHostsLine()) + "\n");
            } catch (IOException e) {
                throw new PackException(SSH_FAILED, "Could not prepare SSH credentials.");
            }
        }

        private static void writePrivate(Path p, String content) throws IOException {
            Files.createFile(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(p, content, StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        if (Files.isRegularFile(p)) Files.write(p, new byte[(int) Math.min(Files.size(p), 65536)]);
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
    }

    /** The fixed ssh argv prefix. Host-key checking is strict against the single pinned key. */
    public static List<String> baseArgv(String sshBin, SshCredentials c, CredFiles f) {
        List<String> a = new ArrayList<>(List.of(sshBin,
                "-F", "/dev/null",
                "-i", f.key.toString(),
                "-p", String.valueOf(c.port()),
                "-o", "BatchMode=yes",
                "-o", "IdentitiesOnly=yes",
                "-o", "IdentityAgent=none",
                "-o", "PasswordAuthentication=no",
                "-o", "KbdInteractiveAuthentication=no",
                "-o", "StrictHostKeyChecking=yes",
                "-o", "UserKnownHostsFile=" + f.knownHosts,
                "-o", "GlobalKnownHostsFile=/dev/null",
                "-o", "ConnectTimeout=10",
                "-o", "ServerAliveInterval=15",
                "-o", "ServerAliveCountMax=4",
                "-o", "ForwardAgent=no",
                "-o", "ForwardX11=no",
                "-o", "LogLevel=ERROR"));
        return a;
    }

    /** Maps OpenSSH's stderr to a user-facing failure. Never echoes the raw stderr (could include paths). */
    public static PackException classify(int exit, String stderr) {
        String e = stderr == null ? "" : stderr;
        if (e.contains("Permission denied")) return new PackException(SSH_AUTH, "SSH authentication failed: the server rejected this key for this user.");
        if (e.contains("Host key verification failed") || e.contains("REMOTE HOST IDENTIFICATION HAS CHANGED")
                || e.contains("No ED25519 host key is known") || e.contains("host key for")) {
            return new PackException(SSH_HOSTKEY, "The machine's host key does not match the one you confirmed. Re-verify the machine before connecting.");
        }
        if (e.contains("Could not resolve hostname")) return new PackException(SSH_UNREACHABLE, "Could not resolve the hostname.");
        if (e.contains("Connection refused")) return new PackException(SSH_UNREACHABLE, "Connection refused: nothing is listening on that SSH port.");
        if (e.contains("timed out") || e.contains("No route to host") || e.contains("Network is unreachable")) {
            return new PackException(SSH_UNREACHABLE, "The machine could not be reached (timeout / no route).");
        }
        if (e.contains("invalid format") || e.contains("Load key")) return new PackException(SSH_AUTH, "The private key could not be loaded.");
        if (exit != 255) {
            // ssh itself uses 255; anything else is the remote script. Surface its last line, sanitized.
            String[] lines = e.strip().split("\n");
            String last = lines.length == 0 ? "" : lines[lines.length - 1].replaceAll("[^\\w .,:/()'=-]", "");
            return new PackException(SSH_FAILED, "The remote step failed (exit " + exit + ")"
                    + (last.isBlank() ? "." : ": " + last.substring(0, Math.min(200, last.length()))));
        }
        return new PackException(SSH_FAILED, "SSH failed (exit " + exit + ").");
    }
}
