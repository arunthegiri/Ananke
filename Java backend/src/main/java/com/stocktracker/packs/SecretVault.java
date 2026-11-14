package com.stocktracker.packs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Encrypted credential store for Packs: SSH private keys, Hugging Face tokens
 * and per-deployment vLLM API keys.
 *
 * <p>AES-256-GCM with a random 96-bit nonce per write. The owner scope and kind
 * are bound in as associated data, so a ciphertext only decrypts in the row it
 * was written for. The master key comes from {@code ANANKE_SECRET_KEY} (base64,
 * 32 bytes) when set — the recommended setup — otherwise from a generated
 * owner-only key file. Plaintext is never logged, never returned by any API
 * (only a last-four hint), and never written to the database.
 *
 * <p>Limitation: with the key-file fallback the key lives on the same volume as
 * the backend's data, which protects against database dumps/backups but not
 * against someone holding the whole volume. Ananke has no user accounts yet, so
 * "scoped to the user" means scoped to this install; scopes are per target and
 * per pack.
 */
@Slf4j
@Service
public class SecretVault {

    private static final SecureRandom RNG = new SecureRandom();
    private final byte[] key;
    private final JdbcTemplate jdbc;

    @Autowired
    public SecretVault(JdbcTemplate jdbc,
                       @Value("${ananke.secret-key:}") String envKey,
                       @Value("${ananke.secret-key-file:./data/secret.key}") String keyFile) {
        this.jdbc = jdbc;
        this.key = loadKey(envKey, Path.of(keyFile));
    }

    /** Test seam: a vault with an explicit key and no database. */
    SecretVault(byte[] key) {
        this.jdbc = null;
        this.key = key.clone();
    }

    public void put(String scope, String kind, String plaintext) {
        String ct = encrypt(scope, kind, plaintext);
        jdbc.update("""
                INSERT INTO ananke_secrets (scope, kind, ciphertext, hint) VALUES (?, ?, ?, ?)
                ON CONFLICT (scope, kind) DO UPDATE SET ciphertext = EXCLUDED.ciphertext,
                    hint = EXCLUDED.hint, created_at = NOW()
                """, scope, kind, ct, hint(plaintext));
    }

    public Optional<String> get(String scope, String kind) {
        List<String> rows = jdbc.queryForList(
                "SELECT ciphertext FROM ananke_secrets WHERE scope = ? AND kind = ?", String.class, scope, kind);
        return rows.isEmpty() ? Optional.empty() : Optional.of(decrypt(scope, kind, rows.get(0)));
    }

    public Optional<String> hintFor(String scope, String kind) {
        List<String> rows = jdbc.queryForList(
                "SELECT hint FROM ananke_secrets WHERE scope = ? AND kind = ?", String.class, scope, kind);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public boolean has(String scope, String kind) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ananke_secrets WHERE scope = ? AND kind = ?", Integer.class, scope, kind);
        return n != null && n > 0;
    }

    public void delete(String scope, String kind) {
        jdbc.update("DELETE FROM ananke_secrets WHERE scope = ? AND kind = ?", scope, kind);
    }

    public void deleteScope(String scope) {
        jdbc.update("DELETE FROM ananke_secrets WHERE scope = ?", scope);
    }

    // ── crypto ───────────────────────────────────────────────────────────────

    String encrypt(String scope, String kind, String plaintext) {
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(aad(scope, kind));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt secret");
        }
    }

    String decrypt(String scope, String kind, String stored) {
        try {
            String[] parts = stored.split(":");
            if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalStateException("bad format");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            c.updateAAD(aad(scope, kind));
            return new String(c.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Never include the ciphertext or key material in the message.
            throw new IllegalStateException("Stored credential could not be decrypted (was the secret key changed?)");
        }
    }

    static String hint(String plaintext) {
        String t = plaintext == null ? "" : plaintext.strip();
        return t.length() <= 8 ? "set" : "…" + t.substring(t.length() - 4);
    }

    private static byte[] aad(String scope, String kind) {
        return (scope + "\u0000" + kind).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] loadKey(String envKey, Path file) {
        if (envKey != null && !envKey.isBlank()) {
            byte[] k = Base64.getDecoder().decode(envKey.trim());
            if (k.length != 32) throw new IllegalStateException("ANANKE_SECRET_KEY must be 32 bytes, base64-encoded");
            return k;
        }
        try {
            if (Files.exists(file)) {
                byte[] k = Base64.getDecoder().decode(Files.readString(file).trim());
                if (k.length == 32) return k;
                throw new IllegalStateException("Secret key file " + file + " is not a 32-byte base64 key");
            }
            byte[] k = new byte[32];
            RNG.nextBytes(k);
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Files.writeString(file, Base64.getEncoder().encodeToString(k));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) { }
            log.warn("Generated a new secret key at {} — set ANANKE_SECRET_KEY to keep it off the data volume", file);
            return k;
        } catch (IOException e) {
            throw new IllegalStateException("Could not load or create the secret key file " + file, e);
        }
    }
}
