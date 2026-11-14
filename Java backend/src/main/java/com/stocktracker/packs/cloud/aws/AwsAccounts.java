package com.stocktracker.packs.cloud.aws;

import com.stocktracker.packs.ComputeTargetRepository;
import com.stocktracker.packs.PackException;
import com.stocktracker.packs.SecretVault;
import com.stocktracker.packs.cloud.CloudAccount;
import com.stocktracker.packs.cloud.CloudAccountRepository;
import com.stocktracker.packs.cloud.aws.AwsGateway.AwsException;
import com.stocktracker.packs.cloud.aws.AwsGateway.Creds;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.stocktracker.packs.PackException.Code.*;

/**
 * AWS account linking (Packs build doc §7.1). Ananke is self-hosted, so there is no Ananke
 * AWS principal for a role to trust: the user deploys {@code ananke-access.yaml}, which
 * creates a scoped role (external-ID condition) and an IAM user that can only assume it.
 * The user's one access key for that user is stored in {@link SecretVault} (scope
 * {@code cloud:<id>}); every API call runs on short-lived role credentials obtained with
 * the external ID. The backend's own ambient AWS credentials are never consulted.
 */
@Slf4j
public class AwsAccounts {

    public static final String CLOUD = "aws";
    static final String KEY_ID = "aws_access_key_id";
    static final String SECRET = "aws_secret_access_key";
    static final String TEMPLATE = "packs/aws/ananke-access.yaml";
    /** DLAMI used for the link check (and as the default image when provisioning). */
    public static final String DLAMI_PARAMETER =
            "/aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-22.04/latest/ami-id";

    static final Pattern REGION   = Pattern.compile("^[a-z]{2}(-gov)?-[a-z]+-\\d$");
    static final Pattern ROLE_ARN = Pattern.compile("^arn:aws(-[a-z]+)?:iam::(\\d{12}):role/[A-Za-z0-9_+=,.@/-]{1,128}$");
    static final Pattern KEY_ID_P = Pattern.compile("^(AKIA|ASIA)[A-Z0-9]{16}$");

    private static final SecureRandom RNG = new SecureRandom();

    private final CloudAccountRepository accounts;
    private final ComputeTargetRepository targets;
    private final SecretVault vault;
    private final AwsGateway aws;
    private final Clock clock;
    private final Map<Long, Creds> sessionCache = new ConcurrentHashMap<>();
    private final String dlamiParameter;

    public AwsAccounts(CloudAccountRepository accounts, ComputeTargetRepository targets, SecretVault vault,
                       AwsGateway aws, Clock clock) {
        this(accounts, targets, vault, aws, clock, DLAMI_PARAMETER);
    }

    /** {@code dlamiParameter} is overridable for emulators, which can't hold names under /aws/. */
    public AwsAccounts(CloudAccountRepository accounts, ComputeTargetRepository targets, SecretVault vault,
                       AwsGateway aws, Clock clock, String dlamiParameter) {
        this.dlamiParameter = dlamiParameter == null || dlamiParameter.isBlank() ? DLAMI_PARAMETER : dlamiParameter;
        this.accounts = accounts;
        this.targets  = targets;
        this.vault    = vault;
        this.aws      = aws;
        this.clock    = clock;
    }

    public record LinkStart(CloudAccount account, String template, String cliCommand, List<String> consoleSteps) { }

    public static String validRegion(String region) {
        if (region == null || !REGION.matcher(region.strip()).matches()) {
            throw new PackException(INVALID_INPUT, "Not an AWS region code (e.g. us-east-1).");
        }
        return region.strip();
    }

    /** Step 1: a pending link with a fresh external ID, plus how to create the role. */
    public LinkStart start(String label, String homeRegion) {
        CloudAccount a = new CloudAccount();
        a.setCloud(CLOUD);
        a.setLabel(label == null || label.isBlank() ? "AWS" : label.strip().substring(0, Math.min(100, label.strip().length())));
        a.setHomeRegion(validRegion(homeRegion));
        a.setExternalId("ananke-" + random(16));
        a.setLinkTag(random(8));
        a.setStatus(CloudAccount.Status.PENDING);
        a = accounts.save(a);
        String stack = "ananke-packs-access";
        String cli = "aws cloudformation deploy --region " + a.getHomeRegion()
                + " --stack-name " + stack
                + " --template-file ananke-access.yaml --capabilities CAPABILITY_NAMED_IAM"
                + " --parameter-overrides ExternalId=" + a.getExternalId()
                + "\naws cloudformation describe-stacks --region " + a.getHomeRegion() + " --stack-name " + stack
                + " --query \"Stacks[0].Outputs\""
                + "\naws iam create-access-key --user-name ananke-packs";
        List<String> steps = List.of(
                "Download ananke-access.yaml (below) and read it: it only allows tagged EC2 GPU VMs, the Deep Learning AMI lookup and price lookups.",
                "CloudFormation console → Create stack → Upload a template file → ananke-access.yaml.",
                "Set ExternalId to " + a.getExternalId() + ", acknowledge IAM resource creation, create the stack.",
                "From the stack's Outputs copy RoleArn; in IAM → Users → ananke-packs → Security credentials, create one access key.",
                "Paste the RoleArn and the access key here. Ananke verifies them with a free dry-run launch; nothing is created.");
        return new LinkStart(a, template(), cli, steps);
    }

