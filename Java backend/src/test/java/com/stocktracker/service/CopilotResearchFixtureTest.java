package com.stocktracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.dto.ApiDto;
import com.stocktracker.dto.CopilotDto;
import com.stocktracker.model.StockPrice;
import com.stocktracker.model.SymbolCoverage;
import com.stocktracker.repository.BacktestResultRepository;
import com.stocktracker.repository.StockPriceRepository;
import com.stocktracker.repository.StrategyRepository;
import com.stocktracker.repository.SymbolCoverageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 baseline: drives {@link CopilotService#runResearch} end to end against
 * a real Postgres (Testcontainers) with deterministic synthetic 1-minute bars,
 * a scripted LLM and a Jupyter-executor double. Nothing touches the network.
 *
 * The executor double stands in for {@code k.export()}: it upserts the strategy
 * plus its exported run through {@link StrategyService#saveStrategy} (the same
 * path {@code POST /api/strategies} uses) and prints the SDK's confirmation line.
 *
 * The class is non-transactional on purpose: CopilotService runs the pipeline
 * on its own pool thread, which cannot see rows held in a test transaction.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CopilotResearchFixtureTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final String SYMBOL   = "FIXT";
    static final String NAME     = "fixture_rsi";
    static final Instant START   = Instant.parse("2024-01-02T14:30:00Z");
    static final int     BARS    = 390;                     // one RTH session
    static final Instant END     = START.plus(BARS - 1, ChronoUnit.MINUTES);
    static final String EXPLANATION = "## Summary\n- Fixture explanation.";

    @Autowired StrategyRepository       strategyRepo;
    @Autowired BacktestResultRepository resultRepo;
    @Autowired StockPriceRepository     priceRepo;
    @Autowired SymbolCoverageRepository coverageRepo;
    @Autowired DataSource               dataSource;

    JdbcTemplate      jdbc;
    StrategyService   strategyService;
    StockPriceService priceService;
    ObjectMapper      mapper;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM backtest_results");
        jdbc.update("DELETE FROM strategies");
        priceRepo.deleteAllInBatch();
        coverageRepo.deleteAllInBatch();

        // Deterministic sine-wave closes: period 60 bars, amplitude 5 around 100,
        // so a 14-period RSI swings through both 30 and 70 many times.
        List<StockPrice> bars = new ArrayList<>();
        for (int i = 0; i < BARS; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + 5 * Math.sin(2 * Math.PI * i / 60.0))
                    .setScale(4, RoundingMode.HALF_UP);
            bars.add(StockPrice.builder()
                    .time(START.plus(i, ChronoUnit.MINUTES)).symbol(SYMBOL)
                    .open(close).high(close.add(BigDecimal.valueOf(0.05)))
                    .low(close.subtract(BigDecimal.valueOf(0.05))).close(close)
                    .volume(1000L + i).build());
        }
        priceRepo.saveAll(bars);
        // Coverage spanning the requested range so getRange never calls Alpaca (null here).
        coverageRepo.save(SymbolCoverage.builder().symbol(SYMBOL)
                .fromTime(Instant.parse("2024-01-01T00:00:00Z"))
                .toTime(Instant.parse("2024-01-04T00:00:00Z")).build());

        strategyService = new StrategyService(strategyRepo, resultRepo, priceRepo, new BacktestEngineService());
        priceService    = new StockPriceService(priceRepo, coverageRepo, null, null, jdbc);
        // Spring Boot's configured mapper shape (JSR-310 on, ISO dates) — see AGENTS.md.
        mapper          = Jackson2ObjectMapperBuilder.json().build();
    }

    // ── Test doubles ──────────────────────────────────────────────────────────

    /** Returns canned OpenAI-shaped turns in order and records every call. */
    static final class ScriptedLLM implements LLMClient {
        private final Deque<JsonNode> chatTurns = new ArrayDeque<>();
        final List<List<Map<String, Object>>> chatCalls = new CopyOnWriteArrayList<>();
        final List<String[]> completeCalls = new CopyOnWriteArrayList<>();
        String explanation = EXPLANATION;

        ScriptedLLM turn(JsonNode n) { chatTurns.add(n); return this; }

        @Override public boolean isConfigured() { return true; }
        @Override public String model() { return "scripted"; }

        @Override
        public synchronized JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int maxTokens) {
            chatCalls.add(new ArrayList<>(messages));
            if (chatTurns.isEmpty()) throw new IllegalStateException("ScriptedLLM ran out of turns");
            return chatTurns.poll();
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, int maxTokens) {
            completeCalls.add(new String[] {systemPrompt, userPrompt});
            return explanation;
        }
    }

    /** Executor double — never touches HTTP (the base constructor only builds a RestClient). */
    static final class FakeJupyter extends JupyterExecutionService {
        final List<String> executed = new CopyOnWriteArrayList<>();
        private final Function<String, ExecutionResult> behaviour;

        FakeJupyter(Function<String, ExecutionResult> behaviour) {
            super("http://executor.invalid");
            this.behaviour = behaviour;
        }

        @Override
        public ExecutionResult execute(String pythonCode) {
            executed.add(pythonCode);
            return behaviour.apply(pythonCode);
        }
    }

    /** Simulates what ananke-sdk's k.export() does: POST /api/strategies, then print. */
    Function<String, JupyterExecutionService.ExecutionResult> exportingAs(String type) {
        return code -> {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", type);
            def.put("rsi_period", 14);
            def.put("long_entry_rsi_below", 30);
            def.put("long_exit_rsi_above", 70);
            def.put("quantity", 1);

            Map<String, Object> results = new LinkedHashMap<>();
            results.put("total_trades", 3);
            results.put("winning_trades", 2);
            results.put("losing_trades", 1);
            results.put("win_rate", 66.67);
            results.put("total_pnl", 4.2);
            results.put("trades", List.of());
            results.put("equity_curve", List.of());

            ApiDto.StrategyRequest req = new ApiDto.StrategyRequest();
            req.setName(NAME);
            req.setDescription("exported by fixture");
            req.setDefinition(def);
            req.setResults(results);
            req.setSymbol(SYMBOL);
            // pandas str(Timestamp) shape, as the real SDK sends it.
            req.setFromTs("2024-01-02 14:30:00+00:00");
            req.setToTs("2024-01-02 20:59:00+00:00");
            req.setInterval("1Min");
            strategyService.saveStrategy(req);

            return JupyterExecutionService.ExecutionResult.success(
                    "running backtest...\nKairos '" + NAME + "' exported successfully\n", "");
        };
    }

    /** Captures each JSON data frame and signals when the service completes the stream. */
    static final class CapturingEmitter extends SseEmitter {
        final List<JsonNode> events = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        private final ObjectMapper om = new ObjectMapper();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            for (var part : builder.build()) {
                if (MediaType.APPLICATION_JSON.equals(part.getMediaType())) {
                    events.add(om.readTree(String.valueOf(part.getData())));
                }
            }
        }

        @Override
        public void complete() {
            completed.countDown();
        }

        List<String> types() {
            return events.stream().map(e -> e.path("type").asText()).toList();
        }

        JsonNode first(String type) {
            return events.stream().filter(e -> type.equals(e.path("type").asText())).findFirst().orElseThrow();
        }
    }

    // ── Canned LLM turns ──────────────────────────────────────────────────────

    JsonNode toolCallTurn() throws Exception {
        return mapper.readTree("""
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
                  "tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_price_data",
                    "arguments":"{\\"symbol\\":\\"fixt\\",\\"from\\":\\"2024-01-02\\",\\"to\\":\\"2024-01-03\\"}"}}]}}]}
                """);
    }

    static final String CODE = """
            from ananke import get_data, Kairos
            df = get_data('FIXT', '2024-01-02', '2024-01-03', '1min',
                          columns=['open','high','low','close','volume','symbol'])
            k = Kairos(df, name='fixture_rsi', params={'type': 'rsi_crossover'})
            k.run()
            k.export()""";

    JsonNode finalTurn(String content) {
        return mapper.valueToTree(Map.of("choices", List.of(Map.of(
                "finish_reason", "stop",
                "message", Map.of("role", "assistant", "content", content)))));
    }

    JsonNode codeAnswer() {
        return finalTurn("RSI mean reversion on FIXT.\n\n```python\n" + CODE + "\n```\n");
    }

    CapturingEmitter run(ScriptedLLM llm, FakeJupyter jupyter) throws Exception {
        CopilotService svc = new CopilotService(llm, jupyter, strategyService, null, null, priceService, mapper);
        CapturingEmitter emitter = new CapturingEmitter();
        svc.runResearch("Build an RSI strategy on FIXT", List.of(), emitter);
        assertThat(emitter.completed.await(60, TimeUnit.SECONDS)).as("stream completed").isTrue();
        return emitter;
    }

    List<Map<String, Object>> resultRows() {
        return jdbc.queryForList("""
                SELECT r.id, r.symbol, r.interval, r.total_trades, r.from_ts, r.to_ts, r.copilot_explanation
                FROM backtest_results r JOIN strategies s ON s.id = r.strategy_id
                WHERE s.name = ? ORDER BY r.id""", NAME);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void happyPath_rsiStrategyIsReRunByEngineAndExplained() throws Exception {
        ScriptedLLM llm = new ScriptedLLM().turn(toolCallTurn()).turn(codeAnswer());
        FakeJupyter jupyter = new FakeJupyter(exportingAs("rsi_crossover"));

        CapturingEmitter emitter = run(llm, jupyter);

        // Event sequence (the SSE contract the frontend renders).
        assertThat(emitter.types()).containsExactly(
                "status", "status", "code", "status", "status", "backtest", "status", "explanation", "done");
        assertThat(emitter.events.stream().filter(e -> "status".equals(e.path("type").asText()))
                .map(e -> e.path("message").asText()).toList())
                .containsExactly("Analysing request…", "Fetching fixt price data…",
                        "Executing strategy in Jupyter…", "Running backtest…", "Analysing results…");

        JsonNode code = emitter.first("code");
        assertThat(code.path("message").asText()).isEqualTo("Generated strategy");
        assertThat(code.path("payload").asText()).isEqualTo(CODE.trim());
        assertThat(jupyter.executed).containsExactly(CODE.trim());

        // Tool loop: second chat call carries the assistant echo + the tool result.
        assertThat(llm.chatCalls).hasSize(2);
        List<Map<String, Object>> second = llm.chatCalls.get(1);
        assertThat(second.get(0).get("role")).isEqualTo("system");
        assertThat(second.get(1)).containsEntry("role", "user").containsEntry("content", "Build an RSI strategy on FIXT");
        assertThat(second.get(2).get("role")).isEqualTo("assistant");
        assertThat(second.get(3)).containsEntry("role", "tool").containsEntry("tool_call_id", "call_1");
        JsonNode toolResult = mapper.readTree((String) second.get(3).get("content"));
        assertThat(toolResult.path("symbol").asText()).isEqualTo("FIXT");
        assertThat(toolResult.path("bar_count").asInt()).isEqualTo(BARS);
        assertThat(toolResult.path("interval").asText()).isEqualTo("1Min");
        assertThat(toolResult.path("sample")).hasSize(5);

        JsonNode backtest = emitter.first("backtest");
        assertThat(backtest.path("message").asText()).isEqualTo("Backtest complete");
        assertThat(backtest.path("payload").path("symbol").asText()).isEqualTo(SYMBOL);
        int engineTrades = backtest.path("payload").path("totalTrades").asInt();
        assertThat(engineTrades).as("engine produced trades on the sine fixture").isPositive();

        JsonNode explanation = emitter.first("explanation");
        assertThat(explanation.path("message").asText()).isEqualTo("Analysis complete");
        assertThat(explanation.path("payload").asText()).isEqualTo(EXPLANATION);
        assertThat(llm.completeCalls).hasSize(1);
        assertThat(llm.completeCalls.get(0)[1]).contains("Strategy: fixture_rsi").contains("Symbol: FIXT");

        JsonNode done = emitter.first("done");
        assertThat(done.path("message").asText()).isEqualTo("Done");
        assertThat(done.path("payload").path("strategyName").asText()).isEqualTo(NAME);
        assertThat(done.path("payload").path("symbol").asText()).isEqualTo(SYMBOL);
        assertThat(done.path("payload").path("usedExportedResult").asBoolean()).isFalse();
        long resultId = done.path("payload").path("backtestResultId").asLong();
        assertThat(backtest.path("payload").path("id").asLong()).isEqualTo(resultId);

        // Persisted state: strategy + exported row + engine row (explanation on the engine row).
        assertThat(strategyRepo.findByName(NAME)).get()
                .satisfies(s -> assertThat(s.getDefinition()).containsEntry("type", "rsi_crossover"));
        List<Map<String, Object>> rows = resultRows();
        assertThat(rows).hasSize(2);
        Map<String, Object> exported = rows.get(0);
        Map<String, Object> engine   = rows.get(1);
        assertThat(((Number) exported.get("total_trades")).intValue()).isEqualTo(3);
        assertThat(exported.get("copilot_explanation")).isNull();
        assertThat(((Number) engine.get("id")).longValue()).isEqualTo(resultId);
        assertThat(engine.get("interval")).isEqualTo("1Min");
        assertThat(((Number) engine.get("total_trades")).intValue()).isEqualTo(engineTrades);
        assertThat(engine.get("copilot_explanation")).isEqualTo(EXPLANATION);
        assertThat(strategyService.latestResult(NAME)).get()
                .satisfies(r -> {
                    assertThat(r.getId()).isEqualTo(resultId);
                    assertThat(r.getFromTs()).isEqualTo(START);
                    assertThat(r.getToTs()).isEqualTo(END);
                });
    }

    @Test
    void nonRsiEmaDefinition_fallsBackToExportedRun() throws Exception {
        ScriptedLLM llm = new ScriptedLLM().turn(codeAnswer());
        FakeJupyter jupyter = new FakeJupyter(exportingAs("custom_ml"));

        CapturingEmitter emitter = run(llm, jupyter);

        assertThat(emitter.types()).containsExactly(
                "status", "code", "status", "status", "backtest", "status", "explanation", "done");
        List<Map<String, Object>> rows = resultRows();
        assertThat(rows).as("engine refused, so no second row").hasSize(1);
        long exportedId = ((Number) rows.get(0).get("id")).longValue();

        JsonNode done = emitter.first("done").path("payload");
        assertThat(done.path("usedExportedResult").asBoolean()).isTrue();
        assertThat(done.path("backtestResultId").asLong()).isEqualTo(exportedId);
        assertThat(emitter.first("backtest").path("payload").path("totalTrades").asInt()).isEqualTo(3);
        // The explanation lands on the exported row instead.
        assertThat(rows.get(0).get("copilot_explanation")).isEqualTo(EXPLANATION);
    }

    @Test
    void noCodeFence_emitsErrorAndNeverExecutes() throws Exception {
        ScriptedLLM llm = new ScriptedLLM().turn(finalTurn("I would use RSI, but here is no code."));
        FakeJupyter jupyter = new FakeJupyter(exportingAs("rsi_crossover"));

        CapturingEmitter emitter = run(llm, jupyter);

        assertThat(emitter.types()).containsExactly("status", "error");
        assertThat(emitter.first("error").path("message").asText())
                .isEqualTo("The model did not return a Python code block. Try rephrasing the request.");
        assertThat(emitter.first("error").path("payload").isNull()).isTrue();
        assertThat(jupyter.executed).isEmpty();
        assertThat(strategyRepo.findByName(NAME)).isEmpty();
    }

    @Test
    void executorFailure_emitsStderrTailAsError() throws Exception {
        ScriptedLLM llm = new ScriptedLLM().turn(codeAnswer());
        FakeJupyter jupyter = new FakeJupyter(code -> new JupyterExecutionService.ExecutionResult(
                false, "partial output\n", "Traceback (most recent call last):\nNameError: name 'x' is not defined\n",
                "Python exited with code 1"));

        CapturingEmitter emitter = run(llm, jupyter);

        assertThat(emitter.types()).containsExactly("status", "code", "status", "error");
        assertThat(emitter.first("error").path("message").asText())
                .isEqualTo("Strategy code failed:\nTraceback (most recent call last):\nNameError: name 'x' is not defined\n");
        assertThat(llm.completeCalls).isEmpty();
        assertThat(resultRows()).isEmpty();
    }

    @Test
    void executorTransportFailure_withBlankStderr_usesErrorField() throws Exception {
        ScriptedLLM llm = new ScriptedLLM().turn(codeAnswer());
        FakeJupyter jupyter = new FakeJupyter(code -> JupyterExecutionService.ExecutionResult.error(
                "Executor returned HTTP 504: Execution timed out after 120s"));

        CapturingEmitter emitter = run(llm, jupyter);

        assertThat(emitter.types()).containsExactly("status", "code", "status", "error");
        assertThat(emitter.first("error").path("message").asText())
                .isEqualTo("Strategy code failed:\nExecutor returned HTTP 504: Execution timed out after 120s");
    }

    @Test
    void scriptRanButNothingExported_emitsError() throws Exception {
        ScriptedLLM llm = new ScriptedLLM().turn(codeAnswer());
        // Succeeds, prints nothing useful, saves nothing. Name falls back to name= in code,
        // but no such strategy exists → error.
        FakeJupyter jupyter = new FakeJupyter(code ->
                JupyterExecutionService.ExecutionResult.success("done\n", ""));

        CapturingEmitter emitter = run(llm, jupyter);

        assertThat(emitter.types()).containsExactly("status", "code", "status", "error");
        assertThat(emitter.first("error").path("message").asText())
                .startsWith("The script ran but no strategy was exported. Ensure it ends with k.run() then k.export().")
                .endsWith("Output:\ndone\n");
    }

    @Test
    void llmUnavailableMidRun_isReportedAsErrorEvent() throws Exception {
        LLMClient down = new ResolvingLLMClient(new ResolvingLLMClientTest.StubClient("a", false),
                new ResolvingLLMClientTest.StubClient("b", false));
        FakeJupyter jupyter = new FakeJupyter(exportingAs("rsi_crossover"));
        CopilotService svc = new CopilotService(down, jupyter, strategyService, null, null, priceService, mapper);
        CapturingEmitter emitter = new CapturingEmitter();

        svc.runResearch("x", null, emitter);

        assertThat(emitter.completed.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter.types()).containsExactly("status", "error");
        assertThat(emitter.first("error").path("message").asText()).contains("no LLM provider has credentials");
    }

    @Test
    void strategyNameParsing_prefersSdkConfirmationOverCodeLiteral() {
        assertThat(CopilotService.parseStrategyName(
                "Kairos 'from_stdout' exported successfully", "k = Kairos(df, name='from_code')"))
                .isEqualTo("from_stdout");
        assertThat(CopilotService.parseStrategyName("no confirmation", "k = Kairos(df, name=\"from_code\")"))
                .isEqualTo("from_code");
        assertThat(CopilotService.parseStrategyName(null, "no name here")).isNull();
        assertThat(CopilotService.extractCode("a\n```py\nprint(1)\n```\n```python\nprint(2)\n```"))
                .as("first fence wins").isEqualTo("print(1)");
        assertThat(CopilotService.extractCode("```\nbare()\n```")).isEqualTo("bare()");
        assertThat(CopilotService.extractCode("no fence")).isNull();
    }

    // Sanity: CopilotDto.ResearchEvent factory shapes, frozen for the frontend.
    @Test
    void researchEventFactories() {
        assertThat(CopilotDto.ResearchEvent.token("t").getMessage()).isNull();
        assertThat(CopilotDto.ResearchEvent.token("t").getPayload()).isEqualTo("t");
        assertThat(CopilotDto.ResearchEvent.done(null).getMessage()).isEqualTo("Done");
    }
}
