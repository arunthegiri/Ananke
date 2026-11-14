package com.stocktracker.agent.actions;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The only way a billable agent action runs (§9.3): a person confirms it here. There is no
 * billable tool yet (cloud linking, §7, is not built), so this list is always empty today.
 */
@RestController
@RequestMapping("/api")
public class AgentActionController {

    private final ActionConfirmationService actions;

    public AgentActionController(ActionConfirmationService actions) {
        this.actions = actions;
    }

    @GetMapping("/agent/actions")
    public List<ActionConfirmationService.PendingAction> pending() {
        return actions.pending();
    }

    @PostMapping("/agent/actions/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String id) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("actionId", id);
        out.put("result", actions.confirm(id));
        return out;
    }

    @PostMapping("/agent/actions/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable String id) {
        actions.reject(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/packs/{id}/audit")
    public List<AgentAuditEntry> audit(@PathVariable long id) {
        return actions.auditForPack(id);
    }
}