    public static String template() {
        try (InputStream in = AwsAccounts.class.getClassLoader().getResourceAsStream(TEMPLATE)) {
            if (in == null) throw new IllegalStateException(TEMPLATE + " missing from the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Step 2: store the key, assume the role, and prove the permissions with a dry-run launch. */
    public CloudAccount verify(long accountId, String roleArn, String accessKeyId, String secretAccessKey) {
        CloudAccount a = get(accountId);
        var m = roleArn == null ? null : ROLE_ARN.matcher(roleArn.strip());
        if (m == null || !m.matches()) throw new PackException(INVALID_INPUT, "Paste the RoleArn output of the stack (arn:aws:iam::<account>:role/…).");
        boolean newKey = accessKeyId != null && !accessKeyId.isBlank();
        if (newKey) {
            if (!KEY_ID_P.matcher(accessKeyId.strip()).matches()) throw new PackException(INVALID_INPUT, "That is not an AWS access key ID.");
            if (secretAccessKey == null || secretAccessKey.strip().length() < 30 || secretAccessKey.strip().length() > 128) {
                throw new PackException(INVALID_INPUT, "Paste the secret access key that belongs to the key ID.");
            }
            vault.put(a.scope(), KEY_ID, accessKeyId.strip());
            vault.put(a.scope(), SECRET, secretAccessKey.strip());
        } else if (!vault.has(a.scope(), KEY_ID)) {
            throw new PackException(INVALID_INPUT, "Paste the access key for the ananke-packs user.");
        }
        a.setRoleArn(roleArn.strip());
        sessionCache.remove(a.getId());
        try {
            Creds role = assume(a);
            var id = aws.callerIdentity(role, a.getHomeRegion());
            if (!m.group(2).equals(id.accountId())) {
                throw new PackException(CLOUD_AUTH, "The role belongs to account " + m.group(2) + " but the session is in " + id.accountId() + ".");
            }
            String ami = aws.ssmParameter(role, a.getHomeRegion(), dlamiParameter);
            boolean allowed = aws.dryRunLaunch(role, a.getHomeRegion(), new AwsGateway.RunSpec(ami, "g6.xlarge", null, 150, null,
                    Map.of("ananke-managed", "true", "ananke-link", a.getLinkTag())));
            if (!allowed) {
                throw new PackException(CLOUD_PERMISSION, "The role cannot launch tagged GPU instances. Re-create the stack from ananke-access.yaml unmodified.");
            }
            a.setAccountRef(id.accountId());
            a.setStatus(CloudAccount.Status.LINKED);
            a.setLastError(null);
            a.setLastVerifiedAt(clock.instant());
            return accounts.save(a);
        } catch (PackException e) {
            fail(a, e.getMessage());
            throw e;
        } catch (AwsException e) {
            String msg = explain(e);
            fail(a, msg);
            throw new PackException("AccessDenied".equals(e.code()) || "InvalidClientTokenId".equals(e.code())
                    || "SignatureDoesNotMatch".equals(e.code()) ? CLOUD_AUTH : CLOUD_FAILED, msg);
        }
    }

    private void fail(CloudAccount a, String msg) {
        a.setStatus(CloudAccount.Status.FAILED);
        a.setLastError(msg);
        accounts.save(a);
    }

    static String explain(AwsException e) {
        String code = e.code() == null ? "" : e.code();
        return switch (code) {
            case "InvalidClientTokenId", "SignatureDoesNotMatch" -> "AWS rejected the access key. Check the key ID and secret for the ananke-packs user.";
            case "AccessDenied" -> "AWS denied the request. Check the RoleArn, and that ExternalId in the stack matches this link.";
            default -> e.getMessage();
        };
    }

    public void unlink(long accountId) {
        CloudAccount a = get(accountId);
        if (!targets.findByCloudAccountId(a.getId()).isEmpty()) {
            throw new IllegalStateException("Terminate this account's machines before unlinking it.");
        }
        sessionCache.remove(a.getId());
        vault.deleteScope(a.scope());
        accounts.delete(a);
    }

    public CloudAccount get(long id) {
        return accounts.findById(id).filter(x -> CLOUD.equals(x.getCloud()))
                .orElseThrow(() -> new NoSuchElementException("Cloud account " + id + " not found"));
    }

    public CloudAccount linked(long id) {
        CloudAccount a = get(id);
        if (a.getStatus() != CloudAccount.Status.LINKED) throw new PackException(CLOUD_NOT_LINKED, "Finish linking this AWS account first.");
        return a;
    }

    public boolean anyLinked() {
        return !accounts.findByCloudAndStatus(CLOUD, CloudAccount.Status.LINKED).isEmpty();
    }

    public List<CloudAccount> list() {
        return accounts.findAllByOrderByCreatedAtAsc().stream().filter(x -> CLOUD.equals(x.getCloud())).toList();
    }

    public String dlamiParameter() {
        return dlamiParameter;
    }

    public String keyHint(CloudAccount a) {
        return vault.get(a.scope(), KEY_ID).map(k -> "…" + k.substring(k.length() - 4)).orElse(null);
    }

    /** Short-lived role credentials for this account, refreshed five minutes before expiry. */
    public Creds session(CloudAccount a) {
        Creds c = sessionCache.get(a.getId());
        Instant now = clock.instant();
        if (c != null && c.expiresAt() != null && c.expiresAt().isAfter(now.plus(Duration.ofMinutes(5)))) return c;
        c = assume(a);
        sessionCache.put(a.getId(), c);
        return c;
    }

    private Creds assume(CloudAccount a) {
        String keyId = vault.get(a.scope(), KEY_ID).orElseThrow(() -> new PackException(CLOUD_NOT_LINKED, "No access key stored."));
        String secret = vault.get(a.scope(), SECRET).orElseThrow(() -> new PackException(CLOUD_NOT_LINKED, "No access key stored."));
        return aws.assumeRole(new Creds(keyId, secret, null, null), a.getHomeRegion(), a.getRoleArn(),
                a.getExternalId(), "ananke-" + a.getId());
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
