package com.stocktracker.connectors.press_wires;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.press_wires.PressWiresConnector.Feed;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Press wires connector against fake feeds shaped like the real PRN / Business Wire / Fed / SEC RSS. */
class PressWiresConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> agents = new CopyOnWriteArrayList<>();
    final Map<String, String> fields = new HashMap<>();
    final Map<String, String> bodies = new HashMap<>();
    HttpServer server;
    PressWiresConnector wires;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        bodies.put("/prn", PRN);
        bodies.put("/bw", BW);
        bodies.put("/fed", "﻿" + FED);
        bodies.put("/sec", SEC);
        wires = new PressWiresConnector(List.of(
                new Feed("prnewswire", "PR Newswire", base + "/prn"),
                new Feed("businesswire", "Business Wire", base + "/bw"),
                new Feed("fed", "Federal Reserve", base + "/fed"),
                new Feed("sec", "SEC", base + "/sec")));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(wires.spec(), k -> Optional.ofNullable(fields.get(k)), HttpClient.newHttpClient(), om, () -> { });
    }

    void handle(HttpExchange ex) throws IOException {
        agents.add(ex.getRequestHeaders().getFirst("User-Agent"));
        String body = bodies.get(ex.getRequestURI().getPath());
        byte[] b = (body == null ? "gone" : body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/rss+xml");
        ex.sendResponseHeaders(body == null ? 503 : 200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static final String PRN = """
            <?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:media="http://search.yahoo.com/mrss/"><channel>
            <title>All News Releases</title>
            <item><title>Acme Robotics Announces Third Quarter 2026 Results</title><link>https://www.prnewswire.com/news-releases/acme-q3-302913655.html</link>
            <guid>https://www.prnewswire.com/news-releases/acme-q3-302913655.html</guid><pubDate>Sat, 14 Nov 2026 17:48:00 +0000</pubDate>
            <description><![CDATA[<p>SAN JOSE, Calif., Nov. 14, 2026 /PRNewswire/ -- Acme Robotics (NASDAQ: ACME) today reported...</p>]]></description>
            <dc:contributor>Acme Robotics</dc:contributor></item>
            <item><title>Unrelated Company Names New CFO</title><link>https://www.prnewswire.com/news-releases/cfo-302913000.html</link>
            <pubDate>Sat, 14 Nov 2026 16:00:00 +0000</pubDate><description><![CDATA[<p>Nothing about acmeish things.</p>]]></description></item>
            </channel></rss>""";

    static final String BW = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Business Wire</title>
            <item><title>Hot Wheels Crowns 2026 Legends Tour Winner</title><pubDate>Sat, 14 Nov 2026 17:30:00 UT</pubDate>
            <description>EL SEGUNDO, Calif.--(BUSINESS WIRE)--Mattel, Inc. (NASDAQ: MAT) today announced the winner, with ACME parts.</description>
            <link>http://www.businesswire.com/news/home/20261114608677/en/Hot-Wheels-Crowns/?feedref=JjAwJuNHiystnCoBq</link>
            <guid isPermaLink="false">20261114608677en</guid></item>
            <item><title>منصة المراقبة في Empire State Building</title><pubDate>Sat, 14 Nov 2026 15:00:00 UT</pubDate>
            <description>نص</description><link>http://www.businesswire.com/news/home/20261114600001/ar/</link></item>
            </channel></rss>""";

    static final String FED = """
            <?xml version="1.0" encoding="utf-8" ?>
            <rss version="2.0"><channel><title>FRB: Press Release - All Releases</title>
            <item><title>Agencies extend comment period for proposed third-party risk management guidance </title>
            <link><![CDATA[https://www.federalreserve.gov/newsevents/pressreleases/bcreg20261113a.htm]]></link>
            <description><![CDATA[Agencies extend comment period for proposed third-party risk management guidance ]]></description>
            <category>Banking and Consumer Regulatory Policy</category>
            <pubDate><![CDATA[Fri, 13 Nov 2026 21:15:00 GMT]]></pubDate></item>
            </channel></rss>""";

    static final String SEC = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" xml:base="https://www.sec.gov/"><channel><title>Press Releases</title>
            <item><title>    SEC Proposes Executive and Director Compensation Disclosure Reforms
            </title><link>https://www.sec.gov/newsroom/press-releases/2026-114</link>
            <description>The Securities and Exchange Commission today proposed rule amendments…</description>
            <pubDate>Fri, 13 Nov 2026 11:57:00 -0500</pubDate><dc:creator> </dc:creator><guid isPermaLink="false">c637</guid></item>
            </channel></rss>""";

    @Test
    @SuppressWarnings("unchecked")
    void latest_mergesAllFeedsNewestFirst_withUtcPublishTimes() throws Exception {
        Map<String, Object> en = (Map<String, Object>) wires.latest(ctx(), om.readTree("{}"));
        assertThat(en).containsEntry("matched", 5);   // the Arabic Business Wire release is dropped by default
        Map<String, Object> out = (Map<String, Object>) wires.latest(ctx(), om.readTree("{\"lang\":\"any\"}"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).extracting(m -> m.get("published_at")).containsExactly(
                "2026-11-14T17:48:00Z", "2026-11-14T17:30:00Z", "2026-11-14T16:00:00Z", "2026-11-14T15:00:00Z",
                "2026-11-13T21:15:00Z", "2026-11-13T16:57:00Z");
        assertThat(items.get(0)).containsEntry("source", "prnewswire")
                .containsEntry("summary", "SAN JOSE, Calif., Nov. 14, 2026 /PRNewswire/ -- Acme Robotics (NASDAQ: ACME) today reported...");
        assertThat(items.get(1)).containsEntry("language", "en")
                .containsEntry("url", "http://www.businesswire.com/news/home/20261114608677/en/Hot-Wheels-Crowns/");
        Map<String, Object> fed = items.get(4);
        assertThat(fed).containsEntry("source", "fed").containsEntry("category", "Banking and Consumer Regulatory Policy")
                .doesNotContainKey("summary");   // same as the title
        assertThat(items.get(5)).containsEntry("title", "SEC Proposes Executive and Director Compensation Disclosure Reforms");
        assertThat(agents).containsOnly("Ananke/1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_matchesWholeWordsCaseInsensitively_inChosenSources() throws Exception {
        fields.put("contact", "Jane jane@example.com");
        Map<String, Object> out = (Map<String, Object>) wires.search(ctx(), om.readTree(
                "{\"terms\":[\"acme\"],\"sources\":[\"prnewswire\",\"businesswire\"]}"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).extracting(m -> m.get("source")).containsExactly("prnewswire", "businesswire");   // "acmeish" is not a match
        assertThat(agents).containsOnly("Ananke/1 (Jane jane@example.com)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneFeedDown_isReported_notFatal() throws Exception {
        bodies.remove("/bw");
        Map<String, Object> out = (Map<String, Object>) wires.latest(ctx(), om.readTree("{\"limit\":2}"));
        assertThat((List<String>) out.get("unavailable")).singleElement().asString().startsWith("businesswire:");
        assertThat((List<?>) out.get("items")).hasSize(2);
        assertThatThrownBy(() -> wires.latest(ctx(), om.readTree("{\"sources\":[\"businesswire\"]}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.UPSTREAM);
    }

    @Test
    void doctypeAndExternalEntities_areRefused() throws Exception {
        Path secret = Files.createTempFile("secret", ".txt");
        Files.writeString(secret, "TOP-SECRET");
        String evil = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "%s">]>
                <rss version="2.0"><channel><item><title>&xxe;</title><pubDate>Fri, 13 Nov 2026 11:57:00 GMT</pubDate></item></channel></rss>"""
                .formatted(secret.toUri());
        bodies.put("/sec", evil);
        assertThatThrownBy(() -> wires.latest(ctx(), om.readTree("{\"sources\":[\"sec\"]}")))
                .isInstanceOf(ConnectorException.class).hasMessageNotContaining("TOP-SECRET")
                .extracting("kind").isEqualTo(ConnectorException.Kind.UPSTREAM);
        String bomb = """
                <?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;">]>
                <rss><channel><item><title>&lol2;</title></item></channel></rss>""";
        assertThatThrownBy(() -> PressWiresConnector.parse(new Feed("x", "X", "u"), bomb)).isInstanceOf(ConnectorException.class);
        Files.deleteIfExists(secret);
    }

    @Test
    void atomFeeds_areReadToo() {
        String atom = """
                <?xml version="1.0" encoding="utf-8"?><feed xmlns="http://www.w3.org/2005/Atom"><title>t</title>
                <entry><title>Atom item</title><link rel="alternate" href="https://example.com/a"/><updated>2026-11-14T12:00:00-05:00</updated>
                <summary>Short &amp; sweet</summary></entry></feed>""";
        List<Map<String, Object>> items = PressWiresConnector.parse(new Feed("x", "X", "u"), atom);
        assertThat(items).singleElement().satisfies(m -> assertThat(m)
                .containsEntry("published_at", "2026-11-14T17:00:00Z").containsEntry("url", "https://example.com/a")
                .containsEntry("summary", "Short & sweet"));
    }

    @Test
    void badArgs_areBadRequest() {
        assertThatThrownBy(() -> wires.latest(ctx(), om.readTree("{\"sources\":[\"globenewswire\"]}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> wires.latest(ctx(), om.readTree("{\"lang\":\"english\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> wires.search(ctx(), om.readTree("{}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> wires.search(ctx(), om.readTree("{\"terms\":[\"a\"]}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThat(agents).isEmpty();
    }

    @Test
    void test_readsTheFedFeed() throws Exception {
        assertThat(wires.test(ctx())).contains("Federal Reserve").contains("1 items");
        assertThat(wires.spec().keyless()).isTrue();
    }
}
