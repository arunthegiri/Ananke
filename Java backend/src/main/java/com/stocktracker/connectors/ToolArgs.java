package com.stocktracker.connectors;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Helpers every connector uses: JSON-schema fragments for tool parameters, argument parsing that
 * fails as {@link ConnectorException.Kind#BAD_REQUEST} (so the model gets a fixable message), and
 * URL building. Keep connectors small by using these instead of re-writing them.
 */
public final class ToolArgs {

    private ToolArgs() { }

    // ── schema fragments ─────────────────────────────────────────────────────

    public static Map<String, Object> object(Map<String, Object> props, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", props);
        if (required != null && !required.isEmpty()) m.put("required", required);
        return m;
    }

    /** Ordered properties: {@code props("a", str(..), "b", integer(..))}. */
    public static Map<String, Object> props(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("props needs key/value pairs");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> enumStr(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> number(String description) {
        return Map.of("type", "number", "description", description);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> strArray(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    /** Required non-blank string, at most {@code max} chars. */
    public static String requireStr(JsonNode args, String name, int max) {
        String s = optStr(args, name);
        if (s == null) throw ConnectorException.badRequest(name + " is required");
        if (s.length() > max) throw ConnectorException.badRequest(name + " is too long (max " + max + " characters)");
        return s;
    }

    /** Trimmed string or null when absent/blank. */
    public static String optStr(JsonNode args, String name) {
        JsonNode n = args == null ? null : args.get(name);
        if (n == null || n.isNull()) return null;
        String s = n.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    /** Ticker like AAPL / BRK-B / BRK.B → upper-case; BAD_REQUEST otherwise. */
    public static String ticker(JsonNode args, String name) {
        String t = requireStr(args, name, 12).toUpperCase(Locale.ROOT);
        if (!t.matches("^[A-Z0-9.^/-]{1,12}$")) throw ConnectorException.badRequest(name + " must look like AAPL or BRK-B");
        return t;
    }

    public static int intArg(JsonNode args, String name, int dflt, int min, int max) {
        JsonNode n = args == null ? null : args.get(name);
        int v = n == null || n.isNull() || !n.canConvertToInt() && !n.isTextual() ? dflt : n.asInt(dflt);
        return Math.max(min, Math.min(max, v));
    }

    /** ISO date or null; BAD_REQUEST on anything else. */
    public static LocalDate date(JsonNode args, String name) {
        String s = optStr(args, name);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw ConnectorException.badRequest(name + " must be YYYY-MM-DD");
        }
    }

    /** String array (or comma-separated string) → ordered set, trimmed, optionally upper-cased. */
    public static Set<String> strSet(JsonNode args, String name, boolean upper) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode n = args == null ? null : args.get(name);
        if (n == null || n.isNull()) return out;
        List<String> raw = new ArrayList<>();
        if (n.isArray()) n.forEach(x -> raw.add(x.asText("")));
        else raw.addAll(List.of(n.asText("").split(",")));
        for (String s : raw) {
            String t = s.strip();
            if (!t.isEmpty()) out.add(upper ? t.toUpperCase(Locale.ROOT) : t);
        }
        return out;
    }

    /** Value must be one of {@code allowed} (case-insensitive); returns the canonical spelling. */
    public static String oneOf(JsonNode args, String name, String dflt, List<String> allowed) {
        String s = optStr(args, name);
        if (s == null) return dflt;
        for (String a : allowed) if (a.equalsIgnoreCase(s)) return a;
        throw ConnectorException.badRequest(name + " must be one of " + allowed);
    }

    // ── URLs ─────────────────────────────────────────────────────────────────

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** {@code base?k=v&…}, skipping null values, URL-encoding everything. */
    public static String url(String base, Object... kv) {
        StringJoiner q = new StringJoiner("&");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) continue;
            q.add(enc(String.valueOf(kv[i])) + "=" + enc(String.valueOf(kv[i + 1])));
        }
        String qs = q.toString();
        return qs.isEmpty() ? base : base + (base.contains("?") ? "&" : "?") + qs;
    }
}
