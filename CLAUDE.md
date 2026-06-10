# Synapse-DB - Claude Code Project Context
**Version 1.6 | Java 21 | Spring Boot 3.x | No external database**

> **Revised 2026-06-06 by Phase 1 `/plan-eng-review`.** Eight design decisions
> override the original v1.1 spec: 8 arrays (not 9 — `thoughtIds` dropped), slot 0
> reserved as a never-evicted synthetic root, per-agent array slices (not one flat
> `absoluteIndex` array), explicit `-1` FCNS init, salience seeded from parent,
> caller-provided path buffer, fail-fast config validation, and single-writer-only
> for V1. Changed sections are marked **[v1.2]**.
>
> **Revised 2026-06-07 by Phase 2 `/plan-eng-review`.** Five persistence decisions:
> decoupled persistence (`append()` stays pure — orchestrator calls `writeRecord()`),
> `MappedByteBuffer` + `invokeCleaner()` unmap on `close()` for Windows, core owns
> FCNS rebuild (`loadSlot()` + `rebuildFcns()` live in `SynapseGraph`), commit-bit
> ordering (timestamp written last), and allocation-free bootstrap via primitive-args
> `loadSlot()`. Changed sections are marked **[v1.3]**.
>
> **Revised 2026-06-07 by Phase 3 `/plan-eng-review`.** Four scoring/walk decisions:
> `getBestNextThought` lives IN `SynapseGraph` (D1 — direct array access, consistent
> with `getPathToRoot`/`countChildren`); `BestPathQuery.java` is **DROPPED** (never
> created, walks were always in-engine); walk bound = `shardSize` not 64 (D2 — 64
> would silently cap high-degree nodes since FCNS is newest-first); ΔTime uses smooth
> `double` division + `max(0,…)` clamp to prevent sub-unit loss and future-timestamp
> amplification (D3); full loop hardening — clock read once, `timestamp==0` skip,
> `BestNextResult.NONE` sentinel (D4). Changed sections are marked **[v1.4]**.
>
> **Revised 2026-06-09 by Phase 6 hardening.** Six hardening decisions:
> CRC32C commit bit (D1 — `slotIndex[+0]` repurposed as CRC32C over bytes `[+4..+31]`,
> written LAST; VERSION bumped to 2; v1 files rejected at open time with clear message);
> T-SHARD-INT-OVERFLOW closed by `MemoryConfig.MAX_SHARD_SIZE = 1 << 25` fail-fast guard
> (D2 — consistent with existing validation style; default 1M shard is well within the
> bound); Micrometer metrics at `SynapseEngine` boundary — timers (`synapse.append.latency`,
> `synapse.bestnext.latency`), counters (`synapse.bootstrap.corrupt.skipped`,
> `synapse.ringfile.open.failures`, `synapse.ratelimit.rejections`), gauge
> (`synapse.shard.fill.percent`) (D3); Bucket4j rate limiting — per-agent 60/min
> and per-IP registration 5/min, configurable via `SYNAPSE_RATELIMIT_*` env vars,
> `@Order(2)` filter after `ApiKeyFilter` (D4); management port separation —
> `management.server.port=9090`, only `health` and `prometheus` exposed, `ApiKeyFilter`
> skips management traffic (D5); `GlobalExceptionHandler.noResource()` handles
> `NoResourceFoundException` → 404 so management-server child context never returns 500
> for unmatched actuator paths (D6). T-HEALTHCHECK-ACTUATOR closed: Dockerfile now uses
> `curl -f http://localhost:9090/actuator/health`. Changed sections marked **[v1.6]**.
>
> **Revised 2026-06-08 by Phase 4 `/plan-eng-review`.** Five API-layer decisions:
> a `SynapseEngine` facade owns the append→read-back→`writeRecord` orchestration +
> ring-file registry + per-agent lock (D1 — controllers stay thin, one home for the
> persist invariant); writes are serialized by a **per-agent `ReentrantLock`** in the
> engine because multi-threaded Tomcat would otherwise corrupt the lock-free
> single-writer core (D2); runtime-minted API keys live **in-memory only** (D3 —
> honors "no runtime file writes"; restart-loses-runtime-keys is a documented V1
> limitation, cure tracked as `T-KEY-PERSIST`); a **full typed error model**
> (`@Valid` DTOs + engine guard exceptions + one `@RestControllerAdvice`) replaces the
> core's `assert` guards, which are OFF in production (D4); the auth filter uses an
> **explicit `shouldNotFilter()` allowlist** (POST `/api/v1/agents`, swagger, api-docs)
> (D5). Ring files are a `Map<Integer,AgentRingFile>` registry (NOT a fixed
> `AgentRingFile[]`) since agents register at runtime. Changed sections are marked
> **[v1.5]**.

