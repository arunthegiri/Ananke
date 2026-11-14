package com.stocktracker.packs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stocktracker.agent.model.DeploymentDirectory;
import com.stocktracker.service.LLMClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only view of Packs for the agent runtime (Agent Integration spec §5.5): a descriptor
 * and a client, no Pack internals. Reuses {@link PackService#clientFor}, the same provider
 * seam the legacy Settings page uses, so there is one way to reach a Pack.
 */
@Component
public class PackDeploymentDirectory implements DeploymentDirectory {

    private final PackRepository packs;
    private final PackService service;
    private final ObjectMapper canonical = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public PackDeploymentDirectory(PackRepository packs, PackService service) {
        this.packs = packs;
        this.service = service;
    }

    @Override
    public Optional<Deployment> describe(String deploymentId) {
        Long id = parse(deploymentId);
        return id == null ? Optional.empty() : packs.findById(id).map(this::toDeployment);
    }

    @Override
    public Optional<LLMClient> clientFor(String deploymentId) {
        return service.clientFor(deploymentId);
    }

    @Override
    public List<Deployment> list() {
        return packs.findAllByOrderByCreatedAtDesc().stream().map(this::toDeployment).toList();
    }

    private Deployment toDeployment(Pack p) {
        Map<String, Object> endpoint = p.getEndpoint() == null ? Map.of() : p.getEndpoint();
        Object served = endpoint.get("servedModel");
        return new Deployment(p.scope(), "Pack: " + p.getName(), p.getStatus().name(),
                p.getStatus() == Pack.Status.RUNNING && served != null, revision(p, endpoint),
                served == null ? null : served.toString());
    }

    /**
     * Model revision plus a hash of what changes serving behaviour — launch config, tool
     * parser, served name — so a redeploy with a different parser or context length
     * invalidates tool-call probes (§5.6).
     */
    private String revision(Pack p, Map<String, Object> endpoint) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("launch", p.getLaunchConfig());
        material.put("toolParser", endpoint.get("toolParser"));
        material.put("servedModel", endpoint.get("servedModel"));
        material.put("trustRemoteCode", p.isTrustRemoteCode());
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.writeValueAsString(material).getBytes(StandardCharsets.UTF_8));
            return p.getHfRevision() + "/" + HexFormat.of().formatHex(h).substring(0, 12);
        } catch (Exception e) {
            return p.getHfRevision();
        }
    }

    private static Long parse(String deploymentId) {
        if (deploymentId == null || !deploymentId.startsWith("pack:")) return null;
        try {
            return Long.parseLong(deploymentId.substring(5));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
