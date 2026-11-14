package com.stocktracker.agent.model;

import com.stocktracker.packs.SecretVault;

import java.util.Optional;

/** Connection credentials in the Packs {@link SecretVault} (AES-GCM, scope-bound) — §5.5. */
public class VaultCredentialStore implements CredentialStore {

    private final SecretVault vault;

    public VaultCredentialStore(SecretVault vault) {
        this.vault = vault;
    }

    @Override
    public Optional<String> get(String scope, String kind) {
        return vault.get(scope, kind);
    }

    @Override
    public void put(String scope, String kind, String plaintext) {
        vault.put(scope, kind, plaintext);
    }

    @Override
    public void delete(String scope, String kind) {
        vault.delete(scope, kind);
    }

    @Override
    public Optional<String> hint(String scope, String kind) {
        return vault.hintFor(scope, kind);
    }
}
