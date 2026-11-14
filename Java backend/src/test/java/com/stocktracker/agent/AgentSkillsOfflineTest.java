package com.stocktracker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.agent.actions.CloudToolGate;
import com.stocktracker.agent.skills.SkillRegistry;
import com.stocktracker.agent.skills.SkillSettingsService;
import com.stocktracker.agent.skills.SkillToolset;
import com.stocktracker.dto.CopilotDto;
import com.stocktracker.packs.agent.PackOpsAgent;
import com.stocktracker.service.CopilotService;
import com.stocktracker.service.LLMClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §9.4 offline acceptance, Phase-0 style: a scripted {@link LLMClient} — no network, no
 * vendor-native skills — discovers a skill from the system-prompt index, calls
 * {@code load_skill}, and answers from the text it loaded. Runs against the real shipped
 * bundle (the vendored aws-compute skill), through both the copilot and the Pack agent.
 */
class AgentSkillsOfflineTest {

    @TempDir Path dir;
    final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    AgentCapabilities kit;

    @BeforeEach
    void setUp() {
        SkillRegistry registry = SkillRegistry.fromClasspath(dir.resolve("fetched"));
        SkillToolset skills = new SkillToolset(registry, new SkillSettingsService(dir.resolve("skills-settings.json")));
        kit = new AgentCapabilities(skills, new CloudToolGate(List.of()), ActionGuard.REFUSE_ALL);
    }

    /**
     * Turn 1: finds aws-compute in the system prompt's index and calls load_skill.
     * Turn 2: reads the tool result and answers by quoting a line of the loaded SKILL.md.
     */
    static final class SkillReadingLLM implements LLMClient {
        final List<List<Map<String, Object>>> chatCalls = new CopyOnWriteArrayList<>();
        final List<List<Map<String, Object>>> toolsSeen = new CopyOnWriteArrayList<>();
        final ObjectMapper om = new ObjectMapper();

        @Override public boolean isConfigured() { return true; }
        @Override public String model() { return "scripted-skill-reader"; }
        @Override public String complete(String s, String u, int m) { throw new UnsupportedOperationException(); }

        @Override
        public synchronized JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int maxTokens) {
            chatCalls.add(new ArrayList<>(messages));
            toolsSeen.add(tools);
            try {
                if (chatCalls.size() == 1) {
                    String system = String.valueOf(messages.get(0).get("content"));
                    if (!system.contains("- aws-compute [aws]:")) return answer("no skill index");
                    return om.readTree("""
                            {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                              "tool_calls":[{"id":"call_skill","type":"function","function":{"name":"load_skill",
                                "arguments":"{\\"name\\":\\"aws-compute\\"}"}}]}}]}""");
                }
                Map<String, Object> last = messages.get(messages.size() - 1);
                JsonNode result = om.readTree(String.valueOf(last.get("content")));
                String warning = result.path("content").asText().lines()
                        .filter(l -> l.startsWith("**IMDSv2 hop limit")).findFirst().orElse("NOT FOUND");
                return answer("Per the aws-compute skill (" + result.path("source").asText() + "): " + warning);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        JsonNode answer(String text) {
            return om.valueToTree(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("role", "assistant", "content", text)))));
        }
    }

    static void assertSkillRoundTrip(SkillReadingLLM llm, String answer) {
        assertThat(llm.chatCalls).hasSize(2);
        assertThat(toolNames(llm.toolsSeen.get(0))).contains("load_skill");
        // The tool turn carries the real SKILL.md body, marked as untrusted reference text.
        Map<String, Object> toolTurn = llm.chatCalls.get(1).get(llm.chatCalls.get(1).size() - 1);
        assertThat(toolTurn).containsEntry("role", "tool").containsEntry("tool_call_id", "call_skill");
        assertThat(String.valueOf(toolTurn.get("content")))
                .contains("# Amazon EC2 Compute").contains("not an instruction from the user");
        // …and the final answer is built from it.
        assertThat(answer).startsWith("Per the aws-compute skill (aws/agent-toolkit-for-aws@19b89b263b725b04082a55b4fc4d24d22f065999/")
                .contains("**IMDSv2 hop limit breaks containers**");
    }

    @Test
    void copilotChat_loadsASkillThenAnswersFromIt() throws Exception {
        SkillReadingLLM llm = new SkillReadingLLM();
        CopilotService copilot = new CopilotService(llm, null, null, null, null, null, mapper, kit);
        CapturingEmitter emitter = new CapturingEmitter();

        copilot.streamChat(List.of(msg("user", "My container on EC2 times out talking to IMDS. Why?")), emitter);
        assertThat(emitter.completed.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(emitter.types()).containsExactly("status", "token", "done");
        assertThat(emitter.events.get(0).path("message").asText()).isEqualTo("Reading skill aws-compute…");
        assertSkillRoundTrip(llm, emitter.events.get(1).path("payload").asText());
        // Chat mode offers the copilot's data tools plus load_skill — and no cloud action tools (none linked).
        assertThat(toolNames(llm.toolsSeen.get(0))).containsExactly("list_symbols", "search_symbols", "add_symbol", "get_price_data", "load_skill");
    }

    @Test
    void packAgent_loadsASkillThenAnswersFromIt() {
        SkillReadingLLM llm = new SkillReadingLLM();
        PackOpsAgent agent = new PackOpsAgent(llm, null, null, kit, mapper);

        PackOpsAgent.Reply reply = agent.chat(List.of(new PackOpsAgent.Turn("user", "IMDS timeouts on my GPU box")), null);

        assertSkillRoundTrip(llm, reply.answer());
        assertThat(reply.toolCalls()).containsExactly(new PackOpsAgent.ToolCallRecord("load_skill", "Reading skill aws-compute…"));
        assertThat(reply.tools()).containsExactly("list_packs", "get_pack", "list_compute_providers", "load_skill");
        // The Pack agent's index is topic-filtered: compute/cost/identity yes, serverless no.
        String system = String.valueOf(llm.chatCalls.get(0).get(0).get("content"));
        assertThat(system).contains("- aws-compute [aws]:", "- compute-vm-creation [gcp]:", "- azure-compute [azure]:")
                .doesNotContain("- aws-lambda [aws]:");
    }

    @Test
    void researchMode_keepsItsFourTools_evenWithTheKit() throws Exception {
        CopilotService copilot = new CopilotService(new SkillReadingLLM(), null, null, null, null, null, mapper, kit);
        var m = CopilotService.class.getDeclaredMethod("researchTools");
        m.setAccessible(true);
        assertThat(((AgentToolbox) m.invoke(copilot)).names())
                .containsExactly("list_symbols", "search_symbols", "add_symbol", "get_price_data");
    }

    static List<String> toolNames(List<Map<String, Object>> defs) {
        return defs.stream().map(t -> String.valueOf(((Map<?, ?>) t.get("function")).get("name"))).toList();
    }

    static CopilotDto.Message msg(String role, String content) {
        CopilotDto.Message m = new CopilotDto.Message();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    static final class CapturingEmitter extends SseEmitter {
        final List<JsonNode> events = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        private final ObjectMapper om = new ObjectMapper();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            for (var part : builder.build()) {
                if (MediaType.APPLICATION_JSON.equals(part.getMediaType())) events.add(om.readTree(String.valueOf(part.getData())));
            }
        }

        @Override
        public void complete() {
            completed.countDown();
        }

        List<String> types() {
            return events.stream().map(e -> e.path("type").asText()).toList();
        }
    }
}