---

## What This Project Is

Synapse-DB is an in-memory graph database for autonomous AI agent reasoning trees.
It stores a directed acyclic graph of agent "thoughts" as flat parallel primitive arrays
(Struct-of-Arrays layout) for CPU L1/L2 cache coherence. Persistence is via pre-allocated
memory-mapped binary ring files — no Elasticsearch, no external process.

The core performance contract: append a thought in O(1), find best next thought in O(degree),
backtrack to root in O(depth). GC pauses on the hot path: zero.

---

## Commands

```bash
mvn spring-boot:run                         # Start the server (port 8080)
mvn test                                    # Run unit tests
mvn test -pl . -Dtest=*BenchmarkTest        # Run JMH benchmarks
mvn clean package -DskipTests              # Build the fat JAR
curl http://localhost:8080/swagger-ui.html  # API docs
```

---

## Architecture — Read This Before Touching Any File

### The Core Data Structure **[v1.2]**

The graph is stored as EIGHT parallel primitive arrays in one class: `SynapseGraph`.
Never add object arrays. Never create a `Node` class. Every thought is represented
by its local slot index across these arrays.

```
int[]   parentIds        // parent slot in same shard
int[]   firstChild       // FCNS: parent → first child slot (-1 = none)
int[]   nextSibling      // FCNS: child → next sibling slot (-1 = none)
float[] successScores    // reinforcement signal [-1.0, 1.0]
int[]   stateHashes      // environment state fingerprint (int hash, not vector)
int[]   sessionIds       // run/conversation identifier
long[]  timestamps       // epoch millis at write time (0 = empty/never-written slot)
float[] salienceScores   // accumulated Hebbian weight, clamped [0.0, 1.0]
```

`thoughtIds` was DROPPED (v1.1 had 9 arrays). It always equalled the slot index, so
it stored zero information while costing ~256 MB of cache-competing memory. The slot
index *is* the thought ID; derive it directly. An empty (never-written) slot is
detected by `timestamps[slot] == 0` — epoch-0 writes never happen.

### Sharding **[v1.2]**

Each agent owns its OWN set of 8 arrays, allocated lazily on registration (~36 MB
per agent). v1.1 specified one flat array per field sized `MAX_AGENTS * SHARD_SIZE`
(~2.4 GB committed at startup regardless of real agent count); this was changed
because agents never share cache lines (shards are megabytes apart), so per-agent
slices give identical cache locality at a fraction of the memory.
```
SHARD_SIZE = 1 << 20  (1,048,576 slots per agent — must be power of 2)
Each agent's arrays are indexed by localSlot directly — there is NO global
absoluteIndex / base offset anymore. parentIds/firstChild/nextSibling are all
local slot indices within the agent's own arrays.
Usable capacity per agent: SHARD_SIZE - 1 (slot 0 is the reserved root).
```

Never mix agent data. Never let one agent read another agent's arrays.

### Ring Buffer **[v1.2]**

