package com.stocktracker.packs.cloud.aws;

import com.stocktracker.agent.actions.AgentAuditEntry;
import com.stocktracker.agent.actions.AgentAuditRepository;
import com.stocktracker.packs.ComputeTarget;
import com.stocktracker.packs.ComputeTargetRepository;
import com.stocktracker.packs.Pack;
import com.stocktracker.packs.PackException;
import com.stocktracker.packs.PackRemovalHook;
import com.stocktracker.packs.PackRepository;
import com.stocktracker.packs.PackService;
import com.stocktracker.packs.SecretVault;
import com.stocktracker.packs.cloud.CloudAccount;
import com.stocktracker.packs.cloud.aws.AwsGateway.Creds;
import com.stocktracker.packs.cloud.aws.AwsGateway.Instance;
import com.stocktracker.packs.compute.SshComputeProvider;
import com.stocktracker.packs.runtime.Ssh;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.stocktracker.packs.PackException.Code.*;

/**
 * GPU VMs on a linked AWS account (Packs build doc §7.2–§7.4): cost estimate → explicit
 * confirmation → provision → out-of-band host key → an ordinary SSH {@link ComputeTarget}
 * (deploy is unchanged) → start/stop, idle auto-stop, terminate on Pack removal, orphan finder.
 *
 * <p>Money rules: nothing is created without a fresh estimate the user acknowledged
 * ({@link #provision} takes the estimate's id plus {@code acknowledgeBilling=true}); every
 * create/stop/start/terminate is written to the audit log against the Pack; the orphan
 * finder only lists — terminating an orphan is a separate, explicit call.
 */
@Slf4j
public class AwsMachines implements PackRemovalHook {

    public static final String PROVIDER = "aws";
    public static final String TAG_MANAGED = "ananke-managed";
    public static final String TAG_LINK = "ananke-link";
    public static final String TAG_TARGET = "ananke-target";
    static final String SSH_USER = "ubuntu";              // Deep Learning Base AMI (Ubuntu 22.04)
    static final Duration ESTIMATE_TTL = Duration.ofMinutes(15);
    static final int HOURS_PER_MONTH = 730;

    /** Curated NVIDIA GPU instance types (vLLM-friendly). GPU facts for sizing hints only. */
    public record InstanceTypeInfo(String type, String gpu, int gpus, int vramGiBPerGpu, int vcpus, int memoryGiB) { }

    public static final List<InstanceTypeInfo> CATALOG = List.of(
            new InstanceTypeInfo("g4dn.xlarge", "NVIDIA T4", 1, 16, 4, 16),
            new InstanceTypeInfo("g4dn.2xlarge", "NVIDIA T4", 1, 16, 8, 32),
            new InstanceTypeInfo("g5.xlarge", "NVIDIA A10G", 1, 24, 4, 16),
            new InstanceTypeInfo("g5.2xlarge", "NVIDIA A10G", 1, 24, 8, 32),
            new InstanceTypeInfo("g5.12xlarge", "NVIDIA A10G", 4, 24, 48, 192),
            new InstanceTypeInfo("g6.xlarge", "NVIDIA L4", 1, 24, 4, 16),
            new InstanceTypeInfo("g6.2xlarge", "NVIDIA L4", 1, 24, 8, 32),
            new InstanceTypeInfo("g6.12xlarge", "NVIDIA L4", 4, 24, 48, 192),
            new InstanceTypeInfo("g6e.xlarge", "NVIDIA L40S", 1, 48, 4, 32),
            new InstanceTypeInfo("g6e.2xlarge", "NVIDIA L40S", 1, 48, 8, 64));

    public record ProvisionRequest(String label, String region, String instanceType, Integer diskGiB,
                                   String sshCidr, Integer idleStopMinutes, Long packId, String amiId) { }

    public record Estimate(String id, long accountId, ProvisionRequest request, double hourlyUsd,
                           double gp3GbMonthUsd, double diskMonthlyUsd, double alwaysOnMonthlyUsd,
                           String source, Instant createdAt, Instant expiresAt, List<String> notes) { }

    public record Orphan(String region, String instanceId, String state, String instanceType, Map<String, String> tags) { }

