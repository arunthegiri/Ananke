package com.stocktracker.agent.actions;

import com.stocktracker.agent.AgentProfile;
import com.stocktracker.agent.AgentTool;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Offers cloud action tools only for linked clouds (§9.3, §9.4): no linked cloud → no
 * cloud tool; AWS linked → AWS tools only. A source cannot smuggle in another cloud's
 * tools, and every action tool must be marked billable with a cost estimate or be
 * explicitly read-only — that is checked per tool at offer time.
 */
@Slf4j
public class CloudToolGate {

    private final List<CloudActionToolSource> sources;

    public CloudToolGate(List<CloudActionToolSource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public List<AgentTool> toolsFor(AgentProfile agent) {
        if (!agent.cloudActions()) return List.of();
        List<AgentTool> out = new ArrayList<>();
        for (CloudActionToolSource src : sources) {
            if (!src.isLinked()) continue;
            for (AgentTool t : src.tools()) {
                if (!src.cloud().equals(t.cloud())) {
                    log.warn("Dropping tool {} from the {} source: it claims cloud {}", t.name(), src.cloud(), t.cloud());
                    continue;
                }
                out.add(t);
            }
        }
        return out;
    }

    public List<String> linkedClouds() {
        return sources.stream().filter(CloudActionToolSource::isLinked).map(CloudActionToolSource::cloud).distinct().toList();
    }

    public boolean isLinked(String cloud) {
        return sources.stream().anyMatch(s -> s.cloud().equals(cloud) && s.isLinked());
    }
}