**Slot 0 is a reserved, never-evicted synthetic root.** Ring writes occupy slots
`[1, SHARD_SIZE - 1]`. The write head starts at 1 and skips 0 on wrap. This keeps
`getPathToRoot`'s `while (ptr != 0)` terminus valid forever — v1.1 let the ring wrap
overwrite slot 0, destroying root identity on the first full wrap.

Within each shard, the write head wraps using a bitmask — never modulo:
```java
int local = writeHead & SHARD_MASK;   // NOT: writeHead % SHARD_SIZE
if (local == 0) local = 1;            // skip the reserved root slot on wrap
```

When a slot is overwritten (ring wrap):
1. Reset `firstChild[slot] = -1` and `nextSibling[slot] = -1` for the evicted slot
2. V1 known limitation: sibling chains pointing TO the evicted slot become stale.
   This is NOT a harmless dangling pointer — a reused slot can splice an unrelated
   subtree into a walk or form a cycle. V1 mitigation: the FCNS sibling walk is
   BOUNDED (see getBestNextThought) so a corrupted chain returns a bounded result
   instead of hanging the read path. Full cure (epoch tags) is deferred — see
   TODOS.md `T-EPOCH`.

### FCNS Invariant (Critical) **[v1.2]**

Every append must update FCNS as a prepend to the parent's child list:
```java
nextSibling[slot]       = firstChild[parentSlot];  // step 1: link new → old first
firstChild[parentSlot]  = slot;                     // step 2: parent → new child
```
Order matters. Step 1 always before step 2.

### Key Invariant **[v1.2]**

The slot index IS the thought ID — there is no `thoughtIds` array to keep in sync.
Parent and sibling references are local slot indices within the same agent's arrays.
`parentIds[ptr]` is an O(1) parent lookup (no base offset — per-agent arrays).
Arrays must be initialized with `firstChild`/`nextSibling` filled to `-1` at
construction (Java zero-inits to 0, which would make slot 0 look like everyone's
child).

---

## Algorithms

### getPathToRoot — O(depth) **[v1.2]**
Writes into a CALLER-PROVIDED buffer (zero allocation on the query path — never
`new int[]` per call). Slot 0 (root) is the terminus and is not included in the path.
```java
int count = 0;
while (ptr != 0 && count < maxDepth) {
    out[count++] = ptr;
    ptr = parentIds[ptr];  // direct array read, no pointer chase, no base offset
}
return count;  // number of slots written into out[]
```

### getBestNextThought — O(degree), NOT O(n) **[v1.4]**
Lives in `SynapseGraph` (same class as `getPathToRoot` and `countChildren`).
`BestPathQuery.java` was DROPPED — walks were always in-engine. The sibling walk is
BOUNDED at `shardSize` (D2: NOT 64 — a small cap would silently truncate high-degree
nodes since FCNS stores newest-first). Clock is read ONCE before the loop (D4). Slots
with `timestamp==0` are skipped defensively (D4, T-EPOCH risk). Returns
`BestNextResult.NONE` if no valid child exists.
```java
long now = clock.getAsLong();           // D4: single clock read
int child = firstChild[currentSlot];
int guard = 0;
while (child != -1 && guard++ < shardSize) {   // D2: shardSize bound
    if (timestamps[child] != 0L) {              // D4: skip empty/evicted slots
        float score = HebbianScorer.score(..., now, ...);
        // track best
    }
    child = nextSibling[child];
}
```
NEVER scan the full shard looking for `parentIds[i] == currentSlot`. That is O(n) and was explicitly rejected in design review.

### Hebbian Scoring Formula **[v1.4]**
```
Score = successScore[i] * salienceScore[i] * exp(-λ * ΔTime) * sessionBoost

ΔTime = Math.max(0.0, (now - timestamps[i]) / (double) decayUnitMs)
        ^^^ smooth double (not long) division for sub-unit recency discrimination
        ^^^ max(0,…) clamp prevents future/skewed timestamps from amplifying score
λ = 0.1 (default, configurable via SYNAPSE_LAMBDA env var)
sessionBoost = sessionIds[i] == currentSessionId ? 2.0f : 1.0f
```

