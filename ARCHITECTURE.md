# Synapse-DB Architecture

## Design goal

Store AI agent reasoning trees in a way that lets a hot path (append → persist → score)
run without heap allocation, without synchronization on the read path, and without
talking to any external process. The entire database lives in one JVM process.

---

## Why Struct-of-Arrays, not objects

The naive design puts each "thought" in a `Node` object:

```
Node { int parent, firstChild, nextSibling; float score, salience; ... }
```

Walking a chain of 50 nodes chases 50 object pointers spread across the heap. Each
pointer miss is a cache-line load (~60–200 ns). At ~50 nodes that is several
microseconds of pure cache traffic before any logic runs.

Synapse-DB uses Struct-of-Arrays (SoA) instead: every field across all thoughts for
one agent is stored in its own contiguous primitive array.

```
int[]   parentIds        // parent slot index
int[]   firstChild       // FCNS: first child of this node
int[]   nextSibling      // FCNS: next sibling in parent's child list
float[] successScores    // reinforcement signal [-1.0, 1.0]
int[]   stateHashes      // environment state fingerprint
int[]   sessionIds       // run/conversation identifier
long[]  timestamps       // epoch millis at write time (0 = empty slot)
float[] salienceScores   // accumulated Hebbian weight [0.0, 1.0]
```

Accessing `parentIds[ptr]`, `timestamps[ptr]`, and `salienceScores[ptr]` for a
sequence of thought IDs walks three arrays in lock-step. If those arrays fit in L2
cache (they do — 1M slots × 4 bytes = 4 MB per array), every access is a cache hit.
No pointer chasing, no GC, no object header overhead.

There is no `Node` class anywhere in this codebase. The slot index *is* the thought ID.
`thoughtIds` was explicitly dropped — it always equalled the slot index and wasted
~256 MB of L2-competing memory.

---

## Sharding

Each agent owns its own set of 8 arrays, allocated lazily on registration (~36 MB per
agent at the default `SHARD_SIZE = 1 << 20`). A global flat layout
(`MAX_AGENTS * SHARD_SIZE` per field, ~2.4 GB at startup) was considered and rejected:
agents never share cache lines, so per-agent slices give identical locality at a
fraction of the committed memory.

All slot indices are *local* to the agent's own arrays — there is no base offset
arithmetic. `parentIds[42]` for agent 3 means "agent 3's slot 42's parent", not some
global offset.

---

## Ring buffer

Each agent's arrays are a ring. The write head advances from slot 1 to
`SHARD_SIZE - 1`, then wraps back to slot 1 (slot 0 is reserved, see below).

```java
int local = writeHead & SHARD_MASK;   // bitmask, never %
if (local == 0) local = 1;            // skip the reserved root slot on wrap
```

Why bitmask instead of modulo: `SHARD_SIZE` is always a power of 2, so `& SHARD_MASK`
compiles to a single AND instruction. `%` on a non-constant divisor is a division.

### Slot 0 — synthetic root

Slot 0 is the fixed, never-evicted root of every agent's tree.
`getPathToRoot` terminates at `ptr == 0`, so the terminus is valid forever even as
the ring wraps. If slot 0 were evictable, the first full ring wrap would destroy the
root identity of every path in the shard.

When a slot is evicted by the ring wrapping over it, its `firstChild` and `nextSibling`
are reset to `-1`. Sibling walks in `getBestNextThought` are bounded at `shardSize`
iterations so a stale chain can't spin forever.

---

## FCNS — First Child Next Sibling

Children of a node are stored as a linked list through the `firstChild` / `nextSibling`
arrays, not as an object list. This makes `countChildren` and `getBestNextThought` O(degree)
with no allocation — just array reads.

Every append prepends the new child to the parent's list (newest-first order):

```java
nextSibling[slot]      = firstChild[parentSlot];  // step 1: link new → old head
firstChild[parentSlot] = slot;                     // step 2: parent → new head
```

Step 1 before step 2 is a contract — reversing them would make the new node's
`nextSibling` point to itself for a single-instruction window.

