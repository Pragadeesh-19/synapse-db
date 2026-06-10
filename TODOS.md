# Synapse-DB — Deferred Work (TODOS)

Tracked follow-ups from `/plan-eng-review` (Phase 1 design review, 2026-06-06).

---

## T-EPOCH — Per-slot epoch/generation tags for FCNS correctness

- **What:** Add a generation counter per slot, bumped on each ring reuse. Validate
  the generation when traversing `firstChild`/`nextSibling`; treat a mismatched
  link as `-1` (no link).
- **Why:** The Phase-1 bounded walk-guard *contains* the eviction+reuse failure
  mode (prevents the infinite-loop hang) but does not *cure* it — sibling walks
  can still wander into a reused slot's new (unrelated) subtree and return a stale
  best-next. Epoch tags make stale links self-healing.
- **Pros:** Eliminates cross-tree corruption and stale results entirely; turns the
  V2 "Compaction Forward" item into a smaller problem.
- **Cons:** Adds an array + a comparison on every traverse — hot-path cost V1
  deliberately avoided.
- **Context:** Decided in review A3 to ship the bounded walk-guard for V1 and defer
  the full fix. The guard lives in the `getBestNextThought` sibling walk; this TODO
  is the principled replacement.
- **Depends on / blocked by:** Phase 1 eviction logic. Natural fit alongside V2
  Compaction Forward.

## T-MULTIWRITER — Race-free multi-writer concurrency

- **What:** Replace the current spec's multi-writer recipe with a correct one:
  publish slot data with a release fence on the FCNS link, read with an acquire
  fence; use `VarHandle` for `long timestamps[]` reads/writes to avoid tearing.
- **Why:** The spec's documented recipe (plain writes + `releaseFence`, FCNS under
  lock) has a publication-ordering gap — a reader walking `firstChild` can observe
  the link before the slot's data is visible — plus non-atomic `long` writes.
  Building on it as-is ships a data race.
- **Pros:** Safe concurrent writers per shard; prevents anyone copying the unsafe
  recipe later.
- **Cons:** Real complexity + benchmarking, for a capability V1's single-threaded
  targets don't require.
- **Context:** Decided in review A6 to implement single-writer-only in V1 (which is
  correct and needs no sync) and move multi-writer to Phase 6/V2. Until this lands,
  the spec's multi-writer section should be marked "not yet correct."
- **Depends on / blocked by:** A real requirement for >1 writer per agent shard
  (none today). Phase 6 concurrency work.

## T-PERSIST-BENCH — Benchmark the integrated append()+writeRecord() hot path

- **What:** A JMH benchmark of append-with-persistence once the Phase 4 wiring lands,
  confirming the combined path still beats the `<1µs` / `>1M ops/sec` target after the
  mmap `putXXX()` record write plus the header `writeHead` `putLong()` are added.
- **Why:** Phase 1 benchmarked pure in-memory append (~48ns). Phase 2 deliberately does
  NOT wire persistence into `append` (eng-review D1 chose decoupled persistence), so the
  combined cost is unmeasured until Phase 4. The header `writeHead` write touches a
  second page / cache line per append — plausibly fine, but unverified against the
  CLAUDE.md headline target, which assumes append+persist together.
- **Pros:** Closes the loop on the headline perf target with persistence actually on;
  catches a regression at the phase boundary instead of in production.
- **Cons:** Cannot run until Phase 4 wiring exists; a reminder, not actionable today.
- **Context:** Decided in Phase 2 `/plan-eng-review` (D1 = decoupled persistence,
  D4 = persist `header.writeHead` immediately after each record write). This TODO
  ensures the decoupling didn't silently move the hot path out of benchmark coverage.
