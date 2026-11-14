package com.stocktracker.packs.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.agent.AgentCapabilities;
import com.stocktracker.agent.AgentProfile;
import com.stocktracker.agent.AgentTool;
import com.stocktracker.agent.AgentToolLoop;
import com.stocktracker.agent.AgentToolbox;
import com.stocktracker.packs.Pack;
import com.stocktracker.packs.PackController;
import com.stocktracker.packs.PackService;
import com.stocktracker.packs.compute.SshComputeProvider;
import com.stocktracker.service.LLMClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Pack operations agent (Packs build doc §9): answers questions about the user's Packs
 * and plans GPU/cloud setups, using the shared agent kit — the compute/cost/identity cloud
 * skills via {@code load_skill}, and cloud action tools only once a cloud is linked (§7).
 *
 * <p>Its own tools are read-only. It cannot deploy, stop or remove a Pack, register a
 * machine, or create anything billable; those stay explicit user actions on /packs.
 */
@Service
public class PackOpsAgent {

    static final int MAX_TOOL_ITERATIONS = 8;
    static final int MAX_TOKENS          = 4096;
    static final int LOG_TAIL_LINES      = 40;

    static final String SYSTEM_PROMPT = """
            You are the Ananke Pack agent. A Pack is a Hugging Face model pinned to one commit and
            served with vLLM on a GPU machine the user connects over SSH; a running Pack can be the
            copilot's AI provider. Help the user understand their Packs' status and failures, size
            GPUs for a model, and plan cloud GPU setups on AWS, Google Cloud or Azure.

            Use list_packs / get_pack for facts about the user's Packs rather than guessing. You
            cannot deploy, stop or remove Packs, register machines or create cloud resources: tell
            the user which button on the Packs page does it. Cloud account linking and one-click
            provisioning are not available yet; machines are connected over SSH.
            """;

    public record ToolCallRecord(String tool, String status) { }

    public record Reply(String answer, List<ToolCallRecord> toolCalls, List<String> tools) { }

    public record Turn(String role, String content) { }

    private final LLMClient llm;
    private final PackService packs;
    private final SshComputeProvider ssh;
    private final AgentCapabilities agents;
    private final ObjectMapper mapper;

    public PackOpsAgent(LLMClient llm, PackService packs, SshComputeProvider ssh,
                        AgentCapabilities agents, ObjectMapper mapper) {
        this.llm    = llm;
        this.packs  = packs;
        this.ssh    = ssh;
        this.agents = agents;
        this.mapper = mapper;
    }

    public Reply chat(List<Turn> history, Long packId) {
        if (!llm.isConfigured()) {
            throw new LLMClient.LLMUnavailableException(
                    "No AI provider is configured. Add one in Settings → AI provider.");
        }
        AgentToolbox tools = toolbox(packId);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", agents.systemPrompt(SYSTEM_PROMPT, AgentProfile.PACK_OPS)));
        if (history != null) {
            for (Turn t : history) {
                if (t == null || t.content() == null) continue;
                if (!"user".equals(t.role()) && !"assistant".equals(t.role())) continue;  // ours is the only system turn
                messages.add(msg(t.role(), t.content()));
            }
        }
        List<ToolCallRecord> calls = new ArrayList<>();
        String answer = AgentToolLoop.run(llm, mapper, messages, tools, MAX_TOOL_ITERATIONS, MAX_TOKENS,
                (name, args, status) -> calls.add(new ToolCallRecord(name, status)));
        return new Reply(answer, calls, tools.names());
    }

    AgentToolbox toolbox(Long packId) {
        return agents.toolbox(AgentProfile.PACK_OPS, packId, ownTools());
    }

    List<AgentTool> ownTools() {
        Map<String, Object> noArgs = Map.of("type", "object", "properties", Map.of());
        return List.of(
                AgentTool.of("list_packs", "List the user's Packs with status and any failure code.", noArgs,
                        args -> packs.list().stream().map(this::summary).toList(),
                        args -> "Listing Packs…"),
                AgentTool.of("get_pack",
                        "Details for one Pack: model profile, launch settings, endpoint health, failure and the "
                        + "tail of its (redacted) deploy log.",
                        Map.of("type", "object",
                               "properties", Map.of("id", Map.of("type", "integer", "description", "Pack id")),
                               "required", List.of("id")),
                        args -> detail(packs.get(args.path("id").asLong())),
                        args -> "Reading Pack " + args.path("id").asText("") + "…"),
                AgentTool.of("list_compute_providers",
                        "Where GPU machines can come from today, and which providers are not available yet.", noArgs,
                        args -> PackController.providerList(ssh),
                        args -> "Checking compute providers…"));
    }

    private Map<String, Object> summary(Pack p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("repo", p.getHfRepo());
        m.put("revision", p.getHfRevision());
        m.put("status", p.getStatus().name());
        m.put("phase", p.getPhase());
        m.put("failureCode", p.getFailureCode());
        return m;
    }

    private Map<String, Object> detail(Pack p) {
        Map<String, Object> m = summary(p);
        Map<String, Object> profile = p.getProfile() == null ? Map.of() : p.getProfile();
        for (String k : List.of("compatibility", "architectures", "parameters", "estimatedWeightsGiB",
                "contextLength", "torchDtype", "quantization", "suggestedToolParser", "notes", "blockers")) {
            m.put(k, profile.get(k));
        }
        m.put("launch", p.getLaunchConfig());
        m.put("failureDetail", p.getFailureDetail());
        m.put("lastHealth", p.getLastHealth());
        m.put("toolCalling", p.getEndpoint() == null ? null : p.getEndpoint().get("toolCalling"));
        m.put("deployLogTail", tail(p.getDeployLog(), LOG_TAIL_LINES));
        return m;
    }

    private static String tail(String log, int lines) {
        if (log == null || log.isEmpty()) return "";
        String[] all = log.split("\n");
        int from = Math.max(0, all.length - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
