package com.synapsedb.api;

import com.synapsedb.api.ratelimit.RateLimitProperties;
import com.synapsedb.core.MemoryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that RateLimitFilter enforces per-agent token buckets and returns HTTP 429 +
 * Retry-After when a bucket is exhausted.
 *
 * <p>The per-agent capacity is forced to 3 via a {@code @Primary RateLimitProperties} bean
 * so the tests can exhaust a bucket in 4 requests instead of 60. The registration capacity
 * is kept high (1000) because the per-IP registration bucket is SHARED across every test
 * method in this context (all requests originate from localhost) — a low registration cap
 * would make tests interfere by execution order.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "management.server.port=0"  // random port for management in tests
        })
class RateLimitTest {

    private static final int AGENT_CAPACITY = 3;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        MemoryConfig memoryConfig() throws IOException {
            Path tmp = Files.createTempDirectory("synapse-rl");
            return new MemoryConfig(64, 1024, 0.1f, 3_600_000L, 0.1f, 0.5f,
                    tmp.toString(), tmp.toString(), 0.7f);
        }

        /**
         * Agent capacity = 3 (so the 4th request to one agent is rejected); registration
         * capacity = 1000 (the per-IP bucket is shared across all test methods).
         */
        @Bean
        @Primary
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(AGENT_CAPACITY, 60, 1000, 60);
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("authenticated route: 429 + Retry-After exactly after per-agent capacity exceeded")
    void agentRouteRateLimited() {
        Agent a = register("rl-test");

        // The first AGENT_CAPACITY appends must succeed; the very next one must be 429.
        for (int i = 0; i < AGENT_CAPACITY; i++) {
            ResponseEntity<String> ok = append(a, i);
            assertEquals(HttpStatus.CREATED, ok.getStatusCode(),
                    "request " + i + " within capacity must be accepted; body: " + ok.getBody());
        }

        ResponseEntity<String> rejected = append(a, 99);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode(),
                "request " + (AGENT_CAPACITY + 1) + " must be rate-limited (429)");
        String retryAfter = rejected.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "429 response must include a Retry-After header");
        assertTrue(Long.parseLong(retryAfter) > 0, "Retry-After must be a positive number of seconds");
    }

    @Test
    @DisplayName("buckets are per-agent, not global: exhausting agent A does not throttle agent B")
    void differentAgentsIndependentBuckets() {
        Agent a = register("agent-a");
        Agent b = register("agent-b");
        assertNotEquals(a.id, b.id, "agents must have different ids");

        // Exhaust agent A's bucket entirely (capacity + 1 → the last one is 429).
        for (int i = 0; i < AGENT_CAPACITY; i++) {
            assertEquals(HttpStatus.CREATED, append(a, i).getStatusCode(),
                    "agent A request " + i + " must be accepted");
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, append(a, 99).getStatusCode(),
                "agent A must be throttled after exhausting its bucket");

        // Agent B's bucket is independent: its first request must still succeed.
        // If buckets were global, agent A's exhaustion would have throttled B too.
        assertEquals(HttpStatus.CREATED, append(b, 0).getStatusCode(),
                "agent B must NOT be throttled by agent A's exhausted bucket (proves per-agent isolation)");
    }

    @Test
    @DisplayName("registration endpoint is reachable and not rate-limited on first request")
    void registrationEndpointReachable() {
        ResponseEntity<Map> resp = rest.postForEntity(
                "/api/v1/agents", Map.of("label", "probe"), Map.class);
        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode(),
                "first registration must not be rate-limited");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record Agent(int id, String apiKey) {}

    private Agent register(String label) {
        ResponseEntity<Map> resp = rest.postForEntity(
                "/api/v1/agents", Map.of("label", label), Map.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(), "registration must succeed");
        return new Agent(((Number) resp.getBody().get("agentId")).intValue(),
                (String) resp.getBody().get("apiKey"));
    }

    private ResponseEntity<String> append(Agent a, int stateHash) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Api-Key", a.apiKey);
        h.set("Content-Type", "application/json");
        String body = "{\"parentId\":0,\"stateHash\":" + stateHash + ",\"successScore\":0.5,\"sessionId\":1}";
        return rest.exchange("/api/v1/agents/" + a.id + "/thoughts",
                HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }
}
