package com.stocktracker.agent.skills;

import com.stocktracker.agent.AgentProfile;
import com.stocktracker.agent.actions.CloudToolGate;
import com.stocktracker.agent.skills.SkillRegistry.Skill;
import com.stocktracker.agent.skills.SkillRegistry.SkillPack;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/** Settings → Agent skills (§9.4): which packs are on, where they came from, and the toggle. */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry registry;
    private final SkillSettingsService settings;
    private final SkillToolset toolset;
    private final CloudToolGate gate;

    public SkillController(SkillRegistry registry, SkillSettingsService settings, SkillToolset toolset, CloudToolGate gate) {
        this.registry = registry;
        this.settings = settings;
        this.toolset  = toolset;
        this.gate     = gate;
    }

    public record SkillView(String name, String description, List<String> topics, int files) { }

    public record PackView(String id, String cloud, String label, String repo, String commit, String sourceUrl,
                           String license, String distribution, boolean enabled, List<SkillView> skills) { }

    public record AgentView(String id, String label, List<String> skills, List<String> linkedClouds) { }

    public record ToggleRequest(Boolean enabled) { }

    @GetMapping("/packs")
    public List<PackView> packs() {
        return registry.packs().stream().map(this::view).toList();
    }

    @PutMapping("/packs/{id}")
    public PackView toggle(@PathVariable String id, @RequestBody ToggleRequest req) {
        SkillPack p = registry.pack(id).orElseThrow(() -> new NoSuchElementException("Unknown skill pack: " + id));
        if (req == null || req.enabled() == null) throw new IllegalArgumentException("enabled is required");
        settings.setEnabled(p.id(), req.enabled());
        return view(p);
    }

    /** What each agent currently sees — the same filter its system prompt uses. */
    @GetMapping("/agents")
    public List<AgentView> agents() {
        return AgentProfile.ALL.stream().map(a -> new AgentView(a.id(), a.label(),
                toolset.visibleSkills(a).stream().map(Skill::name).toList(),
                a.cloudActions() ? gate.linkedClouds() : List.of())).toList();
    }

    private PackView view(SkillPack p) {
        return new PackView(p.id(), p.cloud(), p.label(), p.repo(), p.commit(), p.sourceUrl(), p.license(),
                p.distribution(), settings.isEnabled(p.id()),
                p.skills().stream().map(s -> new SkillView(s.name(),
                        SkillToolset.oneLine(s.description(), SkillToolset.INDEX_DESCRIPTION_CHARS),
                        s.topics(), s.files().size())).toList());
    }
}
