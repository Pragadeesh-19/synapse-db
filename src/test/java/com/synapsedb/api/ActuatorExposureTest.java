package com.synapsedb.api;

import com.synapsedb.core.MemoryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies actuator endpoint exposure:
 * - /actuator/health is accessible and returns 200 (T-HEALTHCHECK-ACTUATOR closure)
 * - /actuator/prometheus is accessible and returns synapse_* metrics
 * - /actuator/** is NOT accessible on the main API port (phase 6 port-separation invariant)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "management.server.port=0",  // random management port in tests
                // Spring Boot 3.x disables metrics export in the main context when management
                // port differs (sets management.defaults.metrics.export.enabled=false and
                // explicitly enables Simple only). Re-enable Prometheus for the test context
                // so the /actuator/prometheus endpoint is properly served.
                "management.prometheus.metrics.export.enabled=true"
        })
class ActuatorExposureTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        MemoryConfig memoryConfig() throws IOException {
            Path tmp = Files.createTempDirectory("synapse-act");
            return new MemoryConfig(64, 1024, 0.1f, 3_600_000L, 0.1f, 0.5f,
                    tmp.toString(), tmp.toString(), 0.7f);
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MeterRegistry meterRegistry;

    /** Injects the management port Spring chose (separate from the main server port). */
    @LocalManagementPort
    int managementPort;

    @Test
    @DisplayName("/actuator/health on management port returns 200 UP")
    void healthReturns200() {
        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + managementPort + "/actuator/health", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "/actuator/health must return 200; body: " + resp.getBody());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().contains("UP") || resp.getBody().contains("\"status\""),
                "health response must contain status: " + resp.getBody());
    }

    @Test
    @DisplayName("PrometheusMeterRegistry is auto-configured and /actuator/prometheus serves metrics")
    void prometheusEndpointExposesMetrics() {
        // Phase 6 D4: verify that Spring Boot auto-configured a PrometheusMeterRegistry.
        // The test properties include management.prometheus.metrics.export.enabled=true to
        // override Spring Boot 3.x's default behaviour of disabling metrics export in the
        // main context when management.server.port differs from server.port.
        boolean hasPrometheus = prometheusRegistryPresent(meterRegistry);
        assertTrue(hasPrometheus,
                "PrometheusMeterRegistry must be auto-configured (micrometer-registry-prometheus on classpath); "
                        + "got registry type: " + meterRegistry.getClass().getName());

        // Register an agent so timers have at least one measurement before scraping.
        ResponseEntity<java.util.Map> regResp = rest.postForEntity(
                "/api/v1/agents",
                java.util.Map.of("label", "metrics-probe"),
                java.util.Map.class);
        if (regResp.getStatusCode() == HttpStatus.CREATED) {
            int agentId = ((Number) regResp.getBody().get("agentId")).intValue();
            String apiKey = (String) regResp.getBody().get("apiKey");
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.set("X-Api-Key", apiKey);
            h.set("Content-Type", "application/json");
            rest.exchange("/api/v1/agents/" + agentId + "/thoughts",
                    org.springframework.http.HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(
                            "{\"parentId\":0,\"stateHash\":1,\"successScore\":0.5,\"sessionId\":1}", h),
                    String.class);
        }

        // HTTP scrape check on the management port.
        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "/actuator/prometheus must return 200 on management port " + managementPort
                        + "; body: " + resp.getBody());
        String body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.contains("synapse_") || body.contains("synapse."),
                "Prometheus output must contain synapse_ metrics; got: "
                        + body.substring(0, Math.min(500, body.length())));
    }

    private static boolean prometheusRegistryPresent(MeterRegistry registry) {
        if (registry.getClass().getName().contains("Prometheus")) return true;
        if (registry instanceof CompositeMeterRegistry composite) {
            return composite.getRegistries().stream()
                    .anyMatch(r -> r.getClass().getName().contains("Prometheus"));
        }
        return false;
    }

    @Test
    @DisplayName("/actuator/health is NOT accessible on the main API port (filter separation)")
    void actuatorNotOnMainPort() {
        // On the main port, /actuator/** is either 404 (no route) or filtered.
        // It must never return 200 as a health endpoint (that would mean management is on the app port).
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health", String.class);
        // ApiKeyFilter intercepts /actuator/** on the main port (returns 401, not the actuator response).
        // Either 401 or 404 confirms management traffic is not served on the API port.
        assertNotEquals(HttpStatus.OK, resp.getStatusCode(),
                "/actuator/health must not be reachable on the main API port; "
                        + "management.server.port must be separate; got " + resp.getStatusCode());
    }
}
