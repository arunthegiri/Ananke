package com.stocktracker.packs.runtime;

import java.util.List;
import java.util.Map;

/**
 * Drives a model server on a machine Ananke can reach. Implementations: SSH to
 * an existing Linux GPU host ({@link SshRemoteRuntime}). A cloud provider's
 * VMs can reuse this one after provisioning, since they end up as SSH hosts.
 */
public interface RemoteRuntime {

    record Gpu(String name, int memoryMiB, String driver) { }

    record Probe(String os, String arch, String python, boolean venv, Integer diskFreeGiB, List<Gpu> gpus) {
        public Map<String, Object> toMap() {
            return Map.of("os", nz(os), "arch", nz(arch), "python", nz(python), "venv", venv,
                    "diskFreeGiB", diskFreeGiB == null ? -1 : diskFreeGiB,
                    "gpus", gpus.stream().map(g -> Map.of("name", g.name(), "memoryMiB", g.memoryMiB(), "driver", g.driver())).toList());
        }
        private static String nz(String s) { return s == null ? "" : s; }
    }

    /** Everything the bootstrap script's deploy action takes, already validated. */
    record DeploySpec(long packId, String repo, String revision, int port, String servedName, String dtype,
                      int maxModelLen, String gpuUtil, int tensorParallel, String quantization, String toolParser,
                      boolean trustRemoteCode, String vllmVersion, String hfToken, String apiKey) {
        @Override
        public String toString() {
            return "DeploySpec[pack=" + packId + ", repo=" + repo + "@" + revision + ", secrets=<redacted>]";
        }
    }

    Probe probe(SshCredentials c);

    /** Installs (if needed), downloads the pinned model, and launches vLLM. Returns the redacted transcript. */
    String deploy(SshCredentials c, DeploySpec spec);

    boolean isRunning(SshCredentials c, long packId);

    String logs(SshCredentials c, long packId);

    void stop(SshCredentials c, long packId);

    void remove(SshCredentials c, long packId);
}
