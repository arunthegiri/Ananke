package com.stocktracker.packs.cloud.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link AwsGateway} on the AWS SDK v2. {@code endpoint} overrides every service endpoint
 * (moto in tests); null = real AWS. A client is built per call from the caller's credentials —
 * there are no ambient/default-chain credentials anywhere in this class (§9.3, §7.1).
 */
public class SdkAwsGateway implements AwsGateway {

    /** The Pricing API is only served from a few regions; us-east-1 is one. */
    static final String PRICING_REGION = "us-east-1";

    private final URI endpoint;
    private final SdkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public SdkAwsGateway(URI endpoint) {
        this.endpoint = endpoint;
        this.http = UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(10))
                .socketTimeout(Duration.ofSeconds(30))
                .build();
    }

    private <B extends AwsClientBuilder<B, C>, C> C client(B builder, Creds creds, String region) {
        AwsCredentials c = creds.sessionToken() == null
                ? AwsBasicCredentials.create(creds.accessKeyId(), creds.secretAccessKey())
                : AwsSessionCredentials.create(creds.accessKeyId(), creds.secretAccessKey(), creds.sessionToken());
        builder.region(Region.of(region)).credentialsProvider(StaticCredentialsProvider.create(c));
        if (endpoint != null) builder.endpointOverride(endpoint);
        return builder.build();
    }

    private Ec2Client ec2(Creds c, String region) {
        return client(Ec2Client.builder().httpClient(http), c, region);
    }

    private static <T> T call(String what, java.util.function.Supplier<T> fn) {
        try {
            return fn.get();
        } catch (AwsServiceException e) {
            String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            String msg = e.awsErrorDetails() == null ? e.getMessage() : e.awsErrorDetails().errorMessage();
            throw new AwsException(code, what + " failed: " + (code == null ? "" : code + ": ") + msg, e);
        } catch (SdkException e) {
            throw new AwsException(null, what + " failed: " + e.getMessage(), e);
        }
    }

    // ── STS / SSM ─────────────────────────────────────────────────────────────

    @Override
    public Creds assumeRole(Creds base, String region, String roleArn, String externalId, String sessionName) {
        return call("sts:AssumeRole", () -> {
            try (StsClient sts = client(StsClient.builder().httpClient(http), base, region)) {
                Credentials c = sts.assumeRole(AssumeRoleRequest.builder()
                        .roleArn(roleArn).externalId(externalId).roleSessionName(sessionName)
                        .durationSeconds(3600).build()).credentials();
                return new Creds(c.accessKeyId(), c.secretAccessKey(), c.sessionToken(), c.expiration());
            }
        });
    }

    @Override
    public Identity callerIdentity(Creds creds, String region) {
        return call("sts:GetCallerIdentity", () -> {
            try (StsClient sts = client(StsClient.builder().httpClient(http), creds, region)) {
                var r = sts.getCallerIdentity();
                return new Identity(r.account(), r.arn());
            }
        });
    }

    @Override
    public String ssmParameter(Creds creds, String region, String name) {
        return call("ssm:GetParameter", () -> {
            try (SsmClient ssm = client(SsmClient.builder().httpClient(http), creds, region)) {
                return ssm.getParameter(b -> b.name(name)).parameter().value();
            }
        });
    }

    // ── EC2 ───────────────────────────────────────────────────────────────────

    private RunInstancesRequest runRequest(Ec2Client ec2, RunSpec spec, boolean dryRun) {
        String rootDevice = "/dev/sda1";
        try {
            var images = ec2.describeImages(b -> b.imageIds(spec.amiId())).images();
            if (!images.isEmpty() && images.get(0).rootDeviceName() != null) rootDevice = images.get(0).rootDeviceName();
        } catch (AwsServiceException ignored) {
            // fall back to the DLAMI default root device
        }
        List<Tag> tags = spec.tags().entrySet().stream().map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build()).toList();
        List<TagSpecification> tagSpecs = List.of(ResourceType.INSTANCE, ResourceType.VOLUME, ResourceType.NETWORK_INTERFACE)
                .stream().map(t -> TagSpecification.builder().resourceType(t).tags(tags).build()).toList();
        return RunInstancesRequest.builder()
                .imageId(spec.amiId())
                .instanceType(spec.instanceType())
                .minCount(1).maxCount(1)
                .securityGroupIds(spec.securityGroupId() == null ? List.of() : List.of(spec.securityGroupId()))
                .userData(spec.userData() == null ? null
                        : Base64.getEncoder().encodeToString(spec.userData().getBytes(StandardCharsets.UTF_8)))
                .blockDeviceMappings(BlockDeviceMapping.builder().deviceName(rootDevice)
                        .ebs(EbsBlockDevice.builder().volumeSize(spec.diskGiB()).volumeType(VolumeType.GP3)
                                .deleteOnTermination(true).encrypted(true).build()).build())
                .metadataOptions(InstanceMetadataOptionsRequest.builder().httpTokens(HttpTokensState.REQUIRED).build())
                .instanceInitiatedShutdownBehavior(ShutdownBehavior.STOP)
                .tagSpecifications(tagSpecs)
                .dryRun(dryRun)
                .build();
    }

    @Override
    public boolean dryRunLaunch(Creds creds, String region, RunSpec spec) {
        try (Ec2Client ec2 = ec2(creds, region)) {
            ec2.runInstances(runRequest(ec2, spec, true));
            return true;    // some emulators accept the dry run as a real no-op call
        } catch (AwsServiceException e) {
            String code = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
            if ("DryRunOperation".equals(code)) return true;
            if ("UnauthorizedOperation".equals(code)) return false;
            throw new AwsException(code, "ec2:RunInstances (dry run) failed: " + code + ": "
                    + (e.awsErrorDetails() == null ? e.getMessage() : e.awsErrorDetails().errorMessage()), e);
        } catch (SdkException e) {
            throw new AwsException(null, "ec2:RunInstances (dry run) failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String defaultVpcId(Creds creds, String region) {
        return call("ec2:DescribeVpcs", () -> {
            try (Ec2Client ec2 = ec2(creds, region)) {
                return ec2.describeVpcs(b -> b.filters(Filter.builder().name("is-default").values("true").build()))
                        .vpcs().stream().findFirst().map(Vpc::vpcId)
                        .orElseThrow(() -> new AwsException("NoDefaultVpc",
                                "This region has no default VPC; create one (VPC console → Actions → Create default VPC).", null));
            }
        });
    }

    @Override
    public List<String> enabledRegions(Creds creds, String region) {
        return call("ec2:DescribeRegions", () -> {
            try (Ec2Client ec2 = ec2(creds, region)) {
                return ec2.describeRegions().regions().stream().map(software.amazon.awssdk.services.ec2.model.Region::regionName).sorted().toList();
            }
        });
    }

    @Override
    public String createSecurityGroup(Creds creds, String region, String vpcId, String name, String description,
                                      Map<String, String> tags) {
        return call("ec2:CreateSecurityGroup", () -> {
            try (Ec2Client ec2 = ec2(creds, region)) {
                return ec2.createSecurityGroup(b -> b.groupName(name).description(description).vpcId(vpcId)
                        .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.SECURITY_GROUP)
                                .tags(tags.entrySet().stream().map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build()).toList())
                                .build())).groupId();
            }
        });
    }

    @Override
    public void authorizeSshIngress(Creds creds, String region, String securityGroupId, String cidr) {
        call("ec2:AuthorizeSecurityGroupIngress", () -> {
            try (Ec2Client ec2 = ec2(creds, region)) {
                return ec2.authorizeSecurityGroupIngress(b -> b.groupId(securityGroupId).ipPermissions(IpPermission.builder()
                        .ipProtocol("tcp").fromPort(22).toPort(22)
                        .ipRanges(IpRange.builder().cidrIp(cidr).description("Ananke backend SSH").build()).build()));
            }
        });
    }

    @Override
    public void deleteSecurityGroup(Creds creds, String region, String securityGroupId) {
        call("ec2:DeleteSecurityGroup", () -> {
            try (Ec2Client ec2 = ec2(creds, region)) {
                return ec2.deleteSecurityGroup(b -> b.groupId(securityGroupId));
            }
        });
    }

    @Override
    public String runInstance(Creds creds, String region, RunSpec spec) {
        return call("ec2:RunInstances", () -> {
            try (Ec2Client ec2 = ec2(creds, region)) {
                return ec2.runInstances(runRequest(ec2, spec, false)).instances().get(0).instanceId();
            }
        });
    }

    private static Instance toInstance(software.amazon.awssdk.services.ec2.model.Instance i) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (Tag t : i.tags()) tags.put(t.key(), t.value());
        return new Instance(i.instanceId(), i.state() == null ? null : i.state().nameAsString(),
                i.publicIpAddress(), i.instanceTypeAsString(), tags);
    }

    @Override
    public Optional<Instance> describeInstance(Creds creds, String region, String instanceId) {
        return call("ec2:DescribeInstances", () -> {
            try (Ec2Client ec2 = ec2(creds, region)) {
                return ec2.describeInstances(b -> b.instanceIds(instanceId)).reservations().stream()
                        .flatMap(r -> r.instances().stream()).findFirst().map(SdkAwsGateway::toInstance);
            } catch (AwsServiceException e) {
                if (e.awsErrorDetails() != null && "InvalidInstanceID.NotFound".equals(e.awsErrorDetails().errorCode())) {
                    return Optional.empty();
                }
                throw e;
            }
        });
    }

    @Override
    public List<Instance> instancesTagged(Creds creds, String region, String tagKey, String tagValue) {
        return call("ec2:DescribeInstances", () -> {
            try (Ec2Client ec2 = ec2(creds, region)) {
                List<Instance> out = new ArrayList<>();
                ec2.describeInstancesPaginator(b -> b.filters(
                        Filter.builder().name("tag:" + tagKey).values(tagValue).build(),
                        Filter.builder().name("instance-state-name").values("pending", "running", "stopping", "stopped").build()))
                        .reservations().forEach(r -> r.instances().forEach(i -> out.add(toInstance(i))));
                return out;
            }
        });
    }

    @Override
    public String consoleOutput(Creds creds, String region, String instanceId) {
        return call("ec2:GetConsoleOutput", () -> {
            try (Ec2Client ec2 = ec2(creds, region)) {
                String out;
                try {
                    out = ec2.getConsoleOutput(b -> b.instanceId(instanceId).latest(true)).output();
                } catch (AwsServiceException e) {
                    // "latest" is Nitro-only; older types (and some emulators) only serve the buffered output.
                    out = ec2.getConsoleOutput(b -> b.instanceId(instanceId)).output();
                }
                if (out == null || out.isEmpty()) return "";
                try {
                    return new String(Base64.getDecoder().decode(out), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException notBase64) {
                    return out;
                }
            }
        });
    }

    @Override
    public void startInstance(Creds creds, String region, String instanceId) {
        call("ec2:StartInstances", () -> { try (Ec2Client e = ec2(creds, region)) { return e.startInstances(b -> b.instanceIds(instanceId)); } });
    }

    @Override
    public void stopInstance(Creds creds, String region, String instanceId) {
        call("ec2:StopInstances", () -> { try (Ec2Client e = ec2(creds, region)) { return e.stopInstances(b -> b.instanceIds(instanceId)); } });
    }

    @Override
    public void terminateInstance(Creds creds, String region, String instanceId) {
        call("ec2:TerminateInstances", () -> { try (Ec2Client e = ec2(creds, region)) { return e.terminateInstances(b -> b.instanceIds(instanceId)); } });
    }

    // ── Pricing ───────────────────────────────────────────────────────────────

    private Optional<Double> price(Creds creds, Map<String, String> filters, String unit) {
        return call("pricing:GetProducts", () -> {
            try (PricingClient pricing = client(PricingClient.builder().httpClient(http), creds, PRICING_REGION)) {
                var req = GetProductsRequest.builder().serviceCode("AmazonEC2").maxResults(20)
                        .filters(filters.entrySet().stream().map(e -> software.amazon.awssdk.services.pricing.model.Filter.builder()
                                .type(FilterType.TERM_MATCH).field(e.getKey()).value(e.getValue()).build()).toList())
                        .build();
                return firstOnDemandPrice(pricing.getProducts(req).priceList(), unit, mapper);
            }
        });
    }

    /** Parses Pricing API price-list documents: terms.OnDemand.*.priceDimensions.*.pricePerUnit.USD. */
    static Optional<Double> firstOnDemandPrice(List<String> priceList, String unit, ObjectMapper mapper) {
        for (String doc : priceList) {
            try {
                JsonNode root = mapper.readTree(doc);
                for (JsonNode term : iterable(root.path("terms").path("OnDemand"))) {
                    for (JsonNode dim : iterable(term.path("priceDimensions"))) {
                        if (unit != null && !unit.equalsIgnoreCase(dim.path("unit").asText())) continue;
                        String usd = dim.path("pricePerUnit").path("USD").asText("");
                        if (!usd.isEmpty()) {
                            double v = Double.parseDouble(usd);
                            if (v > 0) return Optional.of(v);
                        }
                    }
                }
            } catch (Exception ignored) {
                // a malformed document never becomes a price
            }
        }
        return Optional.empty();
    }

    private static Iterable<JsonNode> iterable(JsonNode objectNode) {
        List<JsonNode> out = new ArrayList<>();
        objectNode.fields().forEachRemaining(e -> out.add(e.getValue()));
        return out;
    }

    @Override
    public Optional<Double> onDemandHourlyUsd(Creds creds, String region, String instanceType) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("instanceType", instanceType);
        f.put("regionCode", region);
        f.put("operatingSystem", "Linux");
        f.put("tenancy", "Shared");
        f.put("preInstalledSw", "NA");
        f.put("capacitystatus", "Used");
        return price(creds, f, "Hrs");
    }

    @Override
    public Optional<Double> gp3GbMonthUsd(Creds creds, String region) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("productFamily", "Storage");
        f.put("volumeApiName", "gp3");
        f.put("regionCode", region);
        return price(creds, f, "GB-Mo");
    }
}