    /** Waits between polls; tests pass a no-op. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    public record Timing(Duration poll, Duration bootTimeout, Duration hostKeyTimeout, Duration terminateTimeout) {
        public static Timing defaults() {
            return new Timing(Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(10));
        }
    }

    static final Pattern CIDR = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$");
    static final Pattern AMI = Pattern.compile("^ami-[0-9a-f]{8,17}$");
    static final Pattern HOST_KEY = Pattern.compile("(ssh-ed25519|ecdsa-sha2-nistp(?:256|384|521)|ssh-rsa)\\s+([A-Za-z0-9+/]{40,}={0,3})");

    private final AwsAccounts accounts;
    private final AwsGateway aws;
    private final ComputeTargetRepository targets;
    private final PackRepository packs;
    private final ObjectProvider<PackService> packService;
    private final SecretVault vault;
    private final SshKeygen keygen;
    private final AgentAuditRepository audit;
    private final Executor executor;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Timing timing;
    private final Map<String, Estimate> estimates = new ConcurrentHashMap<>();

    public AwsMachines(AwsAccounts accounts, AwsGateway aws, ComputeTargetRepository targets, PackRepository packs,
                       ObjectProvider<PackService> packService, SecretVault vault, SshKeygen keygen,
                       AgentAuditRepository audit, Executor executor, Sleeper sleeper, Clock clock, Timing timing) {
        this.accounts    = accounts;
        this.aws         = aws;
        this.targets     = targets;
        this.packs       = packs;
        this.packService = packService;
        this.vault       = vault;
        this.keygen      = keygen;
        this.audit       = audit;
        this.executor    = executor;
        this.sleeper     = sleeper;
        this.clock       = clock;
        this.timing      = timing;
    }

    // ── Estimate (free) ──────────────────────────────────────────────────────

    public Estimate estimate(long accountId, ProvisionRequest raw) {
        CloudAccount a = accounts.linked(accountId);
        ProvisionRequest req = validate(raw);
        Creds c = accounts.session(a);
        double hourly = aws.onDemandHourlyUsd(c, req.region(), req.instanceType()).orElseThrow(() ->
                new PackException(PRICE_UNAVAILABLE, "AWS lists no on-demand Linux price for " + req.instanceType()
                        + " in " + req.region() + ". Pick another region or type; nothing is created without a price."));
        double gp3 = aws.gp3GbMonthUsd(c, req.region()).orElseThrow(() ->
                new PackException(PRICE_UNAVAILABLE, "AWS lists no gp3 storage price in " + req.region() + "."));
        double disk = round2(gp3 * req.diskGiB());
        double alwaysOn = round2(hourly * HOURS_PER_MONTH + disk);
        List<String> notes = new ArrayList<>();
        notes.add("On-demand Linux price; taxes, data transfer and public IPv4 charges are extra.");
        notes.add("The disk (" + req.diskGiB() + " GiB gp3) is billed while the VM exists, even when stopped: ≈ $" + disk + "/month.");
        notes.add(req.idleStopMinutes() > 0
                ? "Ananke stops the VM after " + req.idleStopMinutes() + " idle minutes; a stopped VM bills only its disk."
                : "Idle auto-stop is off: the VM bills every hour until you stop or terminate it.");
        Instant now = clock.instant();
        Estimate e = new Estimate(UUID.randomUUID().toString(), accountId, req, hourly, gp3, disk, alwaysOn,
                "AWS Price List API (fetched " + now + ")", now, now.plus(ESTIMATE_TTL), List.copyOf(notes));
        estimates.values().removeIf(x -> x.expiresAt().isBefore(now));
        estimates.put(e.id(), e);
        return e;
    }

    ProvisionRequest validate(ProvisionRequest r) {
        if (r == null) throw new PackException(INVALID_INPUT, "Missing request.");
        String region = AwsAccounts.validRegion(r.region());
        String type = r.instanceType() == null ? "" : r.instanceType().strip();
        if (CATALOG.stream().noneMatch(i -> i.type().equals(type))) {
            throw new PackException(INVALID_INPUT, "Pick one of the listed GPU instance types.");
        }
        int disk = r.diskGiB() == null ? 150 : r.diskGiB();
        if (disk < 100 || disk > 2000) throw new PackException(INVALID_INPUT, "Disk must be 100–2000 GiB (the Deep Learning AMI alone needs ~75 GiB).");
        String cidr = validCidr(r.sshCidr());
        int idle = r.idleStopMinutes() == null ? 60 : r.idleStopMinutes();
        if (idle != 0 && (idle < 15 || idle > 1440)) throw new PackException(INVALID_INPUT, "Idle auto-stop must be off (0) or 15–1440 minutes.");
        String ami = r.amiId() == null || r.amiId().isBlank() ? null : r.amiId().strip();
        if (ami != null && !AMI.matcher(ami).matches()) throw new PackException(INVALID_INPUT, "Not an AMI id (ami-…).");
        if (r.packId() != null) packService.getObject().get(r.packId());        // must exist
        String label = r.label() == null || r.label().isBlank() ? "AWS " + type + " (" + region + ")" : r.label().strip();
        return new ProvisionRequest(label.substring(0, Math.min(100, label.length())), region, type, disk, cidr, idle, r.packId(), ami);
    }

    /** Only a narrow IPv4 range may reach port 22: /24 or tighter, never the internet. */
    static String validCidr(String raw) {
        Matcher m = raw == null ? null : CIDR.matcher(raw.strip());
        if (m == null || !m.matches()) throw new PackException(INVALID_INPUT, "SSH source must be an IPv4 CIDR such as 203.0.113.7/32.");
        for (int i = 1; i <= 4; i++) if (Integer.parseInt(m.group(i)) > 255) throw new PackException(INVALID_INPUT, "Invalid IPv4 address.");
        int prefix = Integer.parseInt(m.group(5));
        if (prefix < 24 || prefix > 32) throw new PackException(INVALID_INPUT, "SSH source must be /24 or narrower (use your backend's IP/32).");
        return raw.strip();
    }

