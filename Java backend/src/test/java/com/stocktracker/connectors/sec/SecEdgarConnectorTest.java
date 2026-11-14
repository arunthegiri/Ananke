package com.stocktracker.connectors.sec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SEC EDGAR connector against a fake EDGAR (Connectors roadmap §2, §8). */
class SecEdgarConnectorTest {

    static final String IDENTITY = "Test Runner test@example.com";

    final ObjectMapper om = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> agents = new CopyOnWriteArrayList<>();
    final AtomicInteger permits = new AtomicInteger();
    HttpServer server;
    SecEdgarConnector sec;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        sec = new SecEdgarConnector(Clock.systemUTC(), base + "/www", base + "/data", base + "/efts");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String identity) {
        return new ConnectorContext(sec.spec(), k -> k.equals("identity") ? Optional.ofNullable(identity) : Optional.empty(),
                HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery();
        paths.add(p + (q == null ? "" : "?" + q));
        agents.add(ex.getRequestHeaders().getFirst("User-Agent"));
        switch (p) {
            case "/www/files/company_tickers.json" -> send(ex, 200, """
                    {"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc."},
                     "1":{"cik_str":1067983,"ticker":"BRK-B","title":"BERKSHIRE HATHAWAY INC"}}""");
            case "/data/submissions/CIK0000320193.json" -> send(ex, 200, """
                    {"name":"Apple Inc.","tickers":["AAPL"],"exchanges":["Nasdaq"],"sic":"3571",
                     "sicDescription":"Electronic Computers","fiscalYearEnd":"0926","stateOfIncorporation":"CA",
                     "formerNames":[{"name":"APPLE COMPUTER INC"}],
                     "filings":{"recent":{
                       "accessionNumber":["0001140361-26-043484","0001140361-26-042798","0000320193-26-000026"],
                       "filingDate":["2026-11-12","2026-11-09","2026-11-03"],
                       "reportDate":["2026-11-10","2026-11-09","2026-09-26"],
                       "acceptanceDateTime":["2026-11-12T18:31:02.000Z","2026-11-09T16:05:11.000Z","2026-11-03T16:01:40.000Z"],
                       "form":["4","8-K","10-K"],
                       "items":["","2.02,9.01",""],
                       "primaryDocument":["xslF345X06/form4.xml","ef8k.htm","aapl-20260926.htm"],
                       "primaryDocDescription":["","8-K","10-K"]}}}""");
            case "/data/api/xbrl/companyconcept/CIK0000320193/us-gaap/NetIncomeLoss.json" -> send(ex, 200, """
                    {"label":"Net Income (Loss)","units":{"USD":[
                      {"start":"2024-09-29","end":"2025-09-27","val":112000000000,"fy":2025,"fp":"FY","form":"10-K","filed":"2025-10-31","accn":"a1","frame":"CY2025"},
                      {"start":"2025-06-29","end":"2025-09-27","val":27000000000,"fy":2025,"fp":"Q4","form":"10-K","filed":"2025-10-31","accn":"a1"},
                      {"start":"2025-03-30","end":"2025-06-28","val":23400000000,"fy":2025,"fp":"Q3","form":"10-Q","filed":"2025-08-01","accn":"a2"},
                      {"start":"2025-09-28","end":"2026-09-26","val":120000000000,"fy":2026,"fp":"FY","form":"10-K","filed":"2026-11-03","accn":"a3"},
                      {"start":"2024-09-29","end":"2025-09-27","val":111500000000,"fy":2026,"fp":"FY","form":"10-K","filed":"2026-11-03","accn":"a3"}]}}""");
            case "/data/api/xbrl/companyconcept/CIK0000320193/us-gaap/MadeUpConcept.json" -> send(ex, 404, "Not Found");
            case "/www/Archives/edgar/data/320193/000114036126043484/form4.xml" -> send(ex, 200, FORM4);
            case "/efts" -> send(ex, 200, """
                    {"hits":{"total":{"value":612},"hits":[{"_id":"0001628280-26-069778:ex991.htm","_source":{
                      "ciks":["0000216085"],"display_names":["HAVERTY FURNITURE COMPANIES INC (HVT) (CIK 0000216085)"],
                      "form":"8-K","file_type":"EX-99.1","file_date":"2026-11-04","period_ending":"2026-11-04",
                      "items":["2.02","9.01"],"adsh":"0001628280-26-069778"}}]}}""");
            default -> send(ex, 404, "nope");
        }
    }

    void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", body.startsWith("<") ? "application/xml" : "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void test_requiresAContactEmail_andSendsTheIdentityAsUserAgent() throws Exception {
        assertThatThrownBy(() -> sec.test(ctx("just a name")))
                .isInstanceOf(ConnectorException.class).hasMessageContaining("contact email");
        assertThat(paths).isEmpty();

        assertThat(sec.test(ctx(IDENTITY))).contains("CIK 0000320193");
        assertThat(agents).containsOnly(IDENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void filings_carryAcceptanceTime_andFilterByForm() throws Exception {
        Map<String, Object> out = (Map<String, Object>) sec.filings(ctx(IDENTITY), om.readTree("{\"ticker\":\"aapl\",\"forms\":[\"8-k\"]}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("filings");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("form", "8-K").containsEntry("items", "2.02,9.01")
                .containsEntry("accepted_at", "2026-11-09T16:05:11.000Z")
                .containsEntry("url", "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/www/Archives/edgar/data/320193/000114036126042798/ef8k.htm");

        // The ticker file is fetched once and reused; every request counted a permit.
        sec.filings(ctx(IDENTITY), om.readTree("{\"ticker\":\"AAPL\"}"));
        assertThat(paths.stream().filter(p -> p.endsWith("company_tickers.json"))).hasSize(1);
        assertThat(permits).hasValue(paths.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void financials_annualOnly_newestFirst_withFiledDates() throws Exception {
        Map<String, Object> out = (Map<String, Object>) sec.financials(ctx(IDENTITY),
                om.readTree("{\"ticker\":\"AAPL\",\"concept\":\"NetIncomeLoss\",\"period\":\"annual\"}"));
        List<Map<String, Object>> values = (List<Map<String, Object>>) out.get("values");
        // FY2025 appears once (first reported 2025-10-31) with the FY2026 10-K's different comparative as a restatement;
        // the Q4 slice inside the 10-K is not an annual value.
        assertThat(values).extracting(v -> v.get("end")).containsExactly("2026-09-26", "2025-09-27");
        assertThat(values).extracting(v -> v.get("filed")).containsExactly("2026-11-03", "2025-10-31");
        assertThat(values.get(1).get("value").toString()).isEqualTo("112000000000");
        assertThat(values.get(1).get("restated")).asString().contains("111500000000").contains("2026-11-03");
        assertThat(values.get(0)).containsEntry("fiscal_period", "FY").doesNotContainKey("restated");
        assertThat(out.get("note")).asString().contains("point-in-time");

        assertThatThrownBy(() -> sec.financials(ctx(IDENTITY), om.readTree("{\"ticker\":\"AAPL\",\"concept\":\"MadeUpConcept\"}")))
                .hasMessageContaining("has no us-gaap:MadeUpConcept");
        assertThatThrownBy(() -> sec.financials(ctx(IDENTITY), om.readTree("{\"ticker\":\"AAPL\",\"concept\":\"../../etc\"}")))
                .hasMessageContaining("XBRL name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void insiderTrades_parseRawForm4Xml_notTheStylesheetView() throws Exception {
        Map<String, Object> out = (Map<String, Object>) sec.insiderTrades(ctx(IDENTITY), om.readTree("{\"ticker\":\"AAPL\"}"));
        List<Map<String, Object>> tx = (List<Map<String, Object>>) out.get("transactions");
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0))
                .containsEntry("insider", "Newstead Jennifer")
                .containsEntry("role", "SVP, GC and Government Affairs")
                .containsEntry("code", "S")
                .containsEntry("acquired_disposed", "D")
                .containsEntry("date", "2026-11-10")
                .containsEntry("plan_10b5_1", true)
                .containsEntry("accepted_at", "2026-11-12T18:31:02.000Z");
        assertThat(tx.get(0).get("shares").toString()).isEqualTo("2399");
        assertThat(tx.get(0).get("price").toString()).isEqualTo("351.36");
        assertThat(paths).contains("/www/Archives/edgar/data/320193/000114036126043484/form4.xml");
    }

    @Test
    void form4WithADoctype_isRejected() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><ownershipDocument>&e;</ownershipDocument>";
        SecEdgarConnector.Filing f = new SecEdgarConnector.Filing("a", "4", java.time.LocalDate.now(), null, null, null, "f.xml", null);
        assertThatThrownBy(() -> SecEdgarConnector.parseForm4(evil, f, "u")).hasMessageContaining("not readable XML");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_buildsTheQuery_andLinksEachHit() throws Exception {
        Map<String, Object> out = (Map<String, Object>) sec.search(ctx(IDENTITY),
                om.readTree("{\"query\":\"\\\"tariff\\\"\",\"forms\":[\"8-K\"],\"from\":\"2026-10-01\",\"to\":\"2026-11-14\"}"));
        assertThat(out).containsEntry("total", 612);
        List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("hits");
        assertThat(hits.get(0)).containsEntry("form", "8-K").containsEntry("filed", "2026-11-04");
        assertThat(hits.get(0).get("url")).asString().endsWith("/www/Archives/edgar/data/216085/000162828026069778/ex991.htm");
        assertThat(paths.get(paths.size() - 1))
                .contains("q=%22tariff%22").contains("forms=8-K").contains("startdt=2026-10-01").contains("enddt=2026-11-14");

        assertThatThrownBy(() -> sec.search(ctx(IDENTITY), om.readTree("{\"query\":\"x\",\"from\":\"last week\"}")))
                .hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    void unknownTicker_isABadRequest_notAnOutage() {
        assertThatThrownBy(() -> sec.filings(ctx(IDENTITY), om.readTree("{\"ticker\":\"ZZZZZZ\"}")))
                .isInstanceOf(ConnectorException.class)
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    static final String FORM4 = """
            <?xml version="1.0"?>
            <ownershipDocument>
              <documentType>4</documentType>
              <issuer><issuerCik>0000320193</issuerCik><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
              <reportingOwner>
                <reportingOwnerId><rptOwnerCik>0001780525</rptOwnerCik><rptOwnerName>Newstead Jennifer</rptOwnerName></reportingOwnerId>
                <reportingOwnerRelationship>
                  <isDirector>false</isDirector><isOfficer>true</isOfficer><isTenPercentOwner>false</isTenPercentOwner>
                  <isOther>false</isOther><officerTitle>SVP, GC and Government Affairs</officerTitle>
                </reportingOwnerRelationship>
              </reportingOwner>
              <aff10b5One>true</aff10b5One>
              <nonDerivativeTable>
                <nonDerivativeTransaction>
                  <securityTitle><value>Common Stock</value></securityTitle>
                  <transactionDate><value>2026-11-10</value></transactionDate>
                  <transactionCoding><transactionFormType>4</transactionFormType><transactionCode>S</transactionCode></transactionCoding>
                  <transactionAmounts>
                    <transactionShares><value>2399</value></transactionShares>
                    <transactionPricePerShare><value>351.36</value></transactionPricePerShare>
                    <transactionAcquiredDisposedCode><value>D</value></transactionAcquiredDisposedCode>
                  </transactionAmounts>
                  <postTransactionAmounts><sharesOwnedFollowingTransaction><value>27598</value></sharesOwnedFollowingTransaction></postTransactionAmounts>
                  <ownershipNature><directOrIndirectOwnership><value>D</value></directOrIndirectOwnership></ownershipNature>
                </nonDerivativeTransaction>
              </nonDerivativeTable>
            </ownershipDocument>""";
}