- **Phase 6 update (raised by `/review`):** the integrated append path now allocates on
  every call where the core `graph.append()` does not — `CrcChecksum.compute()` does
  `new CRC32C()` + `ByteBuffer.slice()` per record, and `SynapseEngine.appendThought`
  wraps the body in `appendTimer.record(() -> …)` (a capturing lambda). The CLAUDE.md
  "zero GC on the hot path" claim still holds for `SynapseGraph` but NOT for the engine
  append. When this benchmark runs it must (a) confirm the combined path still beats
  `<1µs`, and (b) check allocation rate (`-prof gc`). If it misses, the cures are a
  thread-local `CRC32C` + direct-byte checksum (drops the slice) and recording the timer
  via a non-capturing path. Until measured, treat the engine-append allocation-free claim
  as unverified.
- **Depends on / blocked by:** Phase 4 `SynapseEngineConfig` wiring of `AgentRingFile`
  into the append path.

## T-BESTNEXT-DEGREE-BENCH — Benchmark getBestNextThought at high degree (P3)

- **What:** A JMH benchmark of `getBestNextThought` at degree 50 and 500, asserting
  the per-call cost scales ~linearly with degree (confirms the O(degree) contract and
  that `exp`-per-child stays within budget at high fan-out).
- **Why:** The Phase 3 exit gate only measures degree-5 (`<5µs`). With the sibling-walk
  bound set to `shardSize` (Phase 3 eng-review D2, not a small cap), a legitimately
  high-degree node scans many siblings; nothing verifies the cost stays linear at, say,
  degree 500. A super-linear surprise there would go unnoticed until production.
- **Pros:** Scaling confidence for the read path; one extra `@Benchmark` method reusing
  the `BestNextBenchmark` harness; catches a regression at the phase boundary.
- **Cons:** Not required by any current spec target (CLAUDE.md names only degree-5);
  may stay untouched if real workloads never produce high-degree nodes.
- **Context:** Decided in Phase 3 `/plan-eng-review` (D6). Phase 3 ships the degree-5
  gate; this is the deferred scaling-confidence follow-up. Lives alongside the
  `BestNextBenchmark` added in Phase 3.
- **Depends on / blocked by:** `BestNextBenchmark` (Phase 3).

## T-KEY-PERSIST — Persist runtime-registered API key hashes across restart (Phase 4 follow-up)

- **What:** Write runtime-minted key hashes (`sha256 → agentId+label`) to a durable
  sidecar (or fold into the bootstrap path) so agents registered via `POST /api/v1/agents`
  can still authenticate after a server restart.