Because FCNS is newest-first, the sibling walk in `getBestNextThought` sees the most
recently appended children first, which biases toward fresh high-recency scores before
even computing the Hebbian formula.

---

## Hebbian scoring

`getBestNextThought(agentId, currentSlot, sessionId)` walks the FCNS sibling chain
from `firstChild[currentSlot]` and scores each child:

```
Score = successScore[i] × salienceScore[i] × exp(−λ × ΔTime) × sessionBoost

ΔTime      = max(0.0, (now − timestamps[i]) / (double) decayUnitMs)
λ          = 0.1  (default, env: SYNAPSE_LAMBDA)
sessionBoost = 2.0 if sessionIds[i] == currentSessionId, else 1.0
```

`ΔTime` is computed in smooth `double` to preserve sub-unit recency discrimination
(a thought appended 30 minutes ago should score differently than one appended 31
minutes ago). The `max(0, ...)` clamp prevents future-dated or clock-skewed timestamps
from amplifying a score beyond its un-decayed value.

### Salience seeding (Hebbian rule)

A new thought seeds its salience from its parent, adjusted by the reinforcement signal:

```java
float updated = parentSalience + LEARNING_RATE * successScore;
return Math.max(0.0f, Math.min(1.0f, updated));
```

This means a freshly appended thought with a strong positive success score and a
well-reinforced parent can already read a salience of ~0.38 before any scoring walk
touches it — it inherited accumulated weight from the chain above it.

---

## Binary ring file

Each agent has one pre-allocated binary file at `./data/agent-{agentId}.bin`.
The v2 format (Phase 6) repurposes the old `slotIndex` field as a CRC32C
commit bit, closing the torn-write window that v1 left after the timestamp write.

```
Header (64 bytes):
  [0]  magic         long   0x53594E41505345_44L
  [8]  version       int    2  (v2: CRC32C commit bit)
  [12] agentId       int
  [16] writeHead     long   monotonic counter
  [24] activeSession int
  [28] reserved      36 B

Record layout (32 bytes each, at offset 64 + slot × 32):
  [+0]  crc32c        int    CRC32C over bytes [+4..+31] — WRITTEN LAST (commit bit)
  [+4]  parentSlot    int
  [+8]  stateHash     int
  [+12] sessionId     int
  [+16] successScore  float
  [+20] salienceScore float
  [+24] timestamp     long
```

File size: 64 B header + 1,048,576 × 32 B records = ~32 MB per agent.

### Commit-bit ordering (v2 CRC32C)

`writeRecord()` writes fields `[+4..+31]` first (parent, state, session, scores,
timestamp), then computes CRC32C over those 28 bytes and writes it at `[+0]` **last**.

Bootstrap logic per slot:
- `timestamp == 0` → empty/never-written slot; skip (pre-check avoids false CRC flag)
- `timestamp != 0`, CRC mismatch → torn write (crash between data write and CRC write); skip and increment `synapse.bootstrap.corrupt.skipped` counter
- `timestamp != 0`, CRC matches → valid record; call `graph.loadSlot()`

A v1 file (VERSION=1) is rejected at open time with a clear message referencing "v2"
so operators know to delete the old files and re-register.

### MappedByteBuffer

Writes go through `MappedByteBuffer.putXXX()` directly to the OS page cache — no
syscall, no copy, no GC. `mmap.force()` is never called on the write path; the OS
decides when to flush dirty pages to disk.

`firstChild` and `nextSibling` are NOT stored in the ring file. Bootstrap reconstructs
them in one O(n) pass over all non-zero records, replaying the FCNS prepend invariant
in slot-order (ascending slot index, which matches write-head order for non-evicted
slots). This keeps the record size at 32 bytes and the on-disk format stable even if
the in-memory FCNS structure changes.

### Windows unmap

`AgentRingFile.close()` calls `sun.misc.Unsafe.invokeCleaner(buffer)` to force-unmap
the `MappedByteBuffer` before returning. Without this, Windows keeps the file locked
until GC, which breaks tests that delete ring files immediately after closing them.

---

