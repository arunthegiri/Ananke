package com.stocktracker.packs.cloud;

import com.stocktracker.agent.actions.AgentAuditRepository;
import com.stocktracker.packs.ComputeTargetRepository;
import com.stocktracker.packs.PackRepository;
import com.stocktracker.packs.PackService;
import com.stocktracker.packs.SecretVault;
import com.stocktracker.packs.cloud.aws.AwsAccounts;
import com.stocktracker.packs.cloud.aws.AwsGateway;
import com.stocktracker.packs.cloud.aws.AwsMachines;
import com.stocktracker.packs.cloud.aws.SdkAwsGateway;
import com.stocktracker.packs.cloud.aws.SshKeygen;
import com.stocktracker.packs.runtime.CommandRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Wires §7 cloud compute. {@code ananke.cloud.aws.endpoint} points every AWS call at an emulator (tests only). */
@Slf4j
@Configuration
public class CloudConfig {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Bean
    public AwsGateway awsGateway(@Value("${ananke.cloud.aws.endpoint:}") String endpoint) {
        if (!endpoint.isBlank()) log.warn("AWS calls go to {} (emulator override), not AWS", endpoint);
        return new SdkAwsGateway(endpoint.isBlank() ? null : URI.create(endpoint));
    }

    @Bean
    public AwsAccounts awsAccounts(CloudAccountRepository accounts, ComputeTargetRepository targets, SecretVault vault,
                                   AwsGateway aws) {
        return new AwsAccounts(accounts, targets, vault, aws, Clock.systemUTC());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService cloudExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cloud-" + SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public AwsMachines awsMachines(AwsAccounts accounts, AwsGateway aws, ComputeTargetRepository targets, PackRepository packs,
                                   ObjectProvider<PackService> packService, SecretVault vault, CommandRunner runner,
                                   @Value("${ananke.packs.ssh-keygen-binary:ssh-keygen}") String keygenBin,
                                   AgentAuditRepository audit, ExecutorService cloudExecutor) {
        return new AwsMachines(accounts, aws, targets, packs, packService, vault, new SshKeygen(runner, keygenBin), audit,
                cloudExecutor, d -> Thread.sleep(d.toMillis()), Clock.systemUTC(), AwsMachines.Timing.defaults());
    }

    /** §7.4 idle auto-stop. Every 5 minutes by default. */
    @Bean
    public IdleSweeper idleSweeper(AwsMachines machines) {
        return new IdleSweeper(machines);
    }

    public static class IdleSweeper {
        private final AwsMachines machines;

        IdleSweeper(AwsMachines machines) {
            this.machines = machines;
        }

        @Scheduled(initialDelayString = "${ananke.cloud.idle-sweep-initial-ms:120000}",
                   fixedDelayString = "${ananke.cloud.idle-sweep-ms:300000}")
        public void sweep() {
            var stopped = machines.idleSweep();
            if (!stopped.isEmpty()) log.info("Idle auto-stop: stopped cloud machine(s) {}", stopped);
        }
    }
}
