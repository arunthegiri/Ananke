package com.stocktracker.packs.agent;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** {@code POST /api/packs/agent/chat} — one turn with the Pack agent (non-streaming). */
@RestController
@RequestMapping("/api/packs/agent")
public class PackAgentController {

    private final PackOpsAgent agent;

    public PackAgentController(PackOpsAgent agent) {
        this.agent = agent;
    }

    public record ChatRequest(List<PackOpsAgent.Turn> messages, Long packId) { }

    @PostMapping("/chat")
    public PackOpsAgent.Reply chat(@RequestBody ChatRequest req) {
        if (req == null || req.messages() == null || req.messages().isEmpty()) {
            throw new IllegalArgumentException("messages is required");
        }
        return agent.chat(req.messages(), req.packId());
    }
}
