package com.stocktracker.packs.cloud.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.agent.AgentTool;
import com.stocktracker.agent.actions.CloudActionToolSource;
import com.stocktracker.packs.ComputeTarget;
import com.stocktracker.packs.PackException;
import com.stocktracker.packs.cloud.CloudAccount;
import com.stocktracker.packs.cloud.EgressIp;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS tools for Ananke agents (Packs build doc §9.3), offered only while an AWS account is
 * linked. Fixed contracts on top of {@link AwsMachines}: the model chooses arguments from
 * closed sets, never free-form AWS calls. Two tools are billable and therefore never run in
 * the tool loop — a person confirms the exact estimate in the UI first:
 * {@code aws_provision_gpu_vm} (the machine is created from the estimate the person saw) and
 * {@code aws_start_vm}. There is deliberately no stop/terminate tool for agents.
 * The AWS MCP Server's read-only documentation tools are appended when reachable.
 */
@Slf4j
public class AwsActionToolSource implements CloudActionToolSource {

    private final AwsAccounts accounts;
    private final AwsMachines machines;
    private final EgressIp egress;
    private final AwsMcpKnowledge mcp;
    /** canonical provision args → the estimate the person is being asked to confirm. */
    private final Map<String, String> pendingEstimates = new ConcurrentHashMap<>();

    public AwsActionToolSource(AwsAccounts accounts, AwsMachines machines, EgressIp egress, AwsMcpKnowledge mcp) {
        this.accounts = accounts;
        this.machines = machines;
        this.egress   = egress;
        this.mcp      = mcp;
    }

    @Override public String cloud() { return "aws"; }

    @Override public boolean isLinked() { return accounts.anyLinked(); }

    CloudAccount account() {
        return accounts.list().stream().filter(a -> a.getStatus() == CloudAccount.Status.LINKED).findFirst()
                .orElseThrow(() -> new PackException(PackException.Code.CLOUD_NOT_LINKED, "No AWS account is linked."));
    }

    @Override
    public List<AgentTool> tools() {
        List<AgentTool> out = new ArrayList<>(List.of(listTypes(), estimate(), listMachines(), provision(), start()));
        if (mcp != null) {
            try {
                CloudAccount a = account();
                for (AwsMcpKnowledge.McpTool t : mcp.tools(() -> accounts.session(a))) out.add(mcpTool(t));
            } catch (RuntimeException e) {
                log.info("AWS MCP knowledge tools unavailable: {}", e.getMessage());
            }
        }
        return out;
    }

    // ── read-only ────────────────────────────────────────────────────────────

    private AgentTool listTypes() {
        return tool("aws_list_gpu_types", "GPU instance types Ananke can create, with GPU model, count and VRAM.",
                Map.of("type", "object", "properties", Map.of()), false,
                args -> AwsMachines.CATALOG, args -> "Listing AWS GPU types…");
    }

