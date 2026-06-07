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
