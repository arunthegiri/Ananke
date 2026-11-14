package com.stocktracker.packs;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretVaultTest {

    private final SecretVault vault = new SecretVault(key());

    private static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        String ct = vault.encrypt("pack:1", "hf_token", "hf_supersecretvalue1234567890");
        assertThat(ct).startsWith("v1:").doesNotContain("supersecret");
        assertThat(vault.decrypt("pack:1", "hf_token", ct)).isEqualTo("hf_supersecretvalue1234567890");
    }

    @Test
    void ciphertextIsBoundToItsScope() {
        String ct = vault.encrypt("target:1", "ssh_private_key", "KEY");
        assertThatThrownBy(() -> vault.decrypt("target:2", "ssh_private_key", ct))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(ct);
    }

    @Test
    void differentKeyCannotDecrypt() {
        String ct = vault.encrypt("pack:1", "k", "v");
        assertThatThrownBy(() -> new SecretVault(key()).decrypt("pack:1", "k", ct)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hintsRevealAtMostFourChars() {
        assertThat(SecretVault.hint("hf_abcdefghijklmnop")).isEqualTo("…mnop");
        assertThat(SecretVault.hint("short")).isEqualTo("set");
    }
}