### Salience Update (Hebbian rule, on every append) **[v1.2]**
A new thought SEEDS its salience from its parent, then adjusts by its own
`successScore` (the "reinforcement"). The root (slot 0) seeds at `ROOT_BASE_SALIENCE`
(default 0.5, configurable). This is why a freshly appended thought can already read
e.g. 0.38 in the API example — it inherited most of it from the parent.
```java
float parentSalience = salienceScores[parentSlot];
float updated = parentSalience + LEARNING_RATE * successScore;  // LEARNING_RATE = 0.1
return Math.max(0.0f, Math.min(1.0f, updated));                 // clamp to [0, 1]
```

---

## Persistence: Binary Ring File (NOT Elasticsearch)

No Elasticsearch. No JSON serialization. No external process.

Each agent has one pre-allocated binary file: `./data/agent-{agentId}.bin`
File size: 64 bytes header + 1,048,576 × 32 bytes records = ~32 MB per agent.

```
Header (64 bytes):
  [0]  magic         long  (0x53594E41505345_44L)
  [8]  version       int   (1)
  [12] agentId       int
  [16] writeHead     long  (monotonic counter)
  [24] activeSession int
  [28] reserved      36B

Record at offset 64 + (localSlot × 32):
  [+0]  slotIndex     int
  [+4]  parentSlot    int
  [+8]  stateHash     int
  [+12] sessionId     int
  [+16] successScore  float
  [+20] salienceScore float
  [+24] timestamp     long
```

`firstChild` and `nextSibling` are NOT written to the ring file.
They are reconstructed from `parentSlot` relationships during bootstrap (one O(n) pass).

Write path: `MappedByteBuffer.putXXX()` — writes go to OS page cache, no syscall, no GC.
The OS flushes to disk. Never call `mmap.force()` on the hot path.

**Commit-bit ordering [v1.3]:** `writeRecord()` writes `timestamp` LAST. A torn write
(crash mid-record) leaves `timestamp == 0` in the file; bootstrap skips that slot as if
it were empty. The field write order in `writeRecord()` is a contract — never change it.

**Byte order [v1.3]:** `buffer.order(ByteOrder.BIG_ENDIAN)` must be called explicitly after
every `MappedByteBuffer` construction on both write and read paths. Java's default is
`BIG_ENDIAN`, but explicit is better — it makes the on-disk format self-documenting and
safe against any future `ByteBuffer.allocate()` helper that defaults to little-endian.

**Bootstrap API [v1.3]:** `AgentRingFile.bootstrapInto(graph)` calls
`graph.loadSlot(agentId, slot, parent, hash, sess, succ, sal, ts)` — 8 primitive args,
zero allocation — for each record where `timestamp != 0`, then calls
`graph.rebuildFcns(agentId)` once. FCNS reconstruction lives in `SynapseGraph` (D3: one
copy of the prepend invariant). After rebuild, `graph.restoreWriteHead(agentId, writeHead)`
sets the ring head from the header so the next `append()` continues at the right slot.

**Windows close() [v1.3]:** `AgentRingFile.close()` calls
`sun.misc.Unsafe.invokeCleaner(buffer)` (via the `theUnsafe` reflective field) to force-
unmap before returning. Without this, Windows keeps the file locked until GC, breaking
any test that creates then deletes a ring file. The call is guarded by try/catch; if the
JVM blocks it, unmap falls back to GC (V2 cure: FFM `MemorySegment.map(Arena)`).

---

## Package Layout

