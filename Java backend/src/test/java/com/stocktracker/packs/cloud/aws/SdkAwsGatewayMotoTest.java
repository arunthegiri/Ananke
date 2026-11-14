package com.stocktracker.packs.cloud.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.packs.cloud.aws.AwsGateway.Creds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real AWS SDK calls in {@link SdkAwsGateway}, against moto (a local AWS emulator in a
 * throwaway container). Proves request shapes, tag specs, DryRun handling and parsing —
 * not AWS's IAM evaluation or real pricing (moto implements neither). No real AWS is called.
 */
@Testcontainers
class SdkAwsGatewayMotoTest {

    @Container
    static final GenericContainer<?> MOTO = new GenericContainer<>("motoserver/moto:5.2.5")
            .withExposedPorts(5000).waitingFor(Wait.forHttp("/moto-api/").forStatusCode(200));

    static final String REGION = "us-east-1";
    static URI endpoint;
    static SdkAwsGateway gw;
    static final Creds BASE = new Creds("AKIA" + "MOTO".repeat(4), "fake-secret-".repeat(4), null, null);

    @BeforeAll
    static void start() {
        endpoint = URI.create("http://" + MOTO.getHost() + ":" + MOTO.getMappedPort(5000));
        gw = new SdkAwsGateway(endpoint);
    }

    static Ec2Client rawEc2() {
        return Ec2Client.builder().endpointOverride(endpoint).region(Region.of(REGION)).httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b"))).build();
    }

    static String someAmi() {
        try (Ec2Client ec2 = rawEc2()) {
            return ec2.describeImages().images().get(0).imageId();
        }
    }

    @Test
    void assumeRoleThenEc2Lifecycle_withTagsDryRunAndConsole() {
        Creds role = gw.assumeRole(BASE, REGION, "arn:aws:iam::123456789012:role/AnankePacksRole", "ananke-ext", "ananke-1");
        assertThat(role.sessionToken()).isNotBlank();
        assertThat(role.expiresAt()).isNotNull();
        assertThat(gw.callerIdentity(role, REGION).accountId()).matches("\\d{12}");

        Map<String, String> tags = Map.of("ananke-managed", "true", "ananke-link", "abc123", "ananke-target", "1");
        String vpc = gw.defaultVpcId(role, REGION);
        assertThat(vpc).startsWith("vpc-");
        String sg = gw.createSecurityGroup(role, REGION, vpc, "ananke-target-1-x", "test", tags);
        gw.authorizeSshIngress(role, REGION, sg, "203.0.113.7/32");
        try (Ec2Client ec2 = rawEc2()) {
            var group = ec2.describeSecurityGroups(b -> b.groupIds(sg)).securityGroups().get(0);
            assertThat(group.ipPermissions()).singleElement().satisfies(p -> {
                assertThat(p.fromPort()).isEqualTo(22);
                assertThat(p.ipRanges()).singleElement().satisfies(r -> assertThat(r.cidrIp()).isEqualTo("203.0.113.7/32"));
            });
            assertThat(group.tags()).anySatisfy(t -> assertThat(t.key() + "=" + t.value()).isEqualTo("ananke-managed=true"));
        }

        String ami = someAmi();
        AwsGateway.RunSpec spec = new AwsGateway.RunSpec(ami, "g6.xlarge", sg, 150, AwsMachines.userData("ssh-ed25519 AAAA test"), tags);
        assertThat(gw.dryRunLaunch(role, REGION, spec)).isTrue();
        try (Ec2Client ec2 = rawEc2()) {
            assertThat(ec2.describeInstances().reservations().stream().mapToInt(r -> r.instances().size()).sum())
                    .as("a dry run launches nothing").isZero();
        }

        String id = gw.runInstance(role, REGION, spec);
        AwsGateway.Instance i = gw.describeInstance(role, REGION, id).orElseThrow();
        assertThat(i.instanceType()).isEqualTo("g6.xlarge");
        assertThat(i.tags()).containsAllEntriesOf(tags);
        try (Ec2Client ec2 = rawEc2()) {
            var inst = ec2.describeInstances(b -> b.instanceIds(id)).reservations().get(0).instances().get(0);
            assertThat(inst.metadataOptions().httpTokensAsString()).isEqualTo("required");
            assertThat(inst.securityGroups()).anySatisfy(g -> assertThat(g.groupId()).isEqualTo(sg));
            var volumeId = inst.blockDeviceMappings().get(0).ebs().volumeId();
            var vol = ec2.describeVolumes(b -> b.volumeIds(volumeId)).volumes().get(0);
            assertThat(vol.size()).isEqualTo(150);
            assertThat(vol.volumeTypeAsString()).isEqualTo("gp3");
            assertThat(vol.encrypted()).isTrue();
        }
        assertThat(gw.instancesTagged(role, REGION, "ananke-link", "abc123")).extracting(AwsGateway.Instance::id).containsExactly(id);
        assertThat(gw.instancesTagged(role, REGION, "ananke-link", "other")).isEmpty();
        assertThat(gw.consoleOutput(role, REGION, id)).isNotNull();
        assertThat(gw.enabledRegions(role, REGION)).contains("us-east-1", "eu-west-1");

        gw.stopInstance(role, REGION, id);
        assertThat(gw.describeInstance(role, REGION, id).orElseThrow().state()).isIn("stopping", "stopped");
        gw.startInstance(role, REGION, id);
        gw.terminateInstance(role, REGION, id);
        assertThat(gw.describeInstance(role, REGION, id).orElseThrow().state()).isIn("shutting-down", "terminated");
        gw.deleteSecurityGroup(role, REGION, sg);
        assertThat(gw.describeInstance(role, REGION, "i-0123456789abcdef0")).isEmpty();
    }

