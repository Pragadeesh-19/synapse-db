# Synapse-DB

An in-memory graph database for autonomous AI agent reasoning trees.

Stores directed acyclic graphs of agent "thoughts" as flat parallel primitive arrays
(Struct-of-Arrays layout) for CPU cache coherence. Persistence is via pre-allocated
memory-mapped binary ring files — no Elasticsearch, no external process.

**Performance contract:** append a thought in O(1), find best next thought in O(degree),
backtrack to root in O(depth). Zero GC pauses on the hot path.

## Benchmarks (JMH, single-threaded, Java 21, Apple M-series)

| Operation | Result | Target |
|-----------|--------|--------|
| Append (in-memory) | **48 ns** | < 1 µs |
| Path-to-root, depth 50 | **0.156 µs** | < 10 µs |
| Best-next, degree 5 | **0.211 µs** | < 5 µs |
| Bootstrap, 1 M records | **43.5 ms** | < 200 ms |

99 tests green.

## Quickstart

### Prerequisites

- Java 21+
- Maven 3.8+

### Start the server

```bash
# Copy the sample key file and add at least one pre-seeded key (optional)
cp config/api-keys.yml.sample config/api-keys.yml

mvn spring-boot:run
# Server starts on http://localhost:8080
```

### Register an agent

```bash
curl -s -X POST http://localhost:8080/api/v1/agents \
  -H "Content-Type: application/json" \
  -d '{"label": "my-agent"}' | tee /tmp/agent.json
# {"agentId":1,"apiKey":"sk_syn_<uuid>"}
# Save the apiKey — it is shown exactly once.

AGENT_ID=1
API_KEY="sk_syn_<uuid>"
```

### Append a thought

```bash
curl -s -X POST "http://localhost:8080/api/v1/agents/${AGENT_ID}/thoughts" \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: ${API_KEY}" \
  -d '{"parentId":0,"stateHash":99482,"successScore":0.8,"sessionId":7}'
# {"thoughtId":1,"slotIndex":1,"salienceScore":0.38,"persisted":true}
```

### Query best next thought

```bash
curl -s "http://localhost:8080/api/v1/agents/${AGENT_ID}/thoughts/best-next?fromSlot=0&sessionId=7" \
  -H "X-Api-Key: ${API_KEY}"
# {"found":true,"slot":1,"score":0.76}
```

### Backtrack to root

```bash
curl -s "http://localhost:8080/api/v1/agents/${AGENT_ID}/thoughts/path-to-root?fromSlot=1" \
  -H "X-Api-Key: ${API_KEY}"
# {"path":[1],"depth":1}
```

### Bootstrap from ring file after restart

```bash
curl -s -X POST "http://localhost:8080/api/v1/agents/${AGENT_ID}/bootstrap" \
  -H "X-Api-Key: ${API_KEY}"
# {"agentId":1,"slotsLoaded":1,"writeHead":2}
```

## API Reference

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/agents` | None | Register agent, create ring file, return agentId + apiKey |
| POST | `/api/v1/agents/{id}/thoughts` | X-Api-Key | Append thought (O(1) hot path) |
| GET | `/api/v1/agents/{id}/thoughts/best-next` | X-Api-Key | FCNS walk + Hebbian score |
| GET | `/api/v1/agents/{id}/thoughts/path-to-root` | X-Api-Key | Backtrack to root |
| POST | `/api/v1/agents/{id}/bootstrap` | X-Api-Key | Reload shard from ring file |
| GET | `/api/v1/agents/{id}/memory/stats` | X-Api-Key | Fill %, write head, session info |

Full interactive docs: `http://localhost:8080/swagger-ui.html`

## Authentication

API keys have the format `sk_syn_{UUID without dashes}`. They are returned once at
registration and never stored in plaintext — only the SHA-256 hash is kept. Pass the
raw key in the `X-Api-Key` header on every request. A key is scoped to exactly one
agent; using it on a different agent's path returns 403.

Pre-seed keys for known agents in `config/api-keys.yml` (see
`config/api-keys.yml.sample`). Runtime-registered keys survive server restarts only
if pre-seeded; in-memory keys are lost on restart (tracked as `T-KEY-PERSIST`).

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `SYNAPSE_MAX_AGENTS` | 64 | Maximum number of registered agents |
| `SYNAPSE_SHARD_SIZE` | 1048576 | Slots per agent (must be power of 2) |
| `SYNAPSE_LAMBDA` | 0.1 | Hebbian temporal decay constant |
| `SYNAPSE_DECAY_UNIT_MS` | 3600000 | Decay time unit (1 hour) |
| `SYNAPSE_LEARNING_RATE` | 0.1 | Salience update rate |
| `SYNAPSE_ROOT_BASE_SALIENCE` | 0.5 | Seed salience for slot 0 (synthetic root) |
| `SYNAPSE_DATA_DIR` | ./data | Ring file directory |
| `SYNAPSE_CONFIG_DIR` | ./config | api-keys.yml location |

## Running tests

```bash
mvn test                                    # 99 tests
mvn test -pl . -Dtest=*BenchmarkTest        # JMH benchmarks
mvn clean package -DskipTests              # Fat JAR → target/synapse-db-*.jar
```

## Project status

| Phase | Status | Description |
|-------|--------|-------------|
| 1 — Core | Shipped | SynapseGraph: 8 SoA arrays, ring buffer, FCNS, path-to-root |
| 2 — Persistence | Shipped | Binary ring file: mmap write + bootstrap |
| 3 — Scoring | Shipped | HebbianScorer + getBestNextThought |
| 4 — API | Shipped | Spring Boot REST API + auth + error model |
| 5 — Docker | Planned | Single image, /data volume mount |
| 6 — Hardening | Planned | Checksums, rate limiting, Micrometer metrics |
