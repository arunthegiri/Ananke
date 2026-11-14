package com.stocktracker.agent.model;

import java.net.URI;
import java.util.EnumSet;
import java.util.Set;

/**
 * Where a prompt goes when a model is called (§5.4 step 3). Derived by the server from the
 * connection's adapter and endpoint — never taken from the caller — so a connection cannot
 * claim to be private when it is not.
 */
public enum TrustZone {
    /** A third-party hosted API. */
    EXTERNAL_API,
    /** A model server on this machine or LAN (Ollama, LM Studio, a local vLLM). */
    LOCAL,
    /** The user's own remote machine, reached through a Pack's tunnel. */
    PRIVATE_COMPUTE;

    public static final Set<TrustZone> ANY = EnumSet.allOf(TrustZone.class);
    public static final Set<TrustZone> PRIVATE_ONLY = EnumSet.of(LOCAL, PRIVATE_COMPUTE);

    public static TrustZone derive(Adapter adapter, String baseUrl) {
        if (adapter == Adapter.PACK) return PRIVATE_COMPUTE;
        return isLocalHost(baseUrl) ? LOCAL : EXTERNAL_API;
    }

    static boolean isLocalHost(String baseUrl) {
        try {
            String host = baseUrl == null ? null : URI.create(baseUrl.trim()).getHost();
            if (host == null) return false;
            String h = host.toLowerCase();
            return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]")
                    || h.equals("host.docker.internal") || h.endsWith(".local");
        } catch (Exception e) {
            return false;
        }
    }
}
