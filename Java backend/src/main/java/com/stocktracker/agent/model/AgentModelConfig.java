package com.stocktracker.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.packs.SecretVault;
import com.stocktracker.service.LlmSettingsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires per-agent model routing (Agent Integration spec W1). Nothing here replaces or wraps
 * the legacy {@code @Primary} {@code SettingsAwareLLMClient}: Copilot keeps using it until a
 * caller deliberately asks {@link ModelClientFactory} for a role-scoped client.
 */
@Configuration
public class AgentModelConfig {

    @Bean
    public AgentRuntimeFlags agentRuntimeFlags(
            @Value("${ananke.features.agent-runtime-v2:false}") boolean v2,
            @Value("${ananke.features.per-agent-models:false}") boolean perAgentModels,
            @Value("${ananke.features.isolated-execution:false}") boolean isolatedExecution,
            @Value("${ananke.features.research-monitoring:false}") boolean monitoring,
            @Value("${ananke.features.control-lab:false}") boolean controlLab,
            @Value("${ananke.features.paper-deployment-v1:false}") boolean paper) {
        return new AgentRuntimeFlags(v2, perAgentModels, isolatedExecution, monitoring, controlLab, paper);
    }

    @Bean
    public JpaAgentModelStore agentModelStore(ModelConnectionRepository connections,
                                              AgentModelBindingRepository bindings,
                                              ModelCapabilityProbeRepository probes,
                                              ModelCallRecordRepository calls) {
        return new JpaAgentModelStore(connections, bindings, probes, calls);
    }

    @Bean
    public CredentialStore modelCredentialStore(SecretVault vault) {
        return new VaultCredentialStore(vault);
    }

    @Bean
    public AgentModelResolver agentModelResolver(JpaAgentModelStore store,
                                                 ObjectProvider<DeploymentDirectory> deployments) {
        return new AgentModelResolver(store, deployments.getIfAvailable(() -> DeploymentDirectory.NONE),
                Clock.systemUTC());
    }

    @Bean
    public ModelClientFactory modelClientFactory(JpaAgentModelStore store, CredentialStore credentials,
                                                 ObjectProvider<DeploymentDirectory> deployments,
                                                 ObjectMapper mapper) {
        return new ModelClientFactory(store, credentials, deployments.getIfAvailable(() -> DeploymentDirectory.NONE),
                store, mapper, Clock.systemUTC());
    }

    @Bean
    public ModelProbeService modelProbeService(JpaAgentModelStore store,
                                               ObjectProvider<DeploymentDirectory> deployments,
                                               ModelClientFactory factory, ObjectMapper mapper) {
        return new ModelProbeService(store, deployments.getIfAvailable(() -> DeploymentDirectory.NONE), factory,
                mapper, Clock.systemUTC());
    }

    @Bean
    public AgentModelService agentModelService(ModelConnectionRepository connections,
                                               AgentModelBindingRepository bindings, CredentialStore credentials,
                                               ObjectProvider<DeploymentDirectory> deployments,
                                               AgentModelResolver resolver, LlmSettingsService legacy) {
        return new AgentModelService(connections, bindings, credentials,
                deployments.getIfAvailable(() -> DeploymentDirectory.NONE), resolver, legacy);
    }
}