- **Why:** Phase 4 D3 keeps runtime keys **in-memory only** (honors "no runtime writes to
  the key file"). The consequence: after a restart, a runtime-registered agent's ring-file
  data survives but its key is gone, so it can no longer authenticate — a confusing
  operational sharp edge. Pre-seeded `api-keys.yml` agents are unaffected.
- **Pros:** Closes the one real gap in the in-memory-key decision; makes restart safe for
  runtime agents without forcing operators to pre-seed every key.
- **Cons:** Introduces runtime file writes the V1 spec deliberately deferred; needs its own
  crash-safety story (torn write of a key record must not lock out an agent).
- **Context:** Decided in Phase 4 `/plan-eng-review` (D3 = in-memory PUT + documented
  limitation). Builds on `ApiKeyConfigLoader` + the in-memory `ConcurrentHashMap`. Natural
  fit alongside the Phase 6 checksum/crash-safety work.
- **Depends on / blocked by:** Phase 4 auth layer (`ApiKeyFilter`, `ApiKeyConfigLoader`).

## T-HEALTHCHECK-ACTUATOR — **DONE (Phase 6)**

Phase 6 added `spring-boot-starter-actuator`. The Dockerfile HEALTHCHECK now uses
`CMD curl -f http://localhost:9090/actuator/health` on the management port (9090).
This is the correct semantic signal — it exercises the Spring Boot health contract,
not just the API doc renderer. No further action required.

## T-SHARD-INT-OVERFLOW — **DONE (Phase 6)**

Phase 6 added `MemoryConfig.MAX_SHARD_SIZE = 1 << 25` (33,554,432 slots). The compact
constructor now rejects any `SYNAPSE_SHARD_SIZE > MAX_SHARD_SIZE` at startup with a clear
error message, preventing the silent int-overflow in `HEADER_SIZE + slot * RECORD_SIZE`.
Default `SHARD_SIZE = 1 << 20` is well within the guard. No further action required.

## T-RATELIMIT-DISTRIBUTED — Distributed rate limiting across replicas (Phase 6 follow-up)

- **What:** Replace the in-memory Bucket4j `ConcurrentHashMap` buckets (per-agent and
  per-IP) with a distributed store (Redis, Hazelcast, or Bucket4j's distributed
  back-ends) so that rate limits survive restart and are shared across multiple replicas.
- **Why:** Phase 6 ships in-memory token buckets that reset on every JVM restart. A
  client that stays below 60 req/min across a restart is un-throttled for the window it
  already consumed. With multiple replicas behind a load balancer, different instances
  hold independent buckets — a client that hits each replica 60 times/min is effectively
  un-throttled.
- **Pros:** Correct rate limiting under horizontal scale and restart scenarios; Bucket4j
  has first-class distributed back-ends.
- **Cons:** New runtime dependency (Redis or equivalent); increased operational surface.
- **Context:** Phase 6 `/plan-eng-review` accepted in-memory buckets as V1 (single
  replica, restarts tolerated). Tracked explicitly because the current limit silently
  degrades under scale.
- **Depends on / blocked by:** Phase 6 rate-limiting (`RateLimitFilter`, `Bucket4j`).

## T-RATELIMIT-IPBUCKETS-UNBOUNDED — Bound the per-IP bucket map (memory DoS)

- **What:** `RateLimitFilter.ipBuckets` is a `ConcurrentHashMap<String, Bucket>` keyed by
  `request.getRemoteAddr()` with no eviction. Every distinct source IP that hits
  `POST /api/v1/agents` creates a permanent entry. Under a flood of distinct (or spoofed)
  source addresses the map grows without bound → heap exhaustion. `agentBuckets` is NOT
  affected (keyed by `agentId`, bounded by `maxAgents`).
- **Why:** The registration bucket exists to throttle shard-allocation abuse, but the bucket
  *store* itself becomes an amplification target — an attacker turns a rate-limit defence
  into a memory-growth vector. Surfaced by Phase 6 `/review`.
- **Pros:** A bounded/expiring cache (e.g. Caffeine with a TTL ≥ the refill period, or
  Bucket4j's `ProxyManager` with expiry) caps memory while preserving the limit semantics.
- **Cons:** Adds a cache dependency; tuning TTL vs. the refill window needs care so a bucket
  isn't evicted while still rate-limiting an active attacker.
- **Context:** Folds naturally into `T-RATELIMIT-DISTRIBUTED` (a distributed store solves
  both unbounded growth and cross-replica sharing) and overlaps `T-TRUSTED-PROXY`.
- **Depends on / blocked by:** Phase 6 rate-limiting (`RateLimitFilter`).

## T-TRUSTED-PROXY — Honor X-Forwarded-For for per-IP rate limiting

- **What:** `RateLimitFilter` keys the per-IP registration bucket on `request.getRemoteAddr()`,
  which returns the reverse proxy's IP when Synapse-DB is deployed behind nginx/Ingress.
  All registration requests appear to come from one IP — per-IP rate limiting is
  effectively disabled in that topology.
- **Why:** The registration bucket (5/min by default) is the primary defence against
  shard-allocation abuse. If all requests share one bucket key (the proxy IP), the limit
  applies to ALL registrations, not per originating client — wrong in both directions
  (too strict if one legit admin registers many agents, too loose for a single bad actor).
- **Pros:** Correct per-client accounting; consistent with how every other Spring Boot
  rate-limiter reads client IP.
- **Cons:** Requires an allowlist of trusted proxy CIDRs or a `ForwardedHeaderFilter`;
  naively trusting `X-Forwarded-For` is an IP spoofing vector.
- **Context:** Phase 6 shipped `getRemoteAddr()` as the safe default (no spoofing risk
  without proxy trust). Fix requires deciding whether Synapse-DB ships a trusted-proxy
  config or delegates header rewriting to the ingress layer.
- **Depends on / blocked by:** Phase 6 rate-limiting (`RateLimitFilter`).