    @Test
    void ssmParameter_isRead() {
        try (SsmClient ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b"))).build()) {
            ssm.putParameter(b -> b.name("/ananke/test/ami-id").value("ami-0123456789abcdef0").type(ParameterType.STRING));
        }
        assertThat(gw.ssmParameter(BASE, REGION, "/ananke/test/ami-id")).isEqualTo("ami-0123456789abcdef0");
    }

    @Test
    void awsErrors_carryTheirCode() {
        Creds role = gw.assumeRole(BASE, REGION, "arn:aws:iam::123456789012:role/AnankePacksRole", "x", "s");
        assertThatThrownBy(() -> gw.deleteSecurityGroup(role, REGION, "sg-00000000000000000"))
                .isInstanceOf(AwsGateway.AwsException.class)
                .satisfies(e -> assertThat(((AwsGateway.AwsException) e).code()).isEqualTo("InvalidGroup.NotFound"));
    }

    @Test
    void pricingDocuments_areParsed() {
        // Shape of an AWS Price List API GetProducts priceList entry (trimmed).
        String ec2 = """
                {"product":{"attributes":{"instanceType":"g6.xlarge","regionCode":"us-east-1"}},
                 "terms":{"OnDemand":{"ABC.JRTCKXETXF":{"priceDimensions":{"ABC.JRTCKXETXF.6YS6EN2CT7":
                   {"unit":"Hrs","pricePerUnit":{"USD":"0.8048000000"}}}}}}}""";
        String zero = """
                {"terms":{"OnDemand":{"X":{"priceDimensions":{"Y":{"unit":"Hrs","pricePerUnit":{"USD":"0.0000000000"}}}}}}}""";
        ObjectMapper m = new ObjectMapper();
        assertThat(SdkAwsGateway.firstOnDemandPrice(List.of(zero, ec2), "Hrs", m)).contains(0.8048);
        assertThat(SdkAwsGateway.firstOnDemandPrice(List.of(ec2), "GB-Mo", m)).isEmpty();
        assertThat(SdkAwsGateway.firstOnDemandPrice(List.of("not json"), "Hrs", m)).isEmpty();
    }
}
