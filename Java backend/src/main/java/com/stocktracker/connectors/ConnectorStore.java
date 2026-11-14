package com.stocktracker.connectors;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for connector state and the user's fields. The production store keeps state in
 * {@code connector_accounts} (V13) and every field in the encrypted {@code SecretVault};
 * tests use an in-memory one.
 */
public interface ConnectorStore {

    record Account(String connectorId, boolean enabled, Instant linkedAt,
                   Instant lastTestAt, Boolean lastTestOk, String lastTestMessage,
                   Instant lastCallAt, Instant lastErrorAt, String lastError) {

        /** Never-touched connector: off. Linking switches it on; keyless ones are switched on by the user. */
        public static Account fresh(String id) {
            return new Account(id, false, null, null, null, null, null, null, null);
        }

        public Account withEnabled(boolean e) {
            return new Account(connectorId, e, linkedAt, lastTestAt, lastTestOk, lastTestMessage, lastCallAt, lastErrorAt, lastError);
        }

        public Account withLinked(Instant at) {
            return new Account(connectorId, enabled, at, lastTestAt, lastTestOk, lastTestMessage, lastCallAt, lastErrorAt, lastError);
        }

        public Account withTest(Instant at, boolean ok, String message) {
            return new Account(connectorId, enabled, linkedAt, at, ok, message, lastCallAt, lastErrorAt, lastError);
        }

        public Account withCall(Instant at) {
            return new Account(connectorId, enabled, linkedAt, lastTestAt, lastTestOk, lastTestMessage, at, lastErrorAt, null);
        }

        public Account withError(Instant at, String error) {
            return new Account(connectorId, enabled, linkedAt, lastTestAt, lastTestOk, lastTestMessage, lastCallAt, at, error);
        }
    }

    Optional<Account> account(String connectorId);

    void save(Account account);

    void delete(String connectorId);

    Optional<String> field(String connectorId, String key);

    /** Last-four hint for a secret field; never the value. */
    Optional<String> hint(String connectorId, String key);

    void putField(String connectorId, String key, String value);

    void deleteFields(String connectorId);
}