```
com.synapsedb
├── core/
│   ├── SynapseGraph.java        # The 8 arrays, per-agent slices, ring buffer, append + FCNS
│   │                            #   also: getBestNextThought, getPathToRoot, countChildren [v1.4]
│   ├── MemoryConfig.java        # Constants: SHARD_SIZE, MAX_AGENTS, LAMBDA, etc. (fail-fast validated)
│   ├── HebbianScorer.java       # Pure stateless score formula, sessionBoost, decay math [Phase 3]
│   └── BestNextResult.java      # Record: bestSlot, bestScore [Phase 3]
│   # NOTE: BestPathQuery.java was DROPPED (Phase 3 D1). Walks live in SynapseGraph. [v1.4]
│
├── persistence/
│   ├── AgentRingFile.java       # MappedByteBuffer management, writeRecord(), bootstrapInto()
│   └── RingFileHeader.java      # Read/write the 64-byte file header
│
├── engine/
│   ├── SynapseEngine.java       # [v1.5 D1] Orchestration facade. Owns SynapseGraph +
│   │                            #   Map<Integer,AgentRingFile> registry + per-agent
│   │                            #   ReentrantLock. appendThought() = append → read-back
│   │                            #   ts+salience → writeRecord under the agent lock.
│   │                            #   Also: register(), bestNext(), pathToRoot(), bootstrap(),
│   │                            #   stats(). The ONLY home for the append+persist invariant.
│   ├── AppendResult.java        # [v1.5] Record: slot, salience, persisted
│   ├── MemoryStats.java         # [v1.5] Record: agentId, writeHead, usedSlots, capacity, fillPercent, wrapped
│   └── exception/               # [v1.5 D4] UnknownAgentException(→404),
│                                #   InvalidParentException(→409), InvalidRequestException(→400),
│                                #   ThoughtNotFoundException(→404), CapacityReachedException(→503)
│
├── api/
│   ├── controller/
│   │   ├── AgentController.java     # POST /api/v1/agents — thin: DTO → engine.register()
│   │   ├── ThoughtController.java   # POST /thoughts, GET /path-to-root, GET /best-next
│   │   └── MemoryController.java    # POST /bootstrap, GET /stats
│   ├── dto/                         # Request + Response records (one per endpoint)
│   ├── error/
│   │   └── GlobalExceptionHandler.java # [v1.5 D4] @RestControllerAdvice → clean JSON 4xx
│   └── auth/
│       ├── ApiKeyFilter.java        # OncePerRequestFilter: SHA-256 hash → lookup.
│       │                            #   [v1.5 D5] shouldNotFilter() allowlist: POST
│       │                            #   /api/v1/agents, /swagger-ui/**, /v3/api-docs/**.
│       │                            #   Else require X-Api-Key AND key.agentId == {id}.
│       ├── AgentKeyRecord.java      # [v1.5] Record: agentId, label (value in the key map)
│       └── AgentContext.java        # Request-scoped: agentId, label
│
├── config/
│   ├── ApiKeyConfigLoader.java  # Load api-keys.yml → ConcurrentHashMap<hash,AgentKeyRecord>
│   │                            #   at startup. [v1.5 D3] Registration PUTs runtime keys
│   │                            #   into this same map (in-memory only — no file write).
│   ├── SynapseEngineConfig.java # @Bean wiring: SynapseGraph + SynapseEngine. Ring files
│   │                            #   are a runtime registry inside the engine, NOT a fixed
│   │                            #   AgentRingFile[]. [v1.5 D1] (BestPathQuery dropped) [v1.4]
│   └── WebConfig.java           # WebMvcConfigurer: registers AgentAuthorizationInterceptor
│                                #   on /api/v1/agents/** (Phase 4 CSO H1 defense-in-depth)
│
└── SynapseDbApplication.java
```

> **[v1.5] Controllers are thin.** They map DTO ↔ engine calls only. They NEVER touch
> `SynapseGraph` accessors or `AgentRingFile` directly — every mutation and read goes
> through `SynapseEngine`, which holds the per-agent lock and the append+persist
> invariant. This is the single most important boundary in the API layer.