    // ── Provision (billable) ─────────────────────────────────────────────────

    /**
     * Creates the VM described by a fresh estimate the user just saw. {@code actor} is who confirmed
     * ("user" from the UI, or the agent id when a person confirmed an agent's request).
     */
    public ComputeTarget provision(long accountId, String estimateId, boolean acknowledgeBilling, String actor) {
        CloudAccount a = accounts.linked(accountId);
        Estimate e = estimateId == null ? null : estimates.get(estimateId);
        if (e == null || e.accountId() != accountId || e.expiresAt().isBefore(clock.instant())) {
            throw new PackException(ESTIMATE_REQUIRED, "Get a fresh cost estimate first (estimates expire after 15 minutes).");
        }
        if (!acknowledgeBilling) {
            throw new PackException(ESTIMATE_REQUIRED, "Confirm that you accept the estimated AWS charges.");
        }
        estimates.remove(estimateId);                              // one estimate → at most one VM
        ProvisionRequest req = e.request();
        String actionId = UUID.randomUUID().toString();

        SshKeygen.KeyPair kp = keygen.ed25519("ananke-aws");
        ComputeTarget t = new ComputeTarget();
        t.setProvider(PROVIDER);
        t.setLabel(req.label());
        t.setHost("0.0.0.0");                                      // replaced by the public IP once running
        t.setPort(22);
        t.setUsername(SSH_USER);
        t.setStatus("PROVISIONING");
        t.setCloudAccountId(a.getId());
        t.setCloudRegion(req.region());
        t.setIdleStopMinutes(req.idleStopMinutes());
        t.setOwnerPackId(req.packId());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("instanceType", req.instanceType());
        meta.put("diskGiB", req.diskGiB());
        meta.put("sshCidr", req.sshCidr());
        meta.put("phase", "PREPARING");
        meta.put("estimate", Map.of("hourlyUsd", e.hourlyUsd(), "diskMonthlyUsd", e.diskMonthlyUsd(),
                "alwaysOnMonthlyUsd", e.alwaysOnMonthlyUsd(), "source", e.source()));
        meta.put("actionId", actionId);
        t.setCloudMeta(meta);
        ComputeTarget saved = targets.save(t);
        vault.put(saved.scope(), SshComputeProvider.KEY_KIND, kp.privateKey());
        vault.put(saved.scope(), "ssh_public_key", kp.publicKey());
        record(actionId, actor, req.packId(), "aws_provision_gpu_vm", AgentAuditEntry.Event.CONFIRMED,
                costLine(e), "target=" + saved.getId() + " " + req.instanceType() + " " + req.region() + " disk=" + req.diskGiB()
                        + " ssh=" + req.sshCidr(), null);
        executor.execute(() -> runProvision(saved.getId(), a.getId(), req, kp.publicKey(), actionId, actor));
        return saved;
    }

