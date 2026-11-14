package com.stocktracker.connectors;

/**
 * A connector call that could not produce data, with a message fit for the user and the model.
 * Never carries credentials: messages are built from status codes and upstream error text only.
 */
public class ConnectorException extends RuntimeException {

    public enum Kind {
        /** Required fields missing — link it in Settings → Connectors. */
        NOT_LINKED,
        /** The user switched it off. */
        DISABLED,
        /** Upstream rejected the credentials (401/403). */
        AUTH,
        /** Ananke's own limiter or upstream 429. */
        RATE_LIMITED,
        /** Bad arguments from the caller. */
        BAD_REQUEST,
        /** Upstream failed, timed out or answered something unusable. */
        UPSTREAM
    }

    private final Kind kind;

    public ConnectorException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static ConnectorException badRequest(String message) {
        return new ConnectorException(Kind.BAD_REQUEST, message);
    }

    public static ConnectorException upstream(String message) {
        return new ConnectorException(Kind.UPSTREAM, message);
    }
}
