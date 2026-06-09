package com.synapsedb.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-stack API tests: real HTTP through the {@link com.synapsedb.api.auth.ApiKeyFilter},
 * the typed error model, a register→append→best-next→path-to-root→bootstrap→stats roundtrip,
 * and a 2-thread same-agent concurrency proof of the per-agent lock (D2). The real engine
 * writes real ring files into a temp dir.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
class ApiIntegrationTest {

    /** Override the engine config with a small shard + temp dirs (no api-keys.yml → no seeds). */
    @TestConfiguration
    static class TestEngineConfig {
        @Bean
        @Primary
        MemoryConfig memoryConfig() throws IOException {
            Path tmp = Files.createTempDirectory("synapse-it");
            return new MemoryConfig(64, 1024, 0.1f, 3_600_000L, 0.1f, 0.5f,
                    tmp.toString(), tmp.toString(), 0.7f);
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("register returns a raw key once; append persists with positive salience")
    void registerAndAppend() throws IOException {
        Agent a = register("alpha");
        assertTrue(a.apiKey.startsWith("sk_syn_"), "raw key format");

        JsonNode r = mapper.readTree(append(a, 0, 99, 0.8, 7).getBody());
        assertEquals(r.get("thoughtId").asInt(), r.get("slotIndex").asInt(), "thoughtId == slotIndex");
        assertTrue(r.get("salienceScore").asDouble() > 0.0, "salience seeded from root");
        assertTrue(r.get("persisted").asBoolean());
    }

    @Test
    @DisplayName("best-next picks the higher-scoring child")
    void bestNext() throws IOException {
        Agent a = register("beta");
        int low = slot(append(a, 0, 1, 0.1, 7));
        int high = slot(append(a, 0, 2, 0.9, 7));

        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/agents/" + a.id + "/thoughts/best-next?fromSlot=0&sessionId=7",
                HttpMethod.GET, new HttpEntity<>(auth(a.apiKey)), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode n = mapper.readTree(resp.getBody());
        assertTrue(n.get("found").asBoolean());
        assertEquals(high, n.get("slot").asInt(), "highest Hebbian score wins");
        assertNotEquals(low, n.get("slot").asInt());
    }

    @Test
    @DisplayName("best-next on a childless root returns found=false")
    void bestNextChildless() throws IOException {
        Agent a = register("gamma");
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/agents/" + a.id + "/thoughts/best-next?fromSlot=0&sessionId=7",
                HttpMethod.GET, new HttpEntity<>(auth(a.apiKey)), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertFalse(mapper.readTree(resp.getBody()).get("found").asBoolean());
    }

    @Test
    @DisplayName("path-to-root returns the chain newest→oldest")
    void pathToRoot() throws IOException {
        Agent a = register("delta");
        int s1 = slot(append(a, 0, 1, 0.5, 1));
        int s2 = slot(append(a, s1, 2, 0.5, 1));
        int s3 = slot(append(a, s2, 3, 0.5, 1));

        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/agents/" + a.id + "/thoughts/path-to-root?fromSlot=" + s3,
                HttpMethod.GET, new HttpEntity<>(auth(a.apiKey)), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode n = mapper.readTree(resp.getBody());
        assertEquals(3, n.get("depth").asInt());
        assertEquals(List.of(s3, s2, s1),
                mapper.convertValue(n.get("path"), new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() {}));
    }

    @Test
    @DisplayName("bootstrap reloads and stats reports occupancy")
    void bootstrapAndStats() throws IOException {
        Agent a = register("epsilon");
        append(a, 0, 1, 0.5, 1);
        append(a, 0, 2, 0.5, 1);

        ResponseEntity<String> boot = rest.exchange(
                "/api/v1/agents/" + a.id + "/bootstrap", HttpMethod.POST,
                new HttpEntity<>(auth(a.apiKey)), String.class);
        assertEquals(HttpStatus.OK, boot.getStatusCode());
        assertTrue(mapper.readTree(boot.getBody()).get("reloaded").asBoolean());

        ResponseEntity<String> stats = rest.exchange(
                "/api/v1/agents/" + a.id + "/memory/stats", HttpMethod.GET,
                new HttpEntity<>(auth(a.apiKey)), String.class);
        assertEquals(HttpStatus.OK, stats.getStatusCode());
        JsonNode n = mapper.readTree(stats.getBody());
        assertEquals(2, n.get("usedSlots").asInt());
        assertEquals(1023, n.get("capacity").asInt());
        assertFalse(n.get("wrapped").asBoolean());
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("missing API key → 401")
    void missingKey() {
        Agent a = register("zeta");
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/agents/" + a.id + "/memory/stats", HttpMethod.GET,
                new HttpEntity<>(auth(null)), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("unknown API key → 401")
    void unknownKey() {
        Agent a = register("eta");
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/agents/" + a.id + "/memory/stats", HttpMethod.GET,
                new HttpEntity<>(auth("sk_syn_not_a_real_key")), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("valid key for the wrong agent → 403")
    void wrongAgent() {
        Agent a = register("theta");
        Agent b = register("iota");
        // a's key against b's path
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/agents/" + b.id + "/memory/stats", HttpMethod.GET,
                new HttpEntity<>(auth(a.apiKey)), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    // ── Error model (D4) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("parent out of range → 409")
    void badParentConflict() {
        Agent a = register("kappa");
        ResponseEntity<String> resp = append(a, 99999, 1, 0.5, 1);
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }

    @Test
    @DisplayName("negative parent → 400 (bean validation)")
    void negativeParentBadRequest() {
        Agent a = register("lambda");
        ResponseEntity<String> resp = append(a, -5, 1, 0.5, 1);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @DisplayName("successScore out of [-1,1] → 400")
    void successScoreOutOfRange() {
        Agent a = register("mu");
        ResponseEntity<String> resp = append(a, 0, 1, 2.5, 1);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @DisplayName("malformed JSON body → 400 (not 500)")
    void malformedBody() {
        Agent a = register("omicron");
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/agents/" + a.id + "/thoughts", HttpMethod.POST,
                new HttpEntity<>("{ this is not json", auth(a.apiKey)), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @DisplayName("reading a never-written thought → 404")
    void unknownThought() {
        Agent a = register("nu");
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/agents/" + a.id + "/thoughts/path-to-root?fromSlot=42",
                HttpMethod.GET, new HttpEntity<>(auth(a.apiKey)), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── Concurrency (proves the D2 per-agent lock) ───────────────────────────────

    @Test
    @DisplayName("two threads appending to the same agent never corrupt: 200 distinct slots")
    void concurrentAppendsSameAgent() throws Exception {
        Agent a = register("xi");
        int perThread = 100;
        ConcurrentLinkedQueue<Integer> slots = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable job = () -> {
            try {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    ResponseEntity<String> r = append(a, 0, i, 0.5, 1);
                    if (r.getStatusCode() == HttpStatus.CREATED) {
                        slots.add(mapper.readTree(r.getBody()).get("slotIndex").asInt());
                    } else {
                        errors.add(new IllegalStateException("HTTP " + r.getStatusCode()));
                    }
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        };
        pool.submit(job);
        pool.submit(job);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "appends must finish");

        assertTrue(errors.isEmpty(), "no append may fail: " + errors);
        assertEquals(2 * perThread, slots.size(), "every append returns a slot");
        Set<Integer> distinct = new HashSet<>(slots);
        assertEquals(2 * perThread, distinct.size(),
                "every slot must be unique — a duplicate proves the lock failed and the core was corrupted");

        // Engine state must agree: writeHead advanced by exactly 200.
        ResponseEntity<String> stats = rest.exchange(
                "/api/v1/agents/" + a.id + "/memory/stats", HttpMethod.GET,
                new HttpEntity<>(auth(a.apiKey)), String.class);
        assertEquals(2 * perThread, mapper.readTree(stats.getBody()).get("usedSlots").asInt());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private record Agent(int id, String apiKey) {}

    private Agent register(String label) {
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/agents", HttpMethod.POST,
                new HttpEntity<>("{\"label\":\"" + label + "\"}", auth(null)), String.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(), "registration must succeed");
        try {
            JsonNode n = mapper.readTree(resp.getBody());
            return new Agent(n.get("agentId").asInt(), n.get("apiKey").asText());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private ResponseEntity<String> append(Agent a, int parent, int hash, double success, int session) {
        String body = "{\"parentId\":" + parent + ",\"stateHash\":" + hash
                + ",\"successScore\":" + success + ",\"sessionId\":" + session + "}";
        return rest.exchange("/api/v1/agents/" + a.id + "/thoughts", HttpMethod.POST,
                new HttpEntity<>(body, auth(a.apiKey)), String.class);
    }

    private int slot(ResponseEntity<String> appendResponse) {
        assertEquals(HttpStatus.CREATED, appendResponse.getStatusCode());
        try {
            return mapper.readTree(appendResponse.getBody()).get("slotIndex").asInt();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static HttpHeaders auth(String apiKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            h.set("X-Api-Key", apiKey);
        }
        return h;
    }
}