    void runProvision(long targetId, long accountId, ProvisionRequest req, String publicKey, String actionId, String actor) {
        String instanceId = null;
        String sgId = null;
        try {
            CloudAccount a = accounts.linked(accountId);
            Creds c = accounts.session(a);
            String ami = req.amiId() != null ? req.amiId() : aws.ssmParameter(c, req.region(), AwsAccounts.DLAMI_PARAMETER);
            meta(targetId, Map.of("amiId", ami, "phase", "CREATING_SECURITY_GROUP"));

            Map<String, String> tags = new LinkedHashMap<>();
            tags.put(TAG_MANAGED, "true");
            tags.put(TAG_LINK, a.getLinkTag());
            tags.put(TAG_TARGET, String.valueOf(targetId));
            tags.put("Name", "ananke-target-" + targetId);
            String vpc = aws.defaultVpcId(c, req.region());
            sgId = aws.createSecurityGroup(c, req.region(), vpc, "ananke-target-" + targetId + "-" + AwsAccounts.random(3),
                    "Ananke Pack machine: SSH from the Ananke backend only", tags);
            meta(targetId, Map.of("securityGroupId", sgId));
            aws.authorizeSshIngress(c, req.region(), sgId, req.sshCidr());

            meta(targetId, Map.of("phase", "LAUNCHING"));
            instanceId = aws.runInstance(c, req.region(),
                    new AwsGateway.RunSpec(ami, req.instanceType(), sgId, req.diskGiB(), userData(publicKey), tags));
            final String iid = instanceId;
            update(targetId, t -> t.setCloudInstanceId(iid));
            record(actionId, actor, req.packId(), "aws_provision_gpu_vm", AgentAuditEntry.Event.EXECUTED, null,
                    null, "instance " + instanceId + " launched (billing started)");

            meta(targetId, Map.of("phase", "WAITING_FOR_IP"));
            Instance running = waitRunning(c, req.region(), instanceId, timing.bootTimeout());
            meta(targetId, Map.of("phase", "READING_HOST_KEY", "state", "running"));
            String[] key = waitHostKey(c, req.region(), instanceId);
            pinHost(targetId, running.publicIp(), key);
            meta(targetId, Map.of("phase", "READY", "startedAt", clock.instant().toString()));
        } catch (Exception ex) {
            String msg = ex instanceof PackException || ex instanceof AwsGateway.AwsException ? ex.getMessage()
                    : "Provisioning failed: " + ex.getMessage();
            log.warn("AWS provisioning of target {} failed: {}", targetId, msg);
            String cleanup = cleanupAfterFailure(accountId, req.region(), instanceId, sgId);
            update(targetId, t -> {
                t.setStatus("ERROR");
                Map<String, Object> m = new LinkedHashMap<>(t.getCloudMeta());
                m.put("phase", "FAILED");
                m.put("error", msg + (cleanup.isEmpty() ? "" : " " + cleanup));
                t.setCloudMeta(m);
            });
            record(actionId, actor, req.packId(), "aws_provision_gpu_vm", AgentAuditEntry.Event.FAILED, null, null, msg + " " + cleanup);
        }
    }

    /** cloud-init: authorise our key for the default user and print host keys to the console. */
    static String userData(String publicKey) {
        return "#cloud-config\n"
                + "ssh_pwauth: false\n"
                + "disable_root: true\n"
                + "ssh_key_console_blacklist: []\n"
                + "ssh_authorized_keys:\n"
                + "  - " + publicKey.strip() + "\n";
    }

    private Instance waitRunning(Creds c, String region, String instanceId, Duration timeout) throws InterruptedException {
        Instant deadline = clock.instant().plus(timeout);
        while (true) {
            Optional<Instance> i = aws.describeInstance(c, region, instanceId);
            if (i.isPresent()) {
                String state = i.get().state();
                if ("running".equals(state) && i.get().publicIp() != null) return i.get();
                if ("terminated".equals(state) || "shutting-down".equals(state)) {
                    throw new PackException(PROVISION_FAILED, "AWS " + state + " the instance during boot (capacity or quota?).");
                }
            }
            if (!clock.instant().isBefore(deadline)) {
                throw new PackException(PROVISION_FAILED, "The instance was not running with a public IP within "
                        + timeout.toMinutes() + " minutes (does the default subnet assign public IPs?).");
            }
            sleeper.sleep(timing.poll());
        }
    }

