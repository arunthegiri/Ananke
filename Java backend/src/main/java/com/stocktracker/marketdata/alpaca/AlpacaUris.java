package com.stocktracker.marketdata.alpaca;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Absolute, strictly-encoded Alpaca URIs.
 *
 * <p>Alpaca page tokens are base64 and may contain '+', '/' and '='. Left raw in
 * a query string, '+' is decoded server-side as a space and pagination breaks
 * on whichever page happens to produce one. Expanding every value as a URI
 * variable after {@code encode()} encodes reserved characters strictly, and
 * handing RestClient a {@link URI} (not a String) stops it encoding twice.
 */
final class AlpacaUris {

    private AlpacaUris() {}

    /** Null values are skipped, so optional params can be passed unconditionally. */
    static URI build(String baseUrl, String path, Map<String, Object> params) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl).path(path);
        Map<String, Object> vars = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (v == null) return;
            b.queryParam(k, "{" + k + "}");
            vars.put(k, v);
        });
        return b.encode().buildAndExpand(vars).toUri();
    }

    /** Insertion-ordered param map (readable logs, stable URIs in tests). */
    static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
