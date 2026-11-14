package com.stocktracker.packs.cloud;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/** The backend's public IPv4 — the address its SSH connections come from (§7.2 security group rule). */
public class EgressIp {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private final String url;

    public EgressIp(String url) {
        this.url = url;
    }

    public String url() {
        return url;
    }

    /** {@code a.b.c.d}, or empty when the echo service is unreachable or answers oddly. */
    public Optional<String> detect() {
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            String ip = r.body() == null ? "" : r.body().strip();
            return r.statusCode() == 200 && IPV4.matcher(ip).matches() ? Optional.of(ip) : Optional.empty();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
