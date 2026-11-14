package com.stocktracker.connectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.packs.SecretVault;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/** Wires the connector framework (Connectors roadmap §1). Every {@link Connector} bean is registered. */
@Configuration
public class ConnectorConfig {

    @Bean
    public ConnectorStore connectorStore(JdbcTemplate jdbc, SecretVault vault) {
        return new JdbcConnectorStore(jdbc, vault);
    }

    @Bean
    public ConnectorService connectorService(ObjectProvider<Connector> connectors, ConnectorStore store, ObjectMapper mapper) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new ConnectorService(connectors.orderedStream().toList(), store, RateLimiter.system(),
                new ResponseCache(Clock.systemUTC(), 2_000), http, mapper, Clock.systemUTC());
    }

    @Bean
    public ConnectorToolGate connectorToolGate(ConnectorService service, ObjectMapper mapper) {
        return new ConnectorToolGate(service, mapper);
    }
}
