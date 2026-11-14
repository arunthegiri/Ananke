package com.stocktracker.packs.runtime;

import com.stocktracker.packs.PackException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import static com.stocktracker.packs.PackException.Code.*;

/**
 * {@link RemoteRuntime} over the system OpenSSH client. Every remote command
 * is the bundled {@code packs/vllm-bootstrap.sh} sent on stdin with validated
 * positional arguments — nothing else ever runs remotely.
 */
@Component
public class SshRemoteRuntime implements RemoteRuntime {

    private static final Pattern SAFE_ARG = Pattern.compile("^[A-Za-z0-9._/:-]{1,200}$");

    private final CommandRunner runner;
    private final String sshBin;
    private final String script;
    private final Duration deployTimeout;

    public SshRemoteRuntime(CommandRunner runner,
                            @Value("${ananke.packs.ssh-binary:ssh}") String sshBin,
                            @Value("${ananke.packs.deploy-timeout-minutes:90}") int deployTimeoutMinutes) {
        this.runner = runner;
        this.sshBin = sshBin;
        this.deployTimeout = Duration.ofMinutes(deployTimeoutMinutes);
        try {
            this.script = new String(new ClassPathResource("packs/vllm-bootstrap.sh").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("packs/vllm-bootstrap.sh missing from the classpath", e);
        }
    }

    // ── actions ──────────────────────────────────────────────────────────────

    @Override
    public Probe probe(SshCredentials c) {
        String out = exec(c, List.of("probe"), "", Duration.ofSeconds(60));
        String os = null, arch = null, py = null;
        boolean venv = false;
        Integer disk = null;
        List<Gpu> gpus = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] kv = line.split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0]) {
                case "ANANKE_OS" -> os = kv[1];
                case "ANANKE_ARCH" -> arch = kv[1];
                case "ANANKE_PYTHON" -> py = kv[1];
                case "ANANKE_VENV" -> venv = "1".equals(kv[1]);
                case "ANANKE_DISK_FREE_GB" -> disk = parseInt(kv[1]);
                case "ANANKE_GPU" -> {
                    String[] g = kv[1].split(",");
                    if (g.length >= 2) gpus.add(new Gpu(g[0].strip(), parseIntOr0(g[1].strip()), g.length > 2 ? g[2].strip() : ""));
                }
                default -> { }
            }
        }
        return new Probe(os, arch, py, venv, disk, gpus);
    }

    @Override
    public String deploy(SshCredentials c, DeploySpec s) {
        List<String> args = List.of("deploy", String.valueOf(s.packId()), s.repo(), s.revision(), String.valueOf(s.port()),
                s.servedName(), s.dtype(), String.valueOf(s.maxModelLen()), s.gpuUtil(), String.valueOf(s.tensorParallel()),
                s.quantization(), s.toolParser(), s.trustRemoteCode() ? "1" : "0", s.vllmVersion());
        // Secrets travel as base64 shell variables at the top of the stdin script: not argv, not disk.
        StringBuilder pre = new StringBuilder();
        if (s.hfToken() != null && !s.hfToken().isBlank()) pre.append("ANANKE_HF_TOKEN_B64='").append(b64(s.hfToken().strip())).append("'\n");
        pre.append("ANANKE_API_KEY_B64='").append(b64(s.apiKey())).append("'\n");
        return exec(c, args, pre.toString(), deployTimeout);
    }

    @Override
    public boolean isRunning(SshCredentials c, long packId) {
        return exec(c, List.of("status", String.valueOf(packId)), "", Duration.ofSeconds(30)).contains("ANANKE_RUNNING=1");
    }

    @Override
    public String logs(SshCredentials c, long packId) {
        return exec(c, List.of("logs", String.valueOf(packId)), "", Duration.ofSeconds(30));
    }

    @Override
    public void stop(SshCredentials c, long packId) {
        exec(c, List.of("stop", String.valueOf(packId)), "", Duration.ofSeconds(60));
    }

    @Override
    public void remove(SshCredentials c, long packId) {
        exec(c, List.of("remove", String.valueOf(packId)), "", Duration.ofSeconds(120));
    }

    // ── tunnel ───────────────────────────────────────────────────────────────

    public record Tunnel(Process process, int localPort) { }

    /**
     * Forwards 127.0.0.1:&lt;free port&gt; here to 127.0.0.1:&lt;remotePort&gt; on
     * the machine. vLLM never listens on a public interface. The key files are
     * removed as soon as the tunnel is up (ssh only needs them to authenticate).
     */
    public Tunnel openTunnel(SshCredentials c, int remotePort) {
        int local = freePort();
        Ssh.CredFiles f = new Ssh.CredFiles(c);
        try {
            List<String> argv = Ssh.baseArgv(sshBin, c, f);
            argv.addAll(List.of("-N", "-o", "ExitOnForwardFailure=yes",
                    "-L", "127.0.0.1:" + local + ":127.0.0.1:" + remotePort, "--", c.username() + "@" + c.host()));
            Process p = runner.start(argv);
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive()) throw new PackException(TUNNEL_FAILED, "The SSH tunnel to the model server closed immediately.");
                try (var s = new java.net.Socket("127.0.0.1", local)) {
                    return new Tunnel(p, local);
                } catch (IOException notYet) {
                    sleep(300);
                }
            }
            p.destroyForcibly();
            throw new PackException(TUNNEL_FAILED, "Timed out opening the SSH tunnel to the model server.");
        } finally {
            f.close();
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    String exec(SshCredentials c, List<String> scriptArgs, String preamble, Duration timeout) {
        for (String a : scriptArgs) {
            if (!SAFE_ARG.matcher(a).matches()) throw new PackException(INVALID_INPUT, "Refusing an unsafe remote argument.");
        }
        try (Ssh.CredFiles f = new Ssh.CredFiles(c)) {
            List<String> argv = Ssh.baseArgv(sshBin, c, f);
            argv.add("--");
            argv.add(c.username() + "@" + c.host());
            // The remote side gets a single command string; every arg matched SAFE_ARG, so no quoting can break out.
            argv.add("bash -s -- " + String.join(" ", scriptArgs));
            CommandRunner.Result r = runner.run(argv, preamble + script, timeout);
            if (r.timedOut()) throw new PackException(SSH_FAILED, "The remote step timed out after " + timeout.toMinutes() + " min.");
            String out = r.stdout() == null ? "" : r.stdout();
            if (r.exitCode() == 0) return out;
            String code = field(out, "ANANKE_ERROR");
            if (code != null) {
                PackException.Code k;
                try { k = PackException.Code.valueOf(code); } catch (IllegalArgumentException e) { k = SSH_FAILED; }
                String detail = field(out, "ANANKE_ERROR_DETAIL");
                throw new PackException(k, detail == null ? code : detail);
            }
            throw Ssh.classify(r.exitCode(), r.stderr());
        }
    }

    static String field(String out, String key) {
        String found = null;
        for (String line : out.split("\n")) if (line.startsWith(key + "=")) found = line.substring(key.length() + 1).strip();
        return found;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new PackException(TUNNEL_FAILED, "No free local port for the tunnel.");
        }
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s.strip()); } catch (Exception e) { return null; }
    }

    private static int parseIntOr0(String s) {
        Integer i = parseInt(s);
        return i == null ? 0 : i;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
