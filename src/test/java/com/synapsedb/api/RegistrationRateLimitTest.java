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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the per-IP registration bucket enforces its cap and returns HTTP 429 +
 * Retry-After when exhausted.
 *
 * <p>Registration capacity is forced to 1 via a {@code @Primary RateLimitProperties} bean
 * so the second {@code POST /api/v1/agents} from the same IP (127.0.0.1 in tests) is
 * rejected. Agent capacity is set high (1000) so authenticated routes are unaffected.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "management.server.port=0"
        })
class RegistrationRateLimitTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        MemoryConfig memoryConfig() throws IOException {
            Path tmp = Files.createTempDirectory("synapse-reg-rl");
            return new MemoryConfig(64, 1024, 0.1f, 3_600_000L, 0.1f, 0.5f,
                    tmp.toString(), tmp.toString(), 0.7f);
        }

        /**
         * Agent capacity = 1000 (never hit); registration capacity = 1 so the second
         * POST /api/v1/agents from localhost is rejected.
         */
        @Bean
        @Primary
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(1000, 60, 1, 60);
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("per-IP registration bucket: 429 + Retry-After after capacity exhausted")
    void registrationRateLimitEnforcedPerIp() {
        ResponseEntity<Map> first = rest.postForEntity(
                "/api/v1/agents", Map.of("label", "first"), Map.class);
        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, first.getStatusCode(),
                "first registration must not be rate-limited; got " + first.getStatusCode());

        ResponseEntity<Map> second = rest.postForEntity(
                "/api/v1/agents", Map.of("label", "second"), Map.class);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getStatusCode(),
                "second registration from same IP must be rate-limited (429)");

        String retryAfter = second.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "429 response must include a Retry-After header");
        assertTrue(Long.parseLong(retryAfter) > 0, "Retry-After must be a positive number of seconds");
    }
}