## Rate limiting (Phase 6)

`RateLimitFilter` (`@Order(2)`, runs after `ApiKeyFilter`) enforces two token-bucket
strategies using Bucket4j:

| Strategy | Key | Default | Notes |
|----------|-----|---------|-------|
| Per-agent | `agentId` (from `AgentContext`) | 60 req / 60 s | Applied to every `/api/v1/agents/{id}/**` route |
| Per-IP registration | `request.getRemoteAddr()` | 5 req / 60 s | Applied to `POST /api/v1/agents` only |

Buckets are in-memory `ConcurrentHashMap` entries — they reset on restart. Limits are
configurable via `SYNAPSE_RATELIMIT_*` env vars (see Configuration). Distributed bucket
support is deferred (`T-RATELIMIT-DISTRIBUTED`).

A rejected request returns 429 Too Many Requests with `Retry-After` set to the bucket
refill period. Every rejection increments the `synapse.ratelimit.rejections` counter
(tagged by `type=agent` or `type=registration`).

---

## Micrometer metrics (Phase 6)

All metrics are registered to the `MeterRegistry` injected into `SynapseEngine` and
`RateLimitFilter`. In production, Spring Boot autoconfigures `PrometheusMeterRegistry`
so `/actuator/prometheus` on the management port (9090) serves the full metric set.

| Metric name | Type | Description |
|-------------|------|-------------|
| `synapse.append.latency` | Timer | Wall time of `appendThought()` incl. lock + persist |
| `synapse.bestnext.latency` | Timer | Wall time of `bestNext()` FCNS walk + scoring |
| `synapse.shard.fill.percent` | Gauge | `usedSlots / capacity * 100` per agent (tag: `agentId`) |
| `synapse.bootstrap.corrupt.skipped` | Counter | Records skipped due to CRC mismatch on bootstrap |
| `synapse.ringfile.open.failures` | Counter | Ring file open errors (I/O or version mismatch) |
| `synapse.ratelimit.rejections` | Counter | 429 responses (tag: `type=agent\|registration`) |

The management server runs on port 9090 (`management.server.port=9090`), separate from the
API port (8080). `ApiKeyFilter` uses `shouldNotFilter()` to skip management traffic; only
`/actuator/health` and `/actuator/prometheus` are exposed.

---

## Engine facade and API layer

```
HTTP request
    │
    ▼
ApiKeyFilter (OncePerRequestFilter)
    │  SHA-256 hash X-Api-Key → lookup AgentKeyRecord
    │  shouldNotFilter() allowlist: POST /api/v1/agents, /swagger-ui/**, /v3/api-docs/**
    ▼
AgentAuthorizationInterceptor (HandlerInterceptor, CSO H1)
    │  Re-checks AgentContext.agentId == Spring's resolved {id} path variable
    │  Collapses authorization to one source of truth regardless of URI-parsing divergence
    ▼
Controller (thin)
    │  Validates @Valid DTO fields; maps to engine call
    ▼
SynapseEngine (orchestration facade)
    │  Per-agent ReentrantLock serializes writes from Tomcat's thread pool
    │  append → read-back ts+salience → writeRecord (the append+persist invariant)
    │  Throws typed exceptions: UnknownAgentException, InvalidParentException, etc.
    ▼
SynapseGraph (lock-free single-writer core)
    │  8 primitive arrays, FCNS, ring buffer
    ▼
AgentRingFile (MappedByteBuffer)
    │  writeRecord() → OS page cache
    ▼
disk
```

### Why per-agent lock in the engine, not in the graph

`SynapseGraph` is deliberately lock-free — adding `synchronized` to it would serialize
reads and writes across all agents. Instead, `SynapseEngine` holds a
`ConcurrentHashMap<Integer, ReentrantLock>` and takes the lock only for the one agent
being mutated. Different agents never block each other; the lock is uncontended under
normal single-writer-per-agent traffic.

### Why typed exceptions instead of asserts