---

## API Endpoints

| Method | Path | What it does |
|--------|------|-------------|
| POST | /api/v1/agents | Register agent, create ring file, return agentId + apiKey |
| POST | /api/v1/agents/{id}/thoughts | Append thought (O1 hot path) |
| GET | /api/v1/agents/{id}/thoughts/best-next | FCNS walk + Hebbian score |
| GET | /api/v1/agents/{id}/thoughts/path-to-root | Backtrack to root |
| POST | /api/v1/agents/{id}/bootstrap | Reload shard from ring file |
| GET | /api/v1/agents/{id}/memory/stats | Fill %, write head, session info |

All endpoints require `X-Api-Key` header. Auth is a servlet filter, not per-controller.

### Append request/response shape
```json
// POST /api/v1/agents/{agentId}/thoughts
// Request:
{ "parentId": 42, "stateHash": 99482, "successScore": 0.8, "sessionId": 7 }

// Response 201:
{ "thoughtId": 43, "slotIndex": 43, "salienceScore": 0.38, "persisted": true }
```

---

## Authentication

API keys are SHA-256 hashed and stored in `config/api-keys.yml`.
Loaded at startup into `ConcurrentHashMap<String, AgentKeyRecord>` (hash → agentId+label).
Auth filter: hash the raw key from `X-Api-Key` header → map lookup → verify agentId matches path variable.
Use `commons-codec` `DigestUtils.sha256Hex` for hashing — do NOT hand-roll `MessageDigest`.
No database. No runtime writes to the **key file** (V1).

**[v1.5 D5] Filter allowlist.** `ApiKeyFilter.shouldNotFilter()` returns `true` ONLY for
`POST /api/v1/agents` (registration — no key exists yet), `/swagger-ui/**`,
`/swagger-ui.html`, and `/v3/api-docs/**`. Every `/api/v1/agents/{id}/**` route requires a
valid `X-Api-Key` AND `key.agentId == {id}` (401 if missing/unknown, 403 if agent mismatch).

**[v1.5 CSO-H1] Defense-in-depth agentId guard.** `ApiKeyFilter` extracts the agent id from
the raw request URI with a regex; `AgentAuthorizationInterceptor` (registered in `WebConfig`
for `/api/v1/agents/**`) re-checks the authenticated `AgentContext` against the `{id}` path
variable Spring actually *resolved* — the same value bound to `@PathVariable int id`. This
collapses authorization to one source of truth: if the filter's URI parse ever diverged from
Spring's mapping, the interceptor returns 403 before the request reaches another agent's
shard. Do NOT remove it — it is the authoritative agentId match.

**[v1.5 D3] Runtime key lifecycle.** `POST /api/v1/agents` mints a new key and PUTs its
hash into the in-memory `ConcurrentHashMap` — an in-memory write, NOT a file write, so the
"no runtime writes to the key file" rule holds. **V1 limitation:** runtime-registered keys
live only in memory, so a restart locks out those agents even though their ring-file data
survives. Pre-seeded `api-keys.yml` agents are unaffected. Cure tracked as `T-KEY-PERSIST`.

Raw key format: `sk_syn_{UUID without dashes}`
Key is returned once at registration. Hash is stored. Raw key is never stored again.

---

## Concurrency Rules **[v1.2]**

- **V1 is single-writer-per-shard ONLY** — plain array writes, no synchronization.
  This is correct and meets the single-threaded perf targets.