    private String[] waitHostKey(Creds c, String region, String instanceId) throws InterruptedException {
        Instant deadline = clock.instant().plus(timing.hostKeyTimeout());
        while (true) {
            Optional<String[]> key = hostKeyFromConsole(aws.consoleOutput(c, region, instanceId));
            if (key.isPresent()) return key.get();
            if (!clock.instant().isBefore(deadline)) {
                throw new PackException(HOSTKEY_TIMEOUT, "The VM's host key did not appear in its console output within "
                        + timing.hostKeyTimeout().toMinutes() + " minutes, so Ananke cannot pin it without trusting the network. "
                        + "The VM was terminated.");
            }
            sleeper.sleep(timing.poll());
        }
    }

    /**
     * The host key from cloud-init's console block — obtained through the AWS API, not over the
     * network, so the first SSH connection is already pinned (§7.3). Prefers ed25519.
     */
    static Optional<String[]> hostKeyFromConsole(String console) {
        if (console == null) return Optional.empty();
        int end = console.lastIndexOf("-----END SSH HOST KEY KEYS-----");
        if (end < 0) return Optional.empty();
        int begin = console.lastIndexOf("-----BEGIN SSH HOST KEY KEYS-----", end);
        if (begin < 0) return Optional.empty();
        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = HOST_KEY.matcher(console.substring(begin, end));
        while (m.find()) {
            if (validBlob(m.group(1), m.group(2))) found.putIfAbsent(m.group(1), m.group(2));
        }
        for (String pref : List.of("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-rsa")) {
            if (found.containsKey(pref)) return Optional.of(new String[]{pref, found.get(pref)});
        }
        return Optional.empty();
    }

