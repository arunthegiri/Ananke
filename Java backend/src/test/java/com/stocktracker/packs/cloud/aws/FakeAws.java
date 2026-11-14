package com.stocktracker.packs.cloud.aws;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory EC2/STS/SSM/Pricing double that records every call (offline orchestration tests). */
class FakeAws implements AwsGateway {

    static String ed25519Blob(int seed) {
        byte[] type = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(4 + type.length + 4 + 32);
        b.putInt(type.length).put(type).putInt(32);
        for (int i = 0; i < 32; i++) b.put((byte) (seed + i));
        return Base64.getEncoder().encodeToString(b.array());
    }

    final List<String> calls = new CopyOnWriteArrayList<>();
    final Map<String, Instance> instances = new LinkedHashMap<>();
    final Map<String, Integer> describesUntilRunning = new LinkedHashMap<>();
    final List<RunSpec> launched = new ArrayList<>();
    final List<String[]> ingress = new ArrayList<>();
    final List<String> securityGroups = new ArrayList<>();
    String accountId = "123456789012";
    boolean dryRunAllowed = true;
    AwsException assumeFails;
    RuntimeException runFails;
    Optional<Double> hourly = Optional.of(0.8048);
    Optional<Double> gp3 = Optional.of(0.08);
    String hostKeyBlob = ed25519Blob(7);
    boolean consoleHasKey = true;
    int ipCounter = 10;
    List<String> regions = List.of("eu-west-1", "us-east-1");

    @Override
    public Creds assumeRole(Creds base, String region, String roleArn, String externalId, String sessionName) {
        calls.add("assumeRole " + roleArn + " " + externalId);
        if (assumeFails != null) throw assumeFails;
        return new Creds("ASIAFAKE", "secret", "token", Instant.now().plusSeconds(3600));
    }

    @Override public Identity callerIdentity(Creds c, String region) { calls.add("identity"); return new Identity(accountId, "arn:aws:sts::" + accountId + ":assumed-role/AnankePacksRole/x"); }
    @Override public String ssmParameter(Creds c, String region, String name) { calls.add("ssm " + name); return "ami-0123456789abcdef0"; }
    @Override public boolean dryRunLaunch(Creds c, String region, RunSpec spec) { calls.add("dryRun " + spec.tags()); return dryRunAllowed; }
    @Override public String defaultVpcId(Creds c, String region) { return "vpc-1"; }
    @Override public List<String> enabledRegions(Creds c, String region) { return regions; }

    @Override
    public synchronized String createSecurityGroup(Creds c, String region, String vpcId, String name, String d, Map<String, String> tags) {
        String id = "sg-" + (securityGroups.size() + 1);
        securityGroups.add(id);
        calls.add("createSg " + id + " " + tags);
        return id;
    }

    @Override public synchronized void authorizeSshIngress(Creds c, String region, String sg, String cidr) { ingress.add(new String[]{sg, cidr}); }
    @Override public synchronized void deleteSecurityGroup(Creds c, String region, String sg) { calls.add("deleteSg " + sg); securityGroups.remove(sg); }

    @Override
    public synchronized String runInstance(Creds c, String region, RunSpec spec) {
        calls.add("run " + spec.instanceType());
        if (runFails != null) throw runFails;
        launched.add(spec);
        String id = "i-0000000000000000" + instances.size();
        instances.put(id, new Instance(id, "pending", null, spec.instanceType(), spec.tags()));
        describesUntilRunning.put(id, 1);
        return id;
    }

    @Override
    public synchronized Optional<Instance> describeInstance(Creds c, String region, String id) {
        Instance i = instances.get(id);
        if (i == null) return Optional.empty();
        if ("pending".equals(i.state())) {
            int left = describesUntilRunning.getOrDefault(id, 0);
            if (left <= 0) {
                i = new Instance(id, "running", "198.51.100." + (ipCounter++), i.instanceType(), i.tags());
                instances.put(id, i);
            } else {
                describesUntilRunning.put(id, left - 1);
            }
        }
        return Optional.of(i);
    }

    @Override
    public synchronized List<Instance> instancesTagged(Creds c, String region, String k, String v) {
        if (!"us-east-1".equals(region)) return List.of();
        return instances.values().stream().filter(i -> v.equals(i.tags().get(k)) && !"terminated".equals(i.state())).toList();
    }

    @Override
    public String consoleOutput(Creds c, String region, String id) {
        if (!consoleHasKey) return "[    0.000000] Linux version 6.8\nec2: booting\n";
        return "[   12.3] cloud-init[1234]: ...\n"
                + "ec2: -----BEGIN SSH HOST KEY FINGERPRINTS-----\nec2: 256 SHA256:abc root@ip (ED25519)\nec2: -----END SSH HOST KEY FINGERPRINTS-----\n"
                + "-----BEGIN SSH HOST KEY KEYS-----\n"
                + "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBNotARealKeyButLongEnoughToMatchTheRegex= root@ip\n"
                + "ssh-ed25519 " + hostKeyBlob + " root@ip\n"
                + "-----END SSH HOST KEY KEYS-----\n";
    }

    private synchronized void state(String id, String state, boolean newIp) {
        Instance i = instances.get(id);
        instances.put(id, new Instance(id, state, newIp ? "198.51.100." + (ipCounter++) : ("stopped".equals(state) ? null : i.publicIp()),
                i.instanceType(), i.tags()));
    }

    @Override public void startInstance(Creds c, String r, String id) { calls.add("start " + id); state(id, "running", true); }
    @Override public void stopInstance(Creds c, String r, String id) { calls.add("stop " + id); state(id, "stopped", false); }
    @Override public void terminateInstance(Creds c, String r, String id) { calls.add("terminate " + id); state(id, "terminated", false); }
    @Override public Optional<Double> onDemandHourlyUsd(Creds c, String r, String t) { calls.add("price " + t + " " + r); return hourly; }
    @Override public Optional<Double> gp3GbMonthUsd(Creds c, String r) { return gp3; }

    long count(String prefix) {
        return calls.stream().filter(s -> s.startsWith(prefix)).count();
    }
}