    private static Map<String, Object> vmSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("region", Map.of("type", "string", "description", "AWS region code, e.g. us-east-1"));
        p.put("instanceType", Map.of("type", "string", "enum", AwsMachines.CATALOG.stream().map(AwsMachines.InstanceTypeInfo::type).toList()));
        p.put("diskGiB", Map.of("type", "integer", "minimum", 100, "maximum", 2000, "description", "gp3 root disk; default 150"));
        p.put("idleStopMinutes", Map.of("type", "integer", "description", "Auto-stop after idle minutes (0 = never, else 15–1440); default 60"));
        p.put("packId", Map.of("type", "integer", "description", "Optional Pack this VM is for (removing it terminates the VM)"));
        return Map.of("type", "object", "properties", p, "required", List.of("region", "instanceType"));
    }

    AwsMachines.ProvisionRequest request(JsonNode a) {
        String cidr = egress.detect().map(ip -> ip + "/32").orElseThrow(() -> new PackException(PackException.Code.INVALID_INPUT,
                "Couldn't detect the backend's public IP for the SSH rule; ask the user to create the machine from Machines → Cloud."));
        return new AwsMachines.ProvisionRequest(null, a.path("region").asText(null), a.path("instanceType").asText(null),
                a.hasNonNull("diskGiB") ? a.path("diskGiB").asInt() : null, cidr,
                a.hasNonNull("idleStopMinutes") ? a.path("idleStopMinutes").asInt() : null,
                a.hasNonNull("packId") ? a.path("packId").asLong() : null, null);
    }

    private AgentTool estimate() {
        return tool("aws_estimate_gpu_vm", "Free cost estimate for a GPU VM from live AWS prices. Creates nothing.",
                vmSchema(), false, args -> summary(machines.estimate(account().getId(), request(args))),
                args -> "Pricing " + args.path("instanceType").asText("") + " in " + args.path("region").asText("") + "…");
    }

    private AgentTool listMachines() {
        return tool("aws_list_machines", "The AWS GPU machines Ananke manages, with status and estimate.",
                Map.of("type", "object", "properties", Map.of()), false,
                args -> machines.machines(account().getId()).stream().map(AwsActionToolSource::machine).toList(),
                args -> "Listing AWS machines…");
    }

    static Map<String, Object> machine(ComputeTarget t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("targetId", t.getId());
        m.put("label", t.getLabel());
        m.put("status", t.getStatus());
        m.put("region", t.getCloudRegion());
        Map<String, Object> meta = t.getCloudMeta() == null ? Map.of() : t.getCloudMeta();
        m.put("instanceType", meta.get("instanceType"));
        m.put("phase", meta.get("phase"));
        m.put("estimate", meta.get("estimate"));
        m.put("idleStopMinutes", t.getIdleStopMinutes());
        m.put("ownerPackId", t.getOwnerPackId());
        return m;
    }

    static Map<String, Object> summary(AwsMachines.Estimate e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceType", e.request().instanceType());
        m.put("region", e.request().region());
        m.put("hourlyUsd", e.hourlyUsd());
        m.put("diskMonthlyUsd", e.diskMonthlyUsd());
        m.put("alwaysOnMonthlyUsd", e.alwaysOnMonthlyUsd());
        m.put("notes", e.notes());
        m.put("source", e.source());
        return m;
    }

    // ── billable (person confirms in the UI) ─────────────────────────────────

    private AgentTool provision() {
        return new AgentTool() {
            @Override public String name() { return "aws_provision_gpu_vm"; }
            @Override public String description() {
                return "Request a new GPU VM in the linked AWS account. BILLABLE: nothing is created until the user "
                     + "reviews the cost estimate and confirms in Ananke. Tell the user it awaits their confirmation.";
            }
            @Override public Map<String, Object> parameters() { return vmSchema(); }
            @Override public String cloud() { return "aws"; }
            @Override public boolean billable() { return true; }
            @Override public String statusText(JsonNode args) { return "Requesting a " + args.path("instanceType").asText("") + " VM (needs your confirmation)…"; }

            @Override
            public String costEstimate(JsonNode args) {
                AwsMachines.Estimate e = machines.estimate(account().getId(), request(args));
                pendingEstimates.put(key(args), e.id());
                return AwsMachines.costLine(e) + " — " + e.request().instanceType() + " in " + e.request().region()
                        + ", " + e.request().diskGiB() + " GiB disk, SSH from " + e.request().sshCidr()
                        + (e.request().idleStopMinutes() > 0 ? ", auto-stop after " + e.request().idleStopMinutes() + " idle min" : ", no auto-stop")
                        + ". " + e.source() + ".";
            }

            @Override
            public Object call(JsonNode args) {
                // Runs only after a person confirmed: create exactly what they were shown.
                String estimateId = pendingEstimates.remove(key(args));
                if (estimateId == null) throw new PackException(PackException.Code.ESTIMATE_REQUIRED, "That estimate is no longer available; ask again.");
                ComputeTarget t = machines.provision(account().getId(), estimateId, true, "agent");
                return Map.of("targetId", t.getId(), "status", t.getStatus(), "message", "Provisioning started.");
            }
        };
    }

    private AgentTool start() {
        return new AgentTool() {
            @Override public String name() { return "aws_start_vm"; }
            @Override public String description() { return "Start a stopped AWS machine. BILLABLE (compute billing resumes): the user confirms in Ananke first."; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of("targetId", Map.of("type", "integer")), "required", List.of("targetId"));
            }
            @Override public String cloud() { return "aws"; }
            @Override public boolean billable() { return true; }
            @Override public String statusText(JsonNode args) { return "Requesting start of machine " + args.path("targetId").asText() + " (needs your confirmation)…"; }

            @Override
            public String costEstimate(JsonNode args) {
                ComputeTarget t = machines.awsTarget(args.path("targetId").asLong());
                Object e = t.getCloudMeta() == null ? null : t.getCloudMeta().get("estimate");
                if (!(e instanceof Map<?, ?> m) || m.get("hourlyUsd") == null) return null;   // no estimate → refused
                return "Restarts " + t.getLabel() + ": ≈ $" + m.get("hourlyUsd") + "/hour while running (price from when it was created).";
            }

            @Override
            public Object call(JsonNode args) {
                ComputeTarget t = machines.start(args.path("targetId").asLong(), "agent");
                return Map.of("targetId", t.getId(), "status", t.getStatus());
            }
        };
    }

    // ── AWS MCP knowledge (read-only) ────────────────────────────────────────

    private AgentTool mcpTool(AwsMcpKnowledge.McpTool t) {
        String desc = t.description().length() > 1000 ? t.description().substring(0, 1000) : t.description();
        return tool(t.name(), desc + " (AWS documentation; read-only.)", t.inputSchema(), false,
                args -> mcp.call(() -> accounts.session(account()), t.name(), args),
                args -> "Searching AWS documentation…");
    }

    static String key(JsonNode args) {
        // canonical: the fields that define the machine, in a fixed order
        return String.join("|", args.path("region").asText(""), args.path("instanceType").asText(""),
                args.path("diskGiB").asText(""), args.path("idleStopMinutes").asText(""), args.path("packId").asText(""));
    }

    private AgentTool tool(String name, String description, Map<String, Object> params, boolean billable,
                           AgentTool.ToolFn fn, java.util.function.Function<JsonNode, String> status) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> parameters() { return params; }
            @Override public Object call(JsonNode args) throws Exception { return fn.apply(args); }
            @Override public String statusText(JsonNode args) { return status.apply(args); }
            @Override public String cloud() { return "aws"; }
            @Override public boolean billable() { return billable; }
        };
    }
}
