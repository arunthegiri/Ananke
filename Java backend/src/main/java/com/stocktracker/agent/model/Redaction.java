package com.stocktracker.agent.model;

import java.util.regex.Pattern;

/** Scrubs credentials out of provider error text before it is stored or shown (§5.5, §19.1). */
final class Redaction {

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{6,}");
    private static final Pattern KEYLIKE = Pattern.compile("\\b(sk|rk|pk|xai|gsk|hf)[-_][A-Za-z0-9_-]{8,}");
    static final int MAX = 500;

    private Redaction() { }

    static String scrub(String text, String... secrets) {
        if (text == null) return null;
        String out = text;
        for (String s : secrets) {
            if (s != null && s.length() >= 4) out = out.replace(s, "[redacted]");
        }
        out = BEARER.matcher(out).replaceAll("$1[redacted]");
        out = KEYLIKE.matcher(out).replaceAll("[redacted]");
        return out.length() > MAX ? out.substring(0, MAX - 1) + "…" : out;
    }
}
