package com.stocktracker.packs.cloud;

import com.stocktracker.packs.ComputeTarget;
import com.stocktracker.packs.PackController;
import com.stocktracker.packs.PackException;
import com.stocktracker.packs.cloud.aws.AwsAccounts;
import com.stocktracker.packs.cloud.aws.AwsMachines;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Packs build doc §7 — cloud accounts and cloud GPU machines. AWS today.
 * Secrets are write-only: responses carry only a last-four hint of the access key.
 */
@RestController
@RequestMapping("/api/cloud")
public class CloudController {

    private final AwsAccounts accounts;
    private final AwsMachines machines;
    private final PackController packController;
    private final EgressIp egress;

    public CloudController(AwsAccounts accounts, AwsMachines machines, PackController packController, EgressIp egress) {
        this.accounts       = accounts;
        this.machines       = machines;
        this.packController = packController;
        this.egress         = egress;
    }

    public record AccountView(long id, String cloud, String label, String status, String homeRegion, String externalId,
                              String roleArn, String accountRef, String keyHint, String lastVerifiedAt, String lastError,
                              int machines) { }

    public record LinkRequest(String label, String region) { }

    public record VerifyRequest(String roleArn, String accessKeyId, String secretAccessKey) { }

    public record ProvisionConfirm(String estimateId, Boolean acknowledgeBilling) { }

    public record OrphanTerminate(String region, String instanceId) { }

    // ── accounts ─────────────────────────────────────────────────────────────

    @GetMapping("/accounts")
    public List<AccountView> list() {
        return accounts.list().stream().map(this::view).toList();
    }

    @PostMapping("/aws/link")
    public Map<String, Object> link(@RequestBody LinkRequest req) {
        AwsAccounts.LinkStart s = accounts.start(req == null ? null : req.label(), req == null ? null : req.region());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", view(s.account()));
        out.put("templateUrl", "/api/cloud/aws/template");
        out.put("cliCommand", s.cliCommand());
        out.put("consoleSteps", s.consoleSteps());
        return out;
    }

    @GetMapping(value = "/aws/template", produces = "application/x-yaml")
    public ResponseEntity<String> template() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ananke-access.yaml\"")
                .contentType(MediaType.parseMediaType("application/x-yaml"))
                .body(AwsAccounts.template());
    }

    @PostMapping("/accounts/{id}/verify")
    public AccountView verify(@PathVariable long id, @RequestBody VerifyRequest req) {
        if (req == null) throw new IllegalArgumentException("Body required");
        return view(accounts.verify(id, req.roleArn(), req.accessKeyId(), req.secretAccessKey()));
    }

    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<Void> unlink(@PathVariable long id) {
        accounts.unlink(id);
        return ResponseEntity.noContent().build();
    }

    /** The backend's public IPv4 — the address SSH comes from, so the VM's security group admits only it. */
    @GetMapping("/egress-ip")
    public Map<String, Object> egressIp() {
        return egress.detect().<Map<String, Object>>map(ip -> Map.of("ip", ip, "cidr", ip + "/32"))
                .orElse(Map.of("error", "Could not detect the backend's public IP via " + egress.url() + "."));
    }

    // ── machines ─────────────────────────────────────────────────────────────

    @GetMapping("/aws/instance-types")
    public List<AwsMachines.InstanceTypeInfo> instanceTypes() {
        return AwsMachines.CATALOG;
    }

    @PostMapping("/accounts/{id}/estimate")
    public AwsMachines.Estimate estimate(@PathVariable long id, @RequestBody AwsMachines.ProvisionRequest req) {
        return machines.estimate(id, req);
    }

    @PostMapping("/accounts/{id}/provision")
    public PackController.TargetView provision(@PathVariable long id, @RequestBody ProvisionConfirm req) {
        if (req == null) throw new IllegalArgumentException("Body required");
        return packController.targetView(machines.provision(id, req.estimateId(),
                Boolean.TRUE.equals(req.acknowledgeBilling()), "user"));
    }

    @GetMapping("/accounts/{id}/machines")
    public List<PackController.TargetView> machines(@PathVariable long id) {
        return machines.machines(id).stream().map(packController::targetView).toList();
    }

    @PostMapping("/aws/machines/{targetId}/stop")
    public PackController.TargetView stop(@PathVariable long targetId) {
        return packController.targetView(machines.stop(targetId, "user"));
    }

    @PostMapping("/aws/machines/{targetId}/start")
    public PackController.TargetView start(@PathVariable long targetId) {
        return packController.targetView(machines.start(targetId, "user"));
    }

    @PostMapping("/aws/machines/{targetId}/terminate")
    public PackController.TargetView terminate(@PathVariable long targetId) {
        ComputeTarget t = machines.terminate(targetId, "user");
        return packController.targetView(t);
    }

    @GetMapping("/accounts/{id}/orphans")
    public List<AwsMachines.Orphan> orphans(@PathVariable long id) {
        return machines.orphans(id);
    }

    @PostMapping("/accounts/{id}/orphans/terminate")
    public ResponseEntity<Void> terminateOrphan(@PathVariable long id, @RequestBody OrphanTerminate req) {
        if (req == null) throw new IllegalArgumentException("Body required");
        machines.terminateOrphan(id, req.region(), req.instanceId(), "user");
        return ResponseEntity.noContent().build();
    }

    // ── mapping / errors ─────────────────────────────────────────────────────

    private AccountView view(CloudAccount a) {
        return new AccountView(a.getId(), a.getCloud(), a.getLabel(), a.getStatus().name(), a.getHomeRegion(),
                a.getExternalId(), a.getRoleArn(), a.getAccountRef(), accounts.keyHint(a),
                a.getLastVerifiedAt() == null ? null : a.getLastVerifiedAt().toString(), a.getLastError(),
                machines.machines(a.getId()).size());
    }

    /** An AWS API error the services didn't translate: AWS's reason, as a gateway error (never a bare 500). */
    @ExceptionHandler(com.stocktracker.packs.cloud.aws.AwsGateway.AwsException.class)
    public ResponseEntity<Map<String, Object>> awsError(com.stocktracker.packs.cloud.aws.AwsGateway.AwsException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        body.put("code", "CLOUD_FAILED");
        body.put("awsCode", e.code());
        body.put("status", 502);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(PackException.class)
    public ResponseEntity<Map<String, Object>> packError(PackException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        body.put("code", e.code().name());
        body.put("status", 422);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }
}
