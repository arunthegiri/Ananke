package com.stocktracker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.agent.actions.ActionConfirmationService;
import com.stocktracker.agent.actions.AgentAuditRepository;
import com.stocktracker.agent.actions.CloudActionToolSource;
import com.stocktracker.agent.actions.CloudToolGate;
import com.stocktracker.agent.skills.SkillRegistry;
import com.stocktracker.agent.skills.SkillSettingsService;
import com.stocktracker.agent.skills.SkillToolset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Wires the shared agent kit (Packs build doc §9). The skill registry is built eagerly:
 * a bad bundle throws here and the backend does not start.
 */
@Slf4j
@Configuration
public class AgentConfig {

    @Bean
    public SkillRegistry skillRegistry(@Value("${ananke.skills.fetch-dir:./data/skills}") String fetchDir,
                                       @Value("${ananke.skills.bundle-dir:}") String bundleDir) {
        // bundle-dir replaces the jar's bundle wholesale (same manifest + checksum rules); unset = the jar's.
        SkillRegistry r = bundleDir.isBlank()
                ? SkillRegistry.fromClasspath(Path.of(fetchDir))
                : SkillRegistry.fromDirectory(Path.of(bundleDir), Path.of(fetchDir));
        int skills = r.packs().stream().mapToInt(p -> p.skills().size()).sum();
        log.info("Agent skills: {} pack(s), {} skill(s) verified", r.packs().size(), skills);
        return r;
    }

    @Bean
    public SkillSettingsService skillSettingsService(
            @Value("${ananke.skills.settings-file:./data/skills-settings.json}") String file) {
        return new SkillSettingsService(Path.of(file));
    }

    @Bean
    public SkillToolset skillToolset(SkillRegistry registry, SkillSettingsService settings) {
        return new SkillToolset(registry, settings);
    }

    @Bean
    public CloudToolGate cloudToolGate(ObjectProvider<CloudActionToolSource> sources) {
        return new CloudToolGate(sources.orderedStream().toList());
    }

    @Bean
    public ActionConfirmationService actionConfirmationService(AgentAuditRepository audit, CloudToolGate gate,
                                                               ObjectMapper mapper) {
        return new ActionConfirmationService(audit, gate, mapper, Clock.systemUTC());
    }

    @Bean
    public AgentCapabilities agentCapabilities(SkillToolset skills, CloudToolGate gate,
                                               ActionConfirmationService guard) {
        return new AgentCapabilities(skills, gate, guard);
    }
}
