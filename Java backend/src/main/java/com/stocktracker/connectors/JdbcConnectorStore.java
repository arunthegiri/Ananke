package com.stocktracker.connectors;

import com.stocktracker.agent.model.Workspaces;
import com.stocktracker.packs.SecretVault;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** {@code connector_accounts} (V13) for state; {@link SecretVault} scope {@code connector:<id>} for fields. */
public class JdbcConnectorStore implements ConnectorStore {

    private final JdbcTemplate jdbc;
    private final SecretVault vault;

    public JdbcConnectorStore(JdbcTemplate jdbc, SecretVault vault) {
        this.jdbc  = jdbc;
        this.vault = vault;
    }

    static String scope(String connectorId) {
        return "connector:" + connectorId;
    }

    @Override
    public Optional<Account> account(String id) {
        List<Account> rows = jdbc.query("""
                SELECT connector_id, enabled, linked_at, last_test_at, last_test_ok, last_test_message,
                       last_call_at, last_error_at, last_error
                  FROM connector_accounts WHERE workspace_id = ? AND connector_id = ?
                """, this::row, Workspaces.LOCAL, id);
        return rows.stream().findFirst();
    }

    @Override
    public void save(Account a) {
        jdbc.update("""
                INSERT INTO connector_accounts (workspace_id, connector_id, enabled, linked_at, last_test_at, last_test_ok,
                                                last_test_message, last_call_at, last_error_at, last_error, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (workspace_id, connector_id) DO UPDATE SET
                    enabled = EXCLUDED.enabled, linked_at = EXCLUDED.linked_at, last_test_at = EXCLUDED.last_test_at,
                    last_test_ok = EXCLUDED.last_test_ok, last_test_message = EXCLUDED.last_test_message,
                    last_call_at = EXCLUDED.last_call_at, last_error_at = EXCLUDED.last_error_at,
                    last_error = EXCLUDED.last_error, updated_at = NOW()
                """, Workspaces.LOCAL, a.connectorId(), a.enabled(), ts(a.linkedAt()), ts(a.lastTestAt()), a.lastTestOk(),
                a.lastTestMessage(), ts(a.lastCallAt()), ts(a.lastErrorAt()), a.lastError());
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM connector_accounts WHERE workspace_id = ? AND connector_id = ?", Workspaces.LOCAL, id);
    }

    @Override
    public Optional<String> field(String id, String key) {
        return vault.get(scope(id), key);
    }

    @Override
    public Optional<String> hint(String id, String key) {
        return vault.hintFor(scope(id), key);
    }

    @Override
    public void putField(String id, String key, String value) {
        vault.put(scope(id), key, value);
    }

    @Override
    public void deleteFields(String id) {
        vault.deleteScope(scope(id));
    }

    private Account row(ResultSet rs, int i) throws SQLException {
        Object ok = rs.getObject("last_test_ok");
        return new Account(rs.getString("connector_id"), rs.getBoolean("enabled"), inst(rs.getTimestamp("linked_at")),
                inst(rs.getTimestamp("last_test_at")), ok == null ? null : (Boolean) ok, rs.getString("last_test_message"),
                inst(rs.getTimestamp("last_call_at")), inst(rs.getTimestamp("last_error_at")), rs.getString("last_error"));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
