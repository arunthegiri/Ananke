package com.stocktracker.agent.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.agent.ActionGuard;
import com.stocktracker.agent.AgentTool;
import com.stocktracker.agent.actions.AgentAuditEntry.Event;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Billable actions need a person (§9.3). When a model calls a billable tool, the call is
 * parked here with its cost estimate and the model is told to wait; only
 * {@link #confirm} — reached from the UI — runs it. Every step is written to the audit
 * log on the Pack it concerns. Pending actions live in memory and expire; a restart
 * drops them, which fails safe (nothing runs).
 */
@Slf4j
public class ActionConfirmationService implements ActionGuard {

    public static final Duration TTL = Duration.ofMinutes(15);

    public record PendingAction(String id, String agent, Long packId, String cloud, String tool,
                                String costEstimate, String arguments, Instant createdAt, Instant expiresAt) { }

    private record Parked(PendingAction view, AgentTool tool, JsonNode args) { }

    private final Map<String, Parked> pending = new ConcurrentHashMap<>();
    private final AgentAuditRepository audit;
    private final CloudToolGate gate;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ActionConfirmationService(AgentAuditRepository audit, CloudToolGate gate, ObjectMapper mapper, Clock clock) {
        this.audit  = audit;
        this.gate   = gate;
        this.mapper = mapper;
        this.clock  = clock;
    }

    @Override
    public Object requestConfirmation(String agentId, Long packId, AgentTool tool, JsonNode args) {
        String id = UUID.randomUUID().toString();
        String argsJson = args == null ? "{}" : args.toString();
        String estimate = tool.costEstimate(args);
        if (estimate == null || estimate.isBlank()) {
            record(id, agentId, packId, tool, Event.REFUSED, null, argsJson, "billable tool gave no cost estimate");
            return Map.of("status", "refused",
                    "error", "This action creates billable resources but has no cost estimate, so it cannot be offered for confirmation.");
        }
        Instant now = clock.instant();
        PendingAction view = new PendingAction(id, agentId, packId, tool.cloud(), tool.name(), estimate, argsJson, now, now.plus(TTL));
        pending.put(id, new Parked(view, tool, args));
        record(id, agentId, packId, tool, Event.REQUESTED, estimate, argsJson, null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "confirmation_required");
        out.put("actionId", id);
        out.put("costEstimate", estimate);
        out.put("message", "Nothing has been created. A person must review the cost and confirm this action in "
                + "the Ananke UI. Tell the user it is waiting for their confirmation; do not call the tool again.");
        return out;
    }

    public List<PendingAction> pending() {
        expireOld();
        return pending.values().stream().map(Parked::view)
                .sorted(Comparator.comparing(PendingAction::createdAt)).toList();
    }

    /** The user confirmed in the UI: run it now and audit the outcome. */
    public Object confirm(String actionId) {
        Parked p = take(actionId);
        PendingAction v = p.view();
        if (v.cloud() != null && !gate.isLinked(v.cloud())) {
            record(v, Event.FAILED, "cloud account " + v.cloud() + " is no longer linked");
            throw new IllegalStateException("The " + v.cloud() + " account is no longer linked; nothing was run.");
        }
        record(v, Event.CONFIRMED, null);
        try {
            Object result = p.tool().call(p.args());
            record(v, Event.EXECUTED, summarise(result));
            return result;
        } catch (Exception e) {
            record(v, Event.FAILED, e.getMessage());
            throw new IllegalStateException("Action failed: " + e.getMessage(), e);
        }
    }

    public void reject(String actionId) {
        record(take(actionId).view(), Event.REJECTED, null);
    }

    public List<AgentAuditEntry> auditForPack(long packId) {
        return audit.findByPackIdOrderByCreatedAtAsc(packId);
    }

    private Parked take(String actionId) {
        expireOld();
        Parked p = pending.remove(actionId);
        if (p == null) throw new NoSuchElementException("No pending action " + actionId + " (it may have expired).");
        return p;
    }

    private void expireOld() {
        Instant now = clock.instant();
        pending.values().removeIf(p -> {
            if (p.view().expiresAt().isAfter(now)) return false;
            record(p.view(), Event.EXPIRED, null);
            return true;
        });
    }

    private String summarise(Object result) {
        try {
            String s = mapper.writeValueAsString(result);
            return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    private void record(PendingAction v, Event event, String detail) {
        AgentAuditEntry e = entry(v.id(), v.agent(), v.packId(), v.cloud(), v.tool(), event, v.costEstimate(), v.arguments(), detail);
        audit.save(e);
    }

    private void record(String id, String agent, Long packId, AgentTool tool, Event event,
                        String estimate, String args, String detail) {
        audit.save(entry(id, agent, packId, tool.cloud(), tool.name(), event, estimate, args, detail));
    }

    private AgentAuditEntry entry(String id, String agent, Long packId, String cloud, String tool, Event event,
                                  String estimate, String args, String detail) {
        AgentAuditEntry e = new AgentAuditEntry();
        e.setActionId(id);
        e.setAgent(agent);
        e.setPackId(packId);
        e.setCloud(cloud);
        e.setTool(tool);
        e.setEvent(event);
        e.setCostEstimate(estimate);
        e.setArguments(args);
        e.setDetail(detail);
        e.setCreatedAt(clock.instant());
        log.info("agent action {} {} tool={} cloud={} pack={}", id, event, tool, cloud, packId);
        return e;
    }
}
