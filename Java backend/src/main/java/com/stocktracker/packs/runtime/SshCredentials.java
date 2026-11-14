package com.stocktracker.packs.runtime;

/**
 * Decrypted connection material for one command. Lives only on the stack of
 * the call that needs it; {@link #toString()} is redacted so it can never leak
 * into a log line or exception message.
 */
public record SshCredentials(String host, int port, String username, String knownHostsLine, String privateKey) {

    @Override
    public String toString() {
        return "SshCredentials[" + username + "@" + host + ":" + port + ", key=<redacted>]";
    }
}