- The v1.1 multi-writer recipe (`VarHandle.getAndAdd()` claim + plain writes +
  `releaseFence()`, FCNS under a `ReentrantLock`) is SHELVED to V2: it has a
  publication-ordering gap (a reader walking `firstChild` can see the link before the
  slot's data is visible) and relies on non-atomic `long` writes. Do NOT build on it.
  See TODOS.md `T-MULTIWRITER` for the correct acquire/release + `VarHandle` design.
- Never use `synchronized` blocks on `SynapseGraph` methods — too coarse
- Cross-agent reads are read-only (MESI Shared state) — no synchronization needed

### API-layer write serialization **[v1.5 D2]**

Tomcat is multi-threaded; the core is lock-free single-writer. To bridge them safely WITHOUT
touching the core, `SynapseEngine` holds a `ConcurrentHashMap<Integer, ReentrantLock>` and
takes the **per-agent** lock around every mutation (`appendThought`, `bootstrap`,
`register`). Different agents never block each other; the lock is uncontended under normal
single-writer-per-agent traffic. The lock lives in the engine, never in `SynapseGraph` (the
"no `synchronized` on `SynapseGraph`" rule above is intact — the core stays lock-free).

**V1 read limitation:** reads (`bestNext`, `pathToRoot`, `stats`) do NOT take the lock, so a
read concurrent with a write to the *same* agent could observe a torn FCNS link mid-prepend.
Safe under the V1 single-client-per-agent contract; the bounded walk guard prevents a hang.
Full read/write safety is the `T-MULTIWRITER` acquire/release design.

---

## Performance Targets (JMH)

Verify these at the end of Phase 1 and Phase 3:

| Operation | Target |
|-----------|--------|
| Append (single-threaded) | < 1 μs (> 1M/sec) |
| Path-to-root, depth 50 | < 10 μs |
| Best-next, degree 5 | < 5 μs |
| Bootstrap, 1M records | < 200 ms |

If append exceeds 1 μs, check: (1) FCNS lock contention, (2) MappedByteBuffer flush being called, (3) accidental heap allocation in the hot path.

---

## What NOT to Do

- Do NOT create a `Node` class or any object to represent thoughts
- Do NOT use `%` for ring buffer index calculation — always use `& SHARD_MASK`
- Do NOT scan `parentIds[]` to find children — use `firstChild[]` / `nextSibling[]`
- Do NOT add Elasticsearch as a dependency
- Do NOT call `mmap.force()` on the hot path (only via explicit endpoint if needed)
- Do NOT store `firstChild` or `nextSibling` in the ring file — reconstruct on bootstrap
- Do NOT put FCNS rebuild logic in `AgentRingFile` — `SynapseGraph.rebuildFcns()` is the single source of truth for the prepend invariant **[v1.3]**
- Do NOT write `timestamp` before the other record fields in `writeRecord()` — timestamp is the commit bit; write it last so a torn write leaves `timestamp == 0` and bootstrap skips the record **[v1.3]**
- Do NOT return raw API keys after the registration response
- Do NOT let agents access each other's shard ranges

---

## Implementation Phases

Build in this exact order — do not skip to the API layer before the core engine is benchmarked:

1. **Phase 1 (Weeks 1-2):** `SynapseGraph` with all 8 arrays, append + FCNS + ring
   eviction, `getPathToRoot`, bounded walk guard — JMH benchmarks must pass before
   proceeding. NOTE: the eviction+reuse no-hang test is Phase 1 (eviction lives in
   `append`), not Phase 3.
2. **Phase 2 (Week 3):** `AgentRingFile` — write record + bootstrap roundtrip test
3. **Phase 3 (Week 4):** `HebbianScorer` + `BestPathQuery` — full end-to-end flow
4. **Phase 4 (Weeks 5-6):** Spring Boot API layer — all 6 endpoints
5. **Phase 5 (Week 7):** Docker — single image (`eclipse-temurin:21-jre-jammy`), no compose,
   mount `/data` (ring files) + `/config` (api-keys.yml), non-root user UID 1000,
   `-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0` (75% leaves 25% for mmap
   page cache — correctness requirement, not tuning), `--add-opens jdk.unsupported/sun.misc=ALL-UNNAMED`
   for `AgentRingFile.invokeCleaner()`, HEALTHCHECK on `/v3/api-docs` (migrate to
   `/actuator/health` in Phase 6 — tracked as `T-HEALTHCHECK-ACTUATOR`)
6. **Phase 6 (Weeks 8-9):** Checksums, rate limiting (Bucket4j), Micrometer metrics

---

## V2 Roadmap (Do NOT implement in V1)

- Compaction Forward: copy high-salience thoughts before ring eviction
- Per-slot epoch/generation tags to fully cure stale FCNS chains (TODOS.md `T-EPOCH`)
- Race-free multi-writer-per-shard concurrency (TODOS.md `T-MULTIWRITER`)
- WAL file rotation for long-running agents
- Checksum per record for crash safety (V1 known limitation)
- Off-heap migration via Java 21 FFM API (only if GC benchmarks show need)
- Cosine similarity / ONNX Runtime vector extension

---

## Environment Variables

```
SYNAPSE_MAX_AGENTS=64              # Must match pre-allocated array sizes
SYNAPSE_SHARD_SIZE=1048576         # Must be power of 2
SYNAPSE_LAMBDA=0.1                 # Hebbian decay constant
SYNAPSE_DECAY_UNIT_MS=3600000      # 1 hour in millis
SYNAPSE_LEARNING_RATE=0.1          # Salience update rate
SYNAPSE_ROOT_BASE_SALIENCE=0.5     # [v1.2] root slot 0 seed salience (children inherit)
SYNAPSE_DATA_DIR=./data            # Ring file directory
SYNAPSE_CONFIG_DIR=./config        # api-keys.yml location
SYNAPSE_EMOTIONAL_THRESHOLD=0.7    # Salience threshold for V2 compaction (unused in V1)
```

## gstack

Use the `/browse` skill from gstack for all web browsing. Never use `mcp__claude-in-chrome__*` tools.

Available skills:
/office-hours, /plan-ceo-review, /plan-eng-review, /plan-design-review, /design-consultation, /design-shotgun, /design-html, /review, /ship, /land-and-deploy, /canary, /benchmark, /browse, /connect-chrome, /qa, /qa-only, /design-review, /setup-browser-cookies, /setup-deploy, /setup-gbrain, /retro, /investigate, /document-release, /document-generate, /codex, /cso, /autoplan, /plan-tune, /plan-devex-review, /devex-review, /careful, /freeze, /guard, /unfreeze, /gstack-upgrade, /learn

## Synapse-DB Skill Routing

This is a pure Java 21 backend microservice. No UI. No browser. No design.
Run skills in this order per phase — do not skip ahead.

RELEVANT:
/plan-eng-review  → Run before coding each phase. Reviews architecture.
/review           → Run after each phase's code is written. Staff engineer review.
/investigate      → Run when JMH benchmarks don't hit targets, or tests fail unexpectedly.
/cso              → Run after Phase 4 (API layer + auth complete). OWASP + STRIDE audit.
/ship             → Run after /review passes. Commits + opens PR per phase.
/document-release → Run after /ship. Keeps ARCHITECTURE.md, README, CLAUDE.md current.
/careful          → Activate when editing SynapseGraph.java (core engine — dangerous).
/freeze           → Lock edits to one package while debugging (e.g., freeze to core/).
/guard            → /careful + /freeze together. Use when touching ring file logic.
/retro            → Run weekly. Check shipping velocity, test health.
/learn            → Save JMH discoveries, Java 21 quirks, FCNS invariants across sessions.

NOT RELEVANT (do not run these):
/qa, /qa-only           → Browser QA. No UI in this project.
/design-*, /ios-*       → No UI, no iOS.
/browse, /benchmark     → Web browser tools. Not applicable.
/land-and-deploy        → Deployment pipeline. V1 ships as Docker image, not to a live URL.
/plan-design-review     → Design skill. Backend only.
/canary                 → SRE monitoring for live web deployments. Not applicable yet.