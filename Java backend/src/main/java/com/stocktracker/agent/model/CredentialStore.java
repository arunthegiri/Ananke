package com.stocktracker.agent.model;

import java.util.Optional;

/** Where connection credentials live: the Packs {@code SecretVault} in production (§5.5). */
public interface CredentialStore {

    Optional<String> get(String scope, String kind);

    void put(String scope, String kind, String plaintext);

    void delete(String scope, String kind);

    /** "…abcd" style hint for the UI; never the secret. */
    Optional<String> hint(String scope, String kind);
}
