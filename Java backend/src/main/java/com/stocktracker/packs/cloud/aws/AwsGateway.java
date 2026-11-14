package com.stocktracker.packs.cloud.aws;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every AWS API call Ananke makes (Packs build doc §7), behind one seam so the
 * orchestration is tested offline with a fake and the SDK calls against moto.
 * Nothing here is reachable by a model directly; agents only see fixed tools built on top.
 */
public interface AwsGateway {

    /** Short-lived (or base) credentials. Never persisted except base keys in SecretVault. */
    record Creds(String accessKeyId, String secretAccessKey, String sessionToken, Instant expiresAt) {
        @Override public String toString() { return "Creds[" + (accessKeyId == null ? "" : accessKeyId.substring(0, Math.min(4, accessKeyId.length()))) + "…]"; }
    }

    record Identity(String accountId, String arn) { }

    record RunSpec(String amiId, String instanceType, String securityGroupId, int diskGiB,
                   String userData, Map<String, String> tags) { }

    record Instance(String id, String state, String publicIp, String instanceType, Map<String, String> tags) { }

    /** sts:AssumeRole with the external ID; returns temporary credentials. */
    Creds assumeRole(Creds base, String region, String roleArn, String externalId, String sessionName);

    Identity callerIdentity(Creds creds, String region);

    String ssmParameter(Creds creds, String region, String name);

    /** RunInstances with DryRun=true: true when permitted (AWS answers DryRunOperation), false when UnauthorizedOperation. */
    boolean dryRunLaunch(Creds creds, String region, RunSpec spec);

    String defaultVpcId(Creds creds, String region);

    /** Regions enabled for this account (orphan scan). */
    List<String> enabledRegions(Creds creds, String region);

    String createSecurityGroup(Creds creds, String region, String vpcId, String name, String description,
                               Map<String, String> tags);

    void authorizeSshIngress(Creds creds, String region, String securityGroupId, String cidr);

    void deleteSecurityGroup(Creds creds, String region, String securityGroupId);

    String runInstance(Creds creds, String region, RunSpec spec);

    Optional<Instance> describeInstance(Creds creds, String region, String instanceId);

    List<Instance> instancesTagged(Creds creds, String region, String tagKey, String tagValue);

    /** Decoded console output (latest), or "" when not yet available. */
    String consoleOutput(Creds creds, String region, String instanceId);

    void startInstance(Creds creds, String region, String instanceId);

    void stopInstance(Creds creds, String region, String instanceId);

    void terminateInstance(Creds creds, String region, String instanceId);

    /** Linux on-demand USD/hour from the AWS Pricing API, if listed. */
    Optional<Double> onDemandHourlyUsd(Creds creds, String region, String instanceType);

    /** gp3 USD per GB-month from the AWS Pricing API, if listed. */
    Optional<Double> gp3GbMonthUsd(Creds creds, String region);

    /** Problem talking to AWS; {@code code} is the AWS error code when there is one. */
    class AwsException extends RuntimeException {
        private final String code;

        public AwsException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
