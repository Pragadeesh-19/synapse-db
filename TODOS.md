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

## T-HEALTHCHECK-ACTUATOR — Migrate Dockerfile HEALTHCHECK to /actuator/health (Phase 6 follow-up)

- **What:** Replace `CMD curl -f http://localhost:8080/v3/api-docs` in the Dockerfile
  HEALTHCHECK with `CMD curl -f http://localhost:8080/actuator/health` once Phase 6 adds
  `spring-boot-starter-actuator`.
- **Why:** `/v3/api-docs` is a springdoc endpoint — it works as a liveness proxy (200 when
  the Spring context is up) but is semantically incorrect: it tests the API doc renderer,
  not the app health contract. `/actuator/health` gives proper liveness and readiness
  distinction and is the standard for containerized Spring Boot.
- **Pros:** Correct semantic signal to Docker / Kubernetes; enables separate readiness
  probes (e.g., warm-up period before serving traffic).
- **Cons:** Requires `spring-boot-starter-actuator` as a new dependency; without it
  `/actuator/health` returns 404 and the HEALTHCHECK immediately fails.
- **Context:** Phase 5 uses `/v3/api-docs` as a zero-new-dependency proxy. This TODO
  is the clean-up once Phase 6 adds actuator. Do NOT migrate without the actuator dep.
- **Depends on / blocked by:** Phase 6 hardening (`spring-boot-starter-actuator`).

## T-SHARD-INT-OVERFLOW — Guard record-offset int arithmetic against large SHARD_SIZE (security/robustness)

- **What:** `AgentRingFile.writeRecord` / `bootstrapInto` compute the record offset as
  `int base = HEADER_SIZE + slot * RECORD_SIZE`. With `slot` up to `shardSize-1` and
  `RECORD_SIZE = 32`, this `int` multiply overflows once `SYNAPSE_SHARD_SIZE > 2^26`
  (~67M slots), silently writing records to wrong offsets / corrupting the file. The file
  *size* calc already casts to `long`; the per-record `base` does not.
- **Why:** `SYNAPSE_SHARD_SIZE` is operator-controlled config (trusted input), so this is
  not an attacker-reachable vuln — but it's a latent correctness landmine that fails
  silently rather than fast. Surfaced by Phase 4 `/cso` (H2, conf 8/10).
- **Pros:** Either a `MemoryConfig` validation (`shardSize * RECORD_SIZE <= Integer.MAX_VALUE`,
  fail-fast at startup) or computing `base` as `long` removes the landmine for one line.
- **Cons:** Default `SHARD_SIZE` (1M → 32MB offset, well under 2^31) is safe today, so this
  only matters for unusually large shard configs; low priority.
- **Context:** Pre-existing since Phase 2 (`AgentRingFile.java:146,189`). `MemoryConfig`
  already validates `maxAgents * shardSize <= Integer.MAX_VALUE` but not `shardSize *
  RECORD_SIZE`. Prefer the fail-fast `MemoryConfig` guard — consistent with the existing
  validation style and matches "fail fast, not silently."
- **Depends on / blocked by:** none. Self-contained.