    /** The SSH wire format starts with the key type, length-prefixed — a mangled line never pins. */
    static boolean validBlob(String type, String blob) {
        try {
            byte[] b = Base64.getDecoder().decode(blob);
            if (b.length < 4 + type.length()) return false;
            int len = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
            return len == type.length() && new String(b, 4, len, java.nio.charset.StandardCharsets.US_ASCII).equals(type);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void pinHost(long targetId, String ip, String[] key) {
        String host = Ssh.validHost(ip);
        update(targetId, t -> {
            t.setHost(host);
            t.setHostKey(Ssh.knownHostsHost(host, 22) + " " + key[0] + " " + key[1]);
            t.setHostKeyFingerprint(Ssh.fingerprint(key[1]));
            Object prior = t.getCloudMeta() == null ? null : t.getCloudMeta().get("readyStatus");
            t.setStatus(prior instanceof String s ? s : "HOSTKEY_CONFIRMED");
        });
    }

    private String cleanupAfterFailure(long accountId, String region, String instanceId, String sgId) {
        List<String> left = new ArrayList<>();
        try {
            Creds c = accounts.session(accounts.get(accountId));
            if (instanceId != null) {
                aws.terminateInstance(c, region, instanceId);
                waitTerminated(c, region, instanceId);
            }
            if (sgId != null && !deleteSecurityGroup(c, region, sgId)) left.add("security group " + sgId);
        } catch (Exception e) {
            if (instanceId != null) left.add("instance " + instanceId);
            if (sgId != null) left.add("security group " + sgId);
        }
        return left.isEmpty() ? (instanceId == null ? "" : "The instance was terminated.")
                : "Left behind (remove in the AWS console or with the orphan finder): " + String.join(", ", left) + ".";
    }

    private void waitTerminated(Creds c, String region, String instanceId) throws InterruptedException {
        Instant deadline = clock.instant().plus(timing.terminateTimeout());
        while (clock.instant().isBefore(deadline)) {
            Optional<Instance> i = aws.describeInstance(c, region, instanceId);
            if (i.isEmpty() || "terminated".equals(i.get().state())) return;
            sleeper.sleep(timing.poll());
        }
        throw new PackException(CLOUD_FAILED, "Instance " + instanceId + " did not finish terminating in time.");
    }

    /** Security groups can't be deleted until the instance's network interface is gone; retry briefly. */
    private boolean deleteSecurityGroup(Creds c, String region, String sgId) throws InterruptedException {
        for (int i = 0; i < 6; i++) {
            try {
                aws.deleteSecurityGroup(c, region, sgId);
                return true;
            } catch (AwsGateway.AwsException e) {
                if ("InvalidGroup.NotFound".equals(e.code())) return true;
                sleeper.sleep(timing.poll());
            }
        }
        return false;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public ComputeTarget stop(long targetId, String actor) {
        ComputeTarget t = awsTarget(targetId);
        requireState(t, Set.of("HOSTKEY_CONFIRMED", "READY", "NO_GPU", "ERROR"), "stop");
        stopPacksOn(t);
        CloudAccount a = accounts.linked(t.getCloudAccountId());
        String actionId = UUID.randomUUID().toString();
        aws.stopInstance(accounts.session(a), t.getCloudRegion(), t.getCloudInstanceId());
        record(actionId, actor, t.getOwnerPackId(), "aws_stop_vm", AgentAuditEntry.Event.EXECUTED, null,
                "target=" + targetId + " " + t.getCloudInstanceId(), "stopped; the disk is still billed");
        return update(targetId, x -> {
            Map<String, Object> m = new LinkedHashMap<>(x.getCloudMeta());
            if (!"ERROR".equals(x.getStatus())) m.put("readyStatus", x.getStatus());
            m.put("state", "stopped");
            m.put("phase", "STOPPED");
            x.setCloudMeta(m);
            x.setStatus("STOPPED");
        });
    }

    public ComputeTarget start(long targetId, String actor) {
        ComputeTarget t = awsTarget(targetId);
        requireState(t, Set.of("STOPPED"), "start");
        CloudAccount a = accounts.linked(t.getCloudAccountId());
        String actionId = UUID.randomUUID().toString();
        aws.startInstance(accounts.session(a), t.getCloudRegion(), t.getCloudInstanceId());
        record(actionId, actor, t.getOwnerPackId(), "aws_start_vm", AgentAuditEntry.Event.EXECUTED,
                Optional.ofNullable(t.getCloudMeta().get("estimate")).map(Object::toString).orElse(null),
                "target=" + targetId + " " + t.getCloudInstanceId(), "started (billing resumed)");
        ComputeTarget starting = update(targetId, x -> {
            x.setStatus("STARTING");
            meta(x, Map.of("phase", "STARTING", "state", "pending"));
        });
        executor.execute(() -> {
            try {
                // Same disk → same host key; only the public IP changes. Keep the pinned key.
                Instance i = waitRunning(accounts.session(a), t.getCloudRegion(), t.getCloudInstanceId(), timing.bootTimeout());
                String host = Ssh.validHost(i.publicIp());
                update(targetId, x -> {
                    String[] parts = x.getHostKey().split(" ", 2);
                    x.setHost(host);
                    x.setHostKey(Ssh.knownHostsHost(host, 22) + " " + parts[1]);
                    Object prior = x.getCloudMeta().get("readyStatus");
                    x.setStatus(prior instanceof String s ? s : "HOSTKEY_CONFIRMED");
                    meta(x, Map.of("phase", "READY", "state", "running", "startedAt", clock.instant().toString()));
                });
            } catch (Exception e) {
                update(targetId, x -> {
                    x.setStatus("ERROR");
                    meta(x, Map.of("phase", "FAILED", "error", "Start failed: " + e.getMessage()));
                });
            }
        });
        return starting;
    }

    /** Terminates the VM and its security group, then forgets the target. Refused while Packs use it. */
    public ComputeTarget terminate(long targetId, String actor) {
        ComputeTarget t = awsTarget(targetId);
        if (!packs.findByComputeTargetId(targetId).isEmpty()) {
            throw new IllegalStateException("Remove the Packs on this machine first (removing the Pack it was created for terminates it).");
        }
        return terminateNow(t, actor);
    }

    private ComputeTarget terminateNow(ComputeTarget t, String actor) {
        long targetId = t.getId();
        String actionId = UUID.randomUUID().toString();
        ComputeTarget marked = update(targetId, x -> {
            x.setStatus("TERMINATING");
            meta(x, Map.of("phase", "TERMINATING"));
        });
        executor.execute(() -> {
            try {
                CloudAccount a = accounts.get(t.getCloudAccountId());
                Creds c = accounts.session(a);
                if (t.getCloudInstanceId() != null) {
                    aws.terminateInstance(c, t.getCloudRegion(), t.getCloudInstanceId());
                    waitTerminated(c, t.getCloudRegion(), t.getCloudInstanceId());
                }
                Object sg = t.getCloudMeta() == null ? null : t.getCloudMeta().get("securityGroupId");
                boolean sgGone = !(sg instanceof String s) || deleteSecurityGroup(c, t.getCloudRegion(), s);
                record(actionId, actor, t.getOwnerPackId(), "aws_terminate_vm", AgentAuditEntry.Event.EXECUTED, null,
                        "target=" + targetId + " " + t.getCloudInstanceId(),
                        "terminated" + (sgGone ? "" : "; security group " + sg + " left behind"));
                vault.deleteScope(t.scope());
                targets.deleteById(targetId);
            } catch (Exception e) {
                record(actionId, actor, t.getOwnerPackId(), "aws_terminate_vm", AgentAuditEntry.Event.FAILED, null,
                        "target=" + targetId, e.getMessage());
                update(targetId, x -> {
                    x.setStatus("ERROR");
                    meta(x, Map.of("phase", "FAILED", "error", "Terminate failed — retry, or terminate "
                            + t.getCloudInstanceId() + " in the AWS console: " + e.getMessage()));
                });
            }
        });
        return marked;
    }

    /** §7.4: removing the Pack a VM was provisioned for terminates that VM (unless another Pack uses it). */
    @Override
    public void afterPackRemoved(Pack pack, ComputeTarget target) {
        if (!PROVIDER.equals(target.getProvider()) || !pack.getId().equals(target.getOwnerPackId())) return;
        if (!packs.findByComputeTargetId(target.getId()).isEmpty()) return;
        terminateNow(target, "system:pack-removed");
    }

    // ── Idle auto-stop ───────────────────────────────────────────────────────

    /** Stops VMs whose Packs had no copilot traffic for the target's idle window. Returns stopped target ids. */
    public List<Long> idleSweep() {
        List<Long> stopped = new ArrayList<>();
        Instant now = clock.instant();
        for (ComputeTarget t : targets.findByProvider(PROVIDER)) {
            Integer idle = t.getIdleStopMinutes();
            if (idle == null || idle <= 0) continue;
            if (!Set.of("HOSTKEY_CONFIRMED", "READY", "NO_GPU").contains(t.getStatus())) continue;
            List<Pack> on = packs.findByComputeTargetId(t.getId());
            if (on.stream().anyMatch(p -> p.getStatus() == Pack.Status.DEPLOYING)) continue;
            Instant last = lastActivity(t, on);
            if (last != null && Duration.between(last, now).toMinutes() >= idle) {
                try {
                    stop(t.getId(), "system:idle-auto-stop");
                    stopped.add(t.getId());
                } catch (RuntimeException e) {
                    log.warn("Idle auto-stop of target {} failed: {}", t.getId(), e.getMessage());
                }
            }
        }
        return stopped;
    }

    private Instant lastActivity(ComputeTarget t, List<Pack> on) {
        Instant last = null;
        Object started = t.getCloudMeta() == null ? null : t.getCloudMeta().get("startedAt");
        if (started instanceof String s) last = Instant.parse(s);
        PackService ps = packService.getIfAvailable();
        for (Pack p : on) {
            Optional<Instant> used = ps == null ? Optional.empty() : ps.lastUsed(p.getId());
            if (used.isPresent() && (last == null || used.get().isAfter(last))) last = used.get();
            if (p.getUpdatedAt() != null && p.getStatus() == Pack.Status.RUNNING && (last == null || p.getUpdatedAt().isAfter(last))) {
                last = p.getUpdatedAt();
            }
        }
        return last;
    }

    private void stopPacksOn(ComputeTarget t) {
        PackService ps = packService.getIfAvailable();
        if (ps == null) return;
        for (Pack p : packs.findByComputeTargetId(t.getId())) {
            if (p.getStatus() == Pack.Status.RUNNING) {
                try {
                    ps.stop(p.getId());
                } catch (RuntimeException e) {
                    log.info("Pack {} stop before VM stop failed: {}", p.getId(), e.getMessage());
                }
            }
        }
    }

    // ── Orphans (list only) ──────────────────────────────────────────────────

    /** Instances tagged with this link that no Ananke target owns, across every enabled region. */
    public List<Orphan> orphans(long accountId) {
        CloudAccount a = accounts.linked(accountId);
        Creds c = accounts.session(a);
        Set<String> known = new HashSet<>();
        for (ComputeTarget t : targets.findByCloudAccountId(accountId)) if (t.getCloudInstanceId() != null) known.add(t.getCloudInstanceId());
        List<Orphan> out = new ArrayList<>();
        for (String region : aws.enabledRegions(c, a.getHomeRegion())) {
            for (Instance i : aws.instancesTagged(c, region, TAG_LINK, a.getLinkTag())) {
                if (!known.contains(i.id())) out.add(new Orphan(region, i.id(), i.state(), i.instanceType(), i.tags()));
            }
        }
        return out;
    }

    /** Explicit, per-instance: only instances carrying this link's tags can be terminated here. */
    public void terminateOrphan(long accountId, String region, String instanceId, String actor) {
        CloudAccount a = accounts.linked(accountId);
        Creds c = accounts.session(a);
        String r = AwsAccounts.validRegion(region);
        if (instanceId == null || !instanceId.matches("^i-[0-9a-f]{8,17}$")) throw new PackException(INVALID_INPUT, "Not an instance id.");
        Instance i = aws.describeInstance(c, r, instanceId).orElseThrow(() -> new NoSuchElementException("No such instance in " + r));
        if (!"true".equals(i.tags().get(TAG_MANAGED)) || !a.getLinkTag().equals(i.tags().get(TAG_LINK))) {
            throw new PackException(CLOUD_PERMISSION, "That instance was not created by this Ananke link; Ananke will not touch it.");
        }
        if (targets.findByCloudAccountId(accountId).stream().anyMatch(t -> instanceId.equals(t.getCloudInstanceId()))) {
            throw new IllegalStateException("That instance belongs to a machine Ananke still tracks; terminate it from the machine list.");
        }
        aws.terminateInstance(c, r, instanceId);
        record(UUID.randomUUID().toString(), actor, null, "aws_terminate_orphan", AgentAuditEntry.Event.EXECUTED, null,
                region + " " + instanceId, "terminated orphan");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    public List<ComputeTarget> machines(long accountId) {
        return targets.findByCloudAccountId(accountId);
    }

    ComputeTarget awsTarget(long id) {
        ComputeTarget t = targets.findById(id).orElseThrow(() -> new NoSuchElementException("Compute target not found"));
        if (!PROVIDER.equals(t.getProvider())) throw new IllegalArgumentException("Not an AWS machine.");
        return t;
    }

    private static void requireState(ComputeTarget t, Set<String> allowed, String verb) {
        if (!allowed.contains(t.getStatus()) || t.getCloudInstanceId() == null) {
            throw new IllegalStateException("Can't " + verb + " a machine that is " + t.getStatus().toLowerCase() + ".");
        }
    }

    private ComputeTarget update(long targetId, java.util.function.Consumer<ComputeTarget> change) {
        synchronized (this) {
            ComputeTarget t = targets.findById(targetId).orElseThrow(() -> new NoSuchElementException("Compute target not found"));
            change.accept(t);
            return targets.save(t);
        }
    }

    private void meta(long targetId, Map<String, Object> values) {
        update(targetId, t -> meta(t, values));
    }

    private static void meta(ComputeTarget t, Map<String, Object> values) {
        Map<String, Object> m = t.getCloudMeta() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(t.getCloudMeta());
        m.putAll(values);
        t.setCloudMeta(m);
    }

    private void record(String actionId, String actor, Long packId, String tool, AgentAuditEntry.Event event,
                        String cost, String args, String detail) {
        AgentAuditEntry e = new AgentAuditEntry();
        e.setActionId(actionId);
        e.setAgent(actor == null ? "user" : actor.substring(0, Math.min(32, actor.length())));
        e.setPackId(packId);
        e.setCloud(PROVIDER);
        e.setTool(tool);
        e.setEvent(event);
        e.setCostEstimate(cost);
        e.setArguments(args);
        e.setDetail(detail);
        e.setCreatedAt(clock.instant());
        audit.save(e);
    }

    static String costLine(Estimate e) {
        return "$" + e.hourlyUsd() + "/h + disk ≈ $" + e.diskMonthlyUsd() + "/month (always-on ≈ $" + e.alwaysOnMonthlyUsd() + "/month)";
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