Core `assert` guards are disabled by default in production JVMs (`-ea` is not set by
Spring Boot). `SynapseEngine` validates inputs before calling the core and throws typed
exceptions (`UnknownAgentException` → 404, `InvalidParentException` → 409, etc.) that
`GlobalExceptionHandler` maps to clean JSON error responses. The core itself can stay
guard-free and allocation-free.

---

## Docker deployment

The JAR is packaged into a single container — no docker-compose, no external process.
Two mount points must be bound to persistent host storage:

```
Host bind mounts (required)
    ./data/   ─────────── /data   ← ring files (one per agent, ~32 MB each)
    ./config/ ─────────── /config ← api-keys.yml (pre-seeded API key hashes)

Container layout
    /app/synapse-db.jar           ← fat JAR copied from builder stage
    UID 1000 (synapse user)       ← non-root; /data and /config chowned at build time

JVM flags (baked into ENTRYPOINT)
    -XX:+UseZGC -XX:+ZGenerational      ← low-latency GC; no stop-the-world on write path
    -XX:MaxRAMPercentage=75.0            ← 75% heap, 25% reserved for OS mmap page cache
    --add-opens jdk.unsupported/sun.misc=ALL-UNNAMED
                                         ← required for AgentRingFile.invokeCleaner()
```

### Why MaxRAMPercentage=75.0 matters for mmap

The SoA arrays are heap-allocated Java objects. At 64 agents that is ~2.5 GB of arrays
alone plus ~400 MB for Spring Boot. The OS also needs RAM to buffer dirty
`MappedByteBuffer` pages before flushing them to disk. If the JVM heap takes 80–90% of
container memory, page-cache gets squeezed — mmap writes page-fault on every record and
the performance contract collapses. `MaxRAMPercentage=75.0` is a correctness requirement,
not a tuning preference.

### Volume mount contract

| Mount | Required? | Lost without it |
|-------|-----------|----------------|
| `/data` | Yes | Ring files written inside container; lost on `docker stop` |
| `/config` | Conditional | Pre-seeded keys absent; runtime-registered agents still work but lose keys on restart |

### Multi-stage build

Stage 1 (`eclipse-temurin:21-jdk-jammy AS builder`) runs `mvn package`. Stage 2
(`eclipse-temurin:21-jre-jammy`) copies only the fat JAR — the final image contains
no JDK, no Maven wrapper, no source code. This eliminates the stale-JAR risk (building
outside Docker and then copying in) and produces a ~280 MB compressed image.

---

## Known V1 limitations

- **Runtime-registered keys are lost on restart** (`T-KEY-PERSIST`). Pre-seeded
  `api-keys.yml` agents are unaffected.
- **Stale FCNS chains** after ring eviction are bounded (walk terminates) but not
  fully cured (`T-EPOCH`). A reused slot can appear in a sibling walk under very
  high churn.
- **No multi-writer per shard** (`T-MULTIWRITER`). Tomcat writes are serialized by
  the engine lock; the core arrays have no synchronization.
- **In-memory rate-limit buckets** reset on restart and are not shared across replicas
  (`T-RATELIMIT-DISTRIBUTED`).
- **Per-IP rate limiting** uses `getRemoteAddr()` which sees the proxy IP when deployed
  behind nginx/Ingress (`T-TRUSTED-PROXY`).
- **Parent validation runs outside the per-agent lock** (`T-VALIDATE-PARENT-RACE`). A
  concurrent append on the same agent can evict the parent between validation and the
  lock being acquired, causing silent FCNS graph corruption.
- **Registry-lock split** (`T-REGISTRY-LOCK-SPLIT`). `registerNewAgent` (synchronized)
  and `registerExistingAgent` (per-agent lock) share `ringFiles` with no common lock;
  a concurrent pair can open two handles to the same ring file.
- **Hand-rolled JSON in auth filters omits control-character escaping** (`T-JSON-ESCAPE`).
  Labels with `\n`/`\r`/control chars produce invalid JSON per RFC 8259.
- **`writeHead` is not volatile** (`T-STATS-VOLATILE`). Prometheus scrape threads reading
  `stats()` or the fill-percent gauge can observe stale values under concurrent writes.
