package com.stocktracker.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a connector gets for one call: the user's fields (decrypted for this call only) and a
 * shared HTTP client whose errors are already mapped to {@link ConnectorException}s.
 */
public final class ConnectorContext {

    static final int MAX_BODY = 20_000_000;

    private final ConnectorSpec spec;
    private final Function<String, Optional<String>> fields;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Runnable permit;

    /** @param permit counts one upstream request against the connector's rate limits (may block briefly or throw) */
    public ConnectorContext(ConnectorSpec spec, Function<String, Optional<String>> fields,
                            HttpClient http, ObjectMapper mapper, Runnable permit) {
        this.spec   = spec;
        this.fields = fields;
        this.http   = http;
        this.mapper = mapper;
        this.permit = permit == null ? () -> { } : permit;
    }

    /**
     * Counts one upstream request against the rate limits. {@link #get} does this itself;
     * connectors with their own transport (MCP) call it once per request they send.
     */
    public void acquirePermit() {
        permit.run();
    }

    public ConnectorSpec spec() {
        return spec;
    }

    public Optional<String> field(String key) {
        return fields.apply(key).map(String::strip).filter(s -> !s.isEmpty());
    }

    public String require(String key) {
        return field(key).orElseThrow(() -> new ConnectorException(ConnectorException.Kind.NOT_LINKED,
                spec.name() + " is not linked: " + key + " is missing. Add it in Settings → Connectors."));
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public HttpClient http() {
        return http;
    }

    /** GET → parsed JSON. */
    public JsonNode getJson(URI uri, Map<String, String> headers) {
        String body = get(uri, headers, "application/json");
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw ConnectorException.upstream(spec.name() + " answered something that is not JSON.");
        }
    }

    /** GET → body text; non-2xx statuses become {@link ConnectorException}s. */
    public String get(URI uri, Map<String, String> headers, String accept) {
        acquirePermit();
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET();
        if (accept != null) b.header("Accept", accept);
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw ConnectorException.upstream(spec.name() + " is unreachable: " + e.getClass().getSimpleName());
        }
        return check(r.statusCode(), r.body());
    }

    String check(int status, String body) {
        if (status / 100 == 2) {
            if (body != null && body.length() > MAX_BODY) throw ConnectorException.upstream(spec.name() + " answered with too much data.");
            return body;
        }
        String detail = snippet(body);
        if (status == 401 || status == 403) {
            throw new ConnectorException(ConnectorException.Kind.AUTH,
                    spec.name() + " rejected the request (HTTP " + status + "). Check the key or identity in Settings → Connectors."
                            + (detail.isEmpty() ? "" : " " + detail));
        }
        if (status == 429) {
            throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED,
                    spec.name() + " rate limit reached (HTTP 429). Try again shortly.");
        }
        if (status == 404) throw ConnectorException.badRequest(spec.name() + ": not found (HTTP 404).");
        throw ConnectorException.upstream(spec.name() + " failed: HTTP " + status + (detail.isEmpty() ? "" : " " + detail));
    }

    static String snippet(String s) {
        if (s == null) return "";
        String t = s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
