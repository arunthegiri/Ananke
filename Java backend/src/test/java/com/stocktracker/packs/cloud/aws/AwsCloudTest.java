package com.stocktracker.packs.cloud.aws;

import com.stocktracker.agent.actions.AgentAuditEntry;
import com.stocktracker.agent.actions.AgentAuditRepository;
import com.stocktracker.packs.ComputeTarget;
import com.stocktracker.packs.ComputeTargetRepository;
import com.stocktracker.packs.Pack;
import com.stocktracker.packs.PackException;
import com.stocktracker.packs.PackRepository;
import com.stocktracker.packs.PackService;
import com.stocktracker.packs.SecretVault;
import com.stocktracker.packs.cloud.CloudAccount;
import com.stocktracker.packs.cloud.CloudAccountRepository;
import com.stocktracker.packs.compute.SshComputeProvider;
import com.stocktracker.packs.runtime.Ssh;
import com.stocktracker.packs.runtime.SshCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.stocktracker.packs.PackException.Code.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §7 AWS linking + provisioning + lifecycle, offline: a fake AWS gateway, a real TimescaleDB
 * with every Flyway migration (V9 included) and Hibernate validate, real SecretVault.
 * Time is simulated: each poll advances the clock, so timeouts are deterministic.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AwsCloudTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired CloudAccountRepository accountRepo;
    @Autowired ComputeTargetRepository targetRepo;
    @Autowired PackRepository packRepo;
    @Autowired AgentAuditRepository auditRepo;
    @Autowired DataSource dataSource;

    static final class SimClock extends Clock {
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    static final class FixedKeygen extends SshKeygen {
        FixedKeygen() { super(null, "ssh-keygen"); }
        @Override public KeyPair ed25519(String comment) {
            return new KeyPair("-----BEGIN OPENSSH PRIVATE KEY-----\nTESTPRIVATEKEYMATERIAL\n-----END OPENSSH PRIVATE KEY-----\n",
                    "ssh-ed25519 " + FakeAws.ed25519Blob(1) + " " + comment);
        }
    }

    static final ObjectProvider<PackService> NO_PACK_SERVICE = new ObjectProvider<>() {
        @Override public PackService getObject(Object... args) { throw new IllegalStateException(); }
        @Override public PackService getIfAvailable() { return null; }
        @Override public PackService getIfUnique() { return null; }
        @Override public PackService getObject() { throw new IllegalStateException("no PackService in this test"); }
    };

    FakeAws aws;
    SimClock clock;
    SecretVault vault;
    AwsAccounts accounts;
    AwsMachines machines;
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM agent_audit_log");
        jdbc.update("DELETE FROM packs");
        jdbc.update("DELETE FROM compute_targets");
        jdbc.update("DELETE FROM cloud_accounts");
        jdbc.update("DELETE FROM ananke_secrets");
        aws = new FakeAws();
        clock = new SimClock();
        vault = new SecretVault(jdbc, Base64.getEncoder().encodeToString(new byte[32]), "/nonexistent/unused.key");
        accounts = new AwsAccounts(accountRepo, targetRepo, vault, aws, clock);
        AwsMachines.Timing timing = new AwsMachines.Timing(Duration.ofSeconds(10), Duration.ofMinutes(2),
                Duration.ofMinutes(1), Duration.ofMinutes(1));
        machines = new AwsMachines(accounts, aws, targetRepo, packRepo, NO_PACK_SERVICE, vault, new FixedKeygen(),
                auditRepo, Runnable::run, d -> clock.now = clock.now.plus(d), clock, timing);
    }

    static final String ROLE = "arn:aws:iam::123456789012:role/AnankePacksRole";
    // Built at runtime so the source holds nothing key-shaped (secret scanners); both are fake.
    static final String KEY_ID = "AKIA" + "TEST".repeat(4);
    static final String SECRET = "fake-secret-".repeat(4);

    CloudAccount linked() {
        CloudAccount a = accounts.start("Test AWS", "us-east-1").account();
        return accounts.verify(a.getId(), ROLE, KEY_ID, SECRET);
    }

    AwsMachines.ProvisionRequest request() {
        return new AwsMachines.ProvisionRequest("gpu box", "us-east-1", "g6.xlarge", 150, "203.0.113.7/32", 60, null, null);
    }

    ComputeTarget provisioned(CloudAccount a) {
        AwsMachines.Estimate e = machines.estimate(a.getId(), request());
        ComputeTarget t = machines.provision(a.getId(), e.id(), true, "user");
        return targetRepo.findById(t.getId()).orElseThrow();
    }

    // ── Linking ──────────────────────────────────────────────────────────────

    @Test
    void link_verifiesWithAssumeRoleAndAFreeDryRun_andStoresTheKeyEncrypted() {
        AwsAccounts.LinkStart s = accounts.start("Test AWS", "us-east-1");
        assertThat(s.account().getStatus()).isEqualTo(CloudAccount.Status.PENDING);
        assertThat(s.account().getExternalId()).startsWith("ananke-").hasSizeGreaterThan(30);
        assertThat(s.cliCommand()).contains("ExternalId=" + s.account().getExternalId()).contains("CAPABILITY_NAMED_IAM");
        assertThat(s.template()).contains("sts:ExternalId", "aws:RequestTag/ananke-managed", "ec2:ResourceTag/ananke-managed",
                "aws:ViaAWSMCPService", "ec2:CreateAction");

        CloudAccount a = accounts.verify(s.account().getId(), ROLE, KEY_ID, SECRET);

        assertThat(a.getStatus()).isEqualTo(CloudAccount.Status.LINKED);
        assertThat(a.getAccountRef()).isEqualTo("123456789012");
        assertThat(aws.calls).contains("assumeRole " + ROLE + " " + s.account().getExternalId());
        assertThat(aws.calls.stream().filter(c -> c.startsWith("dryRun"))).singleElement().asString()
                .contains("ananke-managed=true").contains("ananke-link=" + a.getLinkTag());
        assertThat(aws.count("run ")).as("linking never launches anything").isZero();
        assertThat(accounts.keyHint(a)).isEqualTo("…TEST");
        // Stored as ciphertext only.
        assertThat(jdbc.queryForList("SELECT ciphertext FROM ananke_secrets", String.class))
                .hasSize(2).noneMatch(c -> c.contains(SECRET) || c.contains(KEY_ID));
    }

    @Test
    void link_failures_areSpecific_andLeaveTheAccountUnlinked() {
        long id = accounts.start("x", "us-east-1").account().getId();
        assertThatThrownBy(() -> accounts.verify(id, "arn:aws:iam::123:role/x", KEY_ID, SECRET))
                .isInstanceOf(PackException.class).extracting("code").isEqualTo(INVALID_INPUT);

        aws.dryRunAllowed = false;
        assertThatThrownBy(() -> accounts.verify(id, ROLE, KEY_ID, SECRET)).extracting("code").isEqualTo(CLOUD_PERMISSION);

        aws.dryRunAllowed = true;
        aws.accountId = "999999999999";
        assertThatThrownBy(() -> accounts.verify(id, ROLE, KEY_ID, SECRET)).extracting("code").isEqualTo(CLOUD_AUTH);

        aws.assumeFails = new AwsGateway.AwsException("InvalidClientTokenId", "bad key", null);
        assertThatThrownBy(() -> accounts.verify(id, ROLE, KEY_ID, SECRET))
                .hasMessageContaining("AWS rejected the access key").extracting("code").isEqualTo(CLOUD_AUTH);
        assertThat(accountRepo.findById(id).orElseThrow().getStatus()).isEqualTo(CloudAccount.Status.FAILED);
        assertThatThrownBy(() -> machines.estimate(id, request())).extracting("code").isEqualTo(CLOUD_NOT_LINKED);
    }

    // ── Estimate + confirmation ──────────────────────────────────────────────

    @Test
    void estimate_usesLivePrices_andValidatesTheRequest() {
        CloudAccount a = linked();
        AwsMachines.Estimate e = machines.estimate(a.getId(), request());
        assertThat(e.hourlyUsd()).isEqualTo(0.8048);
        assertThat(e.diskMonthlyUsd()).isEqualTo(12.0);
        assertThat(e.alwaysOnMonthlyUsd()).isEqualTo(Math.round((0.8048 * 730 + 12.0) * 100) / 100.0);
        assertThat(e.notes()).anyMatch(n -> n.contains("even when stopped")).anyMatch(n -> n.contains("60 idle minutes"));

        for (String cidr : List.of("0.0.0.0/0", "203.0.0.0/16", "203.0.113.7", "300.1.1.1/32")) {
            assertThatThrownBy(() -> machines.estimate(a.getId(), new AwsMachines.ProvisionRequest(null, "us-east-1",
                    "g6.xlarge", 150, cidr, 60, null, null))).as(cidr).extracting("code").isEqualTo(INVALID_INPUT);
        }
        assertThatThrownBy(() -> machines.estimate(a.getId(), new AwsMachines.ProvisionRequest(null, "us-east-1",
                "p5.48xlarge", 150, "203.0.113.7/32", 60, null, null))).hasMessageContaining("listed GPU instance types");
        assertThatThrownBy(() -> machines.estimate(a.getId(), new AwsMachines.ProvisionRequest(null, "us-east-1",
                "g6.xlarge", 50, "203.0.113.7/32", 60, null, null))).hasMessageContaining("100–2000");

        aws.hourly = java.util.Optional.empty();
        assertThatThrownBy(() -> machines.estimate(a.getId(), request())).extracting("code").isEqualTo(PRICE_UNAVAILABLE);
        assertThat(aws.count("run ")).isZero();
    }

    @Test
    void provision_requiresAFreshAcknowledgedEstimate_usedOnce() {
        CloudAccount a = linked();
        assertThatThrownBy(() -> machines.provision(a.getId(), "made-up", true, "user")).extracting("code").isEqualTo(ESTIMATE_REQUIRED);

        AwsMachines.Estimate e = machines.estimate(a.getId(), request());
        assertThatThrownBy(() -> machines.provision(a.getId(), e.id(), false, "user"))
                .hasMessageContaining("accept the estimated AWS charges");

        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertThatThrownBy(() -> machines.provision(a.getId(), e.id(), true, "user")).extracting("code").isEqualTo(ESTIMATE_REQUIRED);
        assertThat(aws.count("run ")).isZero();
        assertThat(targetRepo.count()).isZero();

        AwsMachines.Estimate fresh = machines.estimate(a.getId(), request());
        machines.provision(a.getId(), fresh.id(), true, "user");
        assertThatThrownBy(() -> machines.provision(a.getId(), fresh.id(), true, "user"))
                .as("one estimate → at most one VM").extracting("code").isEqualTo(ESTIMATE_REQUIRED);
        assertThat(aws.count("run ")).isEqualTo(1);
    }

    // ── Provision ────────────────────────────────────────────────────────────

    @Test
    void provision_pinsTheHostKeyFromTheConsole_andTheDeployPathCanConnect() {
        CloudAccount a = linked();
        ComputeTarget t = provisioned(a);

        assertThat(t.getStatus()).isEqualTo("HOSTKEY_CONFIRMED");
        assertThat(t.getHost()).startsWith("198.51.100.");
        assertThat(t.getHostKey()).isEqualTo(t.getHost() + " ssh-ed25519 " + aws.hostKeyBlob);
        assertThat(t.getHostKeyFingerprint()).isEqualTo(Ssh.fingerprint(aws.hostKeyBlob));
        assertThat(t.getUsername()).isEqualTo("ubuntu");
        assertThat(t.getCloudMeta()).containsEntry("phase", "READY").containsEntry("amiId", "ami-0123456789abcdef0")
                .containsEntry("instanceType", "g6.xlarge");

        AwsGateway.RunSpec spec = aws.launched.get(0);
        assertThat(spec.tags()).containsEntry("ananke-managed", "true").containsEntry("ananke-link", a.getLinkTag())
                .containsEntry("ananke-target", String.valueOf(t.getId()));
        assertThat(spec.userData()).contains("ssh-ed25519 " + FakeAws.ed25519Blob(1)).doesNotContain("PRIVATE KEY");
        assertThat(spec.diskGiB()).isEqualTo(150);
        assertThat(aws.ingress).singleElement().satisfies(i -> assertThat(i[1]).isEqualTo("203.0.113.7/32"));

        // The existing SSH deploy path takes over unchanged: pinned key, stored private key.
        SshCredentials c = new SshComputeProvider(targetRepo, vault, null, "ssh-keyscan", "ssh-keygen").credentials(t);
        assertThat(c.knownHostsLine()).isEqualTo(t.getHostKey());
        assertThat(c.privateKey()).contains("TESTPRIVATEKEYMATERIAL");

        assertThat(auditRepo.findAll()).extracting(AgentAuditEntry::getEvent)
                .containsExactly(AgentAuditEntry.Event.CONFIRMED, AgentAuditEntry.Event.EXECUTED);
        assertThat(auditRepo.findAll().get(0).getCostEstimate()).startsWith("$0.8048/h");
    }

    @Test
    void hostKeyNeverAppears_terminatesAndCleansUp_noTofu() {
        aws.consoleHasKey = false;
        CloudAccount a = linked();
        ComputeTarget t = provisioned(a);

        assertThat(t.getStatus()).isEqualTo("ERROR");
        assertThat(t.getHostKey()).isNull();
        assertThat((String) t.getCloudMeta().get("error")).contains("did not appear in its console output within 1 minute,")
                .containsOnlyOnce("terminated");
        assertThat(t.getCloudMeta()).containsEntry("state", "terminated");
        assertThat(aws.calls).contains("terminate " + t.getCloudInstanceId(), "deleteSg sg-1");
        assertThat(auditRepo.findAll()).extracting(AgentAuditEntry::getEvent).endsWith(AgentAuditEntry.Event.FAILED);
        assertThatThrownBy(() -> new SshComputeProvider(targetRepo, vault, null, "a", "b").credentials(t))
                .extracting("code").isEqualTo(SSH_UNREACHABLE);
    }

    @Test
    void launchFailure_deletesTheSecurityGroup() {
        aws.runFails = new AwsGateway.AwsException("InsufficientInstanceCapacity", "ec2:RunInstances failed: InsufficientInstanceCapacity", null);
        ComputeTarget t = provisioned(linked());
        assertThat(t.getStatus()).isEqualTo("ERROR");
        assertThat((String) t.getCloudMeta().get("error")).contains("InsufficientInstanceCapacity");
        assertThat(aws.securityGroups).isEmpty();
        assertThat(aws.count("terminate")).isZero();
    }

    @Test
    void hostKeyParsing_prefersEd25519_andRejectsMangledBlobs() {
        String good = FakeAws.ed25519Blob(3);
        String console = "-----BEGIN SSH HOST KEY KEYS-----\nssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGarbageGarbageGarbageGarbage root\n"
                + "-----END SSH HOST KEY KEYS-----\nreboot\n-----BEGIN SSH HOST KEY KEYS-----\nssh-ed25519 " + good
                + " root@x\n-----END SSH HOST KEY KEYS-----\n";
        assertThat(AwsMachines.hostKeyFromConsole(console)).get().satisfies(k -> assertThat(k[1]).isEqualTo(good));
        assertThat(AwsMachines.hostKeyFromConsole("ssh-ed25519 " + good)).as("outside the cloud-init block").isEmpty();
        assertThat(AwsMachines.hostKeyFromConsole("-----BEGIN SSH HOST KEY KEYS-----\nssh-ed25519 "
                + FakeAws.ed25519Blob(3).replace("AAAAC3", "AAAAB3") + "\n-----END SSH HOST KEY KEYS-----")).isEmpty();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    void stopThenStart_keepsThePinnedKey_andFollowsTheNewIp() {
        ComputeTarget t = provisioned(linked());
        String firstIp = t.getHost();

        assertThat(machines.stop(t.getId(), "user").getStatus()).isEqualTo("STOPPED");
        assertThatThrownBy(() -> new SshComputeProvider(targetRepo, vault, null, "a", "b")
                .credentials(targetRepo.findById(t.getId()).orElseThrow())).extracting("code").isEqualTo(SSH_UNREACHABLE);

        machines.start(t.getId(), "user");
        ComputeTarget after = targetRepo.findById(t.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("HOSTKEY_CONFIRMED");
        assertThat(after.getHost()).isNotEqualTo(firstIp);
        assertThat(after.getHostKey()).isEqualTo(after.getHost() + " ssh-ed25519 " + aws.hostKeyBlob);
        assertThat(auditRepo.findAll()).extracting(AgentAuditEntry::getTool)
                .contains("aws_stop_vm", "aws_start_vm");
    }

    @Test
    void idleSweep_stopsOnlyIdleMachines() {
        ComputeTarget t = provisioned(linked());
        assertThat(machines.idleSweep()).isEmpty();                      // just started

        clock.now = clock.now.plus(Duration.ofMinutes(61));
        assertThat(machines.idleSweep()).containsExactly(t.getId());
        assertThat(targetRepo.findById(t.getId()).orElseThrow().getStatus()).isEqualTo("STOPPED");
        assertThat(auditRepo.findAll()).anySatisfy(e -> assertThat(e.getAgent()).isEqualTo("system:idle-auto-stop"));

        ComputeTarget never = provisioned(accountRepo.findAll().get(0));
        never.setIdleStopMinutes(0);
        targetRepo.save(never);
        clock.now = clock.now.plus(Duration.ofDays(1));
        assertThat(machines.idleSweep()).isEmpty();
    }

    @Test
    void removingTheOwningPack_terminatesItsVm_andForgetsTheMachine() {
        ComputeTarget t = provisioned(linked());
        Pack pack = new Pack();
        pack.setName("qwen");
        pack.setHfRepo("Qwen/Qwen3-0.6B");
        pack.setHfRevision("c1899de289a04d12100db370d81485cdf75e47ca");
        pack.setProfile(Map.of("compatibility", "supported"));
        Pack saved = packRepo.save(pack);
        t.setOwnerPackId(saved.getId());
        targetRepo.save(t);

        Pack other = new Pack();
        other.setId(saved.getId() + 1000);
        machines.afterPackRemoved(other, t);
        assertThat(aws.count("terminate")).as("not the owner").isZero();

        assertThatThrownBy(() -> {
            saved.setComputeTargetId(t.getId());
            packRepo.save(saved);
            machines.terminate(t.getId(), "user");
        }).hasMessageContaining("Remove the Packs on this machine first");
        packRepo.deleteAll();

        machines.afterPackRemoved(saved, t);
        assertThat(aws.calls).contains("terminate " + t.getCloudInstanceId(), "deleteSg sg-1");
        assertThat(targetRepo.findById(t.getId())).isEmpty();
        assertThat(vault.has(t.scope(), SshComputeProvider.KEY_KIND)).isFalse();
        assertThat(auditRepo.findAll()).anySatisfy(e -> {
            assertThat(e.getTool()).isEqualTo("aws_terminate_vm");
            assertThat(e.getPackId()).isEqualTo(saved.getId());
        });
    }

    @Test
    void orphans_areListedAcrossRegions_andOnlyOurOwnCanBeTerminated() {
        CloudAccount a = linked();
        ComputeTarget tracked = provisioned(a);
        // An instance launched by this link whose target row is gone (e.g. a crash mid-provision).
        aws.instances.put("i-0aaaaaaaaaaaaaaaa", new AwsGateway.Instance("i-0aaaaaaaaaaaaaaaa", "running", "1.2.3.4", "g5.xlarge",
                Map.of("ananke-managed", "true", "ananke-link", a.getLinkTag())));
        aws.instances.put("i-0bbbbbbbbbbbbbbbb", new AwsGateway.Instance("i-0bbbbbbbbbbbbbbbb", "running", "1.2.3.5", "g5.xlarge",
                Map.of("Name", "someone-elses")));

        assertThat(machines.orphans(a.getId())).extracting(AwsMachines.Orphan::instanceId).containsExactly("i-0aaaaaaaaaaaaaaaa");
        assertThat(aws.count("terminate")).as("listing never terminates").isZero();

        assertThatThrownBy(() -> machines.terminateOrphan(a.getId(), "us-east-1", "i-0bbbbbbbbbbbbbbbb", "user"))
                .extracting("code").isEqualTo(CLOUD_PERMISSION);
        assertThatThrownBy(() -> machines.terminateOrphan(a.getId(), "us-east-1", tracked.getCloudInstanceId(), "user"))
                .hasMessageContaining("still tracks");
        machines.terminateOrphan(a.getId(), "us-east-1", "i-0aaaaaaaaaaaaaaaa", "user");
        assertThat(aws.calls).containsOnlyOnce("terminate i-0aaaaaaaaaaaaaaaa");
    }

    @Test
    void unlink_isRefusedWhileMachinesExist_thenRemovesTheKeys() {
        CloudAccount a = linked();
        ComputeTarget t = provisioned(a);
        assertThatThrownBy(() -> accounts.unlink(a.getId())).hasMessageContaining("Terminate this account's machines");
        targetRepo.delete(t);
        accounts.unlink(a.getId());
        assertThat(vault.has(a.scope(), "aws_access_key_id")).isFalse();
        assertThat(accountRepo.findById(a.getId())).isEmpty();
    }

    // ── #7: agent tools (Packs §9.3) ─────────────────────────────────────────

    com.stocktracker.agent.actions.ActionConfirmationService guardFor(com.stocktracker.agent.actions.CloudToolGate gate) {
        return new com.stocktracker.agent.actions.ActionConfirmationService(auditRepo, gate,
                new com.fasterxml.jackson.databind.ObjectMapper(), clock);
    }

    AwsActionToolSource toolSource() {
        com.stocktracker.packs.cloud.EgressIp egress = new com.stocktracker.packs.cloud.EgressIp("unused") {
            @Override public java.util.Optional<String> detect() { return java.util.Optional.of("203.0.113.9"); }
        };
        return new AwsActionToolSource(accounts, machines, egress, null);
    }

    @Test
    void agentTools_appearOnlyOnceAwsIsLinked_andNeverIncludeStopOrTerminate() {
        AwsActionToolSource src = toolSource();
        var gate = new com.stocktracker.agent.actions.CloudToolGate(List.of(src));
        var kit = new com.stocktracker.agent.AgentCapabilities(null, gate, guardFor(gate));

        assertThat(kit.toolbox(com.stocktracker.agent.AgentProfile.PACK_OPS, null, List.of()).names()).isEmpty();
        linked();
        assertThat(kit.toolbox(com.stocktracker.agent.AgentProfile.PACK_OPS, null, List.of()).names()).containsExactly(
                "aws_list_gpu_types", "aws_estimate_gpu_vm", "aws_list_machines", "aws_provision_gpu_vm", "aws_start_vm");
    }

    @Test
    void agentProvision_waitsForAPerson_thenCreatesExactlyWhatWasShown() throws Exception {
        CloudAccount a = linked();
        AwsActionToolSource src = toolSource();
        var gate = new com.stocktracker.agent.actions.CloudToolGate(List.of(src));
        var guard = guardFor(gate);
        var box = new com.stocktracker.agent.AgentCapabilities(null, gate, guard)
                .toolbox(com.stocktracker.agent.AgentProfile.PACK_OPS, null, List.of());
        var om = new com.fasterxml.jackson.databind.ObjectMapper();

        Object est = box.dispatch("aws_estimate_gpu_vm", om.readTree("{\"region\":\"us-east-1\",\"instanceType\":\"g6.xlarge\"}"));
        assertThat(((Map<?, ?>) est).get("hourlyUsd")).isEqualTo(0.8048);
        assertThat(aws.count("run ")).as("an estimate creates nothing").isZero();

        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) box.dispatch("aws_provision_gpu_vm",
                om.readTree("{\"region\":\"us-east-1\",\"instanceType\":\"g6.xlarge\",\"diskGiB\":200}"));
        assertThat(pending).containsEntry("status", "confirmation_required");
        assertThat(pending.get("costEstimate")).asString().startsWith("$0.8048/h").contains("200 GiB disk").contains("SSH from 203.0.113.9/32");
        assertThat(aws.count("run ")).as("nothing until a person confirms").isZero();
        assertThat(targetRepo.count()).isZero();

        guard.confirm((String) pending.get("actionId"));

        assertThat(aws.launched).singleElement().satisfies(spec -> {
            assertThat(spec.instanceType()).isEqualTo("g6.xlarge");
            assertThat(spec.diskGiB()).isEqualTo(200);
        });
        assertThat(aws.ingress).singleElement().satisfies(i -> assertThat(i[1]).isEqualTo("203.0.113.9/32"));
        assertThat(targetRepo.findAll()).singleElement().satisfies(t -> assertThat(t.getStatus()).isEqualTo("HOSTKEY_CONFIRMED"));
        assertThat(auditRepo.findAll()).extracting(e -> e.getAgent() + ":" + e.getTool() + ":" + e.getEvent()).contains(
                "pack-ops:aws_provision_gpu_vm:REQUESTED", "pack-ops:aws_provision_gpu_vm:CONFIRMED",
                "pack-ops:aws_provision_gpu_vm:EXECUTED", "agent:aws_provision_gpu_vm:EXECUTED");
        assertThatThrownBy(() -> guard.confirm((String) pending.get("actionId"))).isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(aws.count("run ")).isEqualTo(1);
    }

    @Test
    void agentStart_isBillable_andRefusedWhenUnlinkedAtConfirmTime() throws Exception {
        CloudAccount a = linked();
        ComputeTarget t = provisioned(a);
        machines.stop(t.getId(), "user");
        AwsActionToolSource src = toolSource();
        var gate = new com.stocktracker.agent.actions.CloudToolGate(List.of(src));
        var guard = guardFor(gate);
        var box = new com.stocktracker.agent.AgentCapabilities(null, gate, guard)
                .toolbox(com.stocktracker.agent.AgentProfile.PACK_OPS, null, List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) box.dispatch("aws_start_vm",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"targetId\":" + t.getId() + "}"));
        assertThat(pending).containsEntry("status", "confirmation_required");
        assertThat(pending.get("costEstimate")).asString().contains("$0.8048/hour");
        long starts = aws.count("start ");

        a.setStatus(CloudAccount.Status.FAILED);           // e.g. the user unlinked / the key was revoked
        accountRepo.save(a);
        assertThatThrownBy(() -> guard.confirm((String) pending.get("actionId"))).hasMessageContaining("no longer linked");
        assertThat(aws.count("start ")).isEqualTo(starts);
    }
}
