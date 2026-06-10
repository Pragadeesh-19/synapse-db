package com.synapsedb.core;

/**
 * Immutable engine configuration with fail-fast validation (eng-review C1).
 *
 * <p>Every constant that feeds the ring-buffer bitmask or the per-agent array
 * sizing is validated at construction. A bad value here (e.g. a non-power-of-2
 * {@code shardSize}) would otherwise corrupt every index <em>silently</em> via
 * {@code & shardMask} — the worst class of bug to diagnose. We refuse to start
 * instead.
 *
 * <pre>
 *   env / defaults ──▶ fromEnv() ──▶ compact ctor validation ──▶ immutable config
 *                                         │
 *                                         ├─ shardSize power-of-2 & &gt; 0
 *                                         ├─ maxAgents &ge; 1
 *                                         ├─ (long)maxAgents*shardSize ≤ Integer.MAX_VALUE
 *                                         └─ rates/salience finite & in range
 * </pre>
 */
public record MemoryConfig(
        int maxAgents,
        int shardSize,
        float lambda,
        long decayUnitMs,
        float learningRate,
        float rootBaseSalience,
        String dataDir,
        String configDir,
        float emotionalThreshold) {

    /**
     * Maximum safe shard size for int record-offset arithmetic in AgentRingFile.
     * HEADER_SIZE(64) + MAX_SHARD_SIZE * RECORD_SIZE(32) = 64 + 1073741824 < Integer.MAX_VALUE.
     * Beyond this, {@code int base = HEADER_SIZE + slot * RECORD_SIZE} overflows silently.
     */
    public static final int MAX_SHARD_SIZE = 1 << 25; // 33,554,432 slots

    /** Compact constructor: fail-fast validation. Runs for every construction path. */
    public MemoryConfig {
        if (shardSize <= 0 || (shardSize & (shardSize - 1)) != 0) {
            throw new IllegalArgumentException(
                    "SHARD_SIZE must be a positive power of 2 (the ring buffer uses '& mask', "
                            + "not '%'); got " + shardSize);
        }
        if (shardSize > MAX_SHARD_SIZE) {
            throw new IllegalArgumentException(
                    "SHARD_SIZE " + shardSize + " exceeds MAX_SHARD_SIZE " + MAX_SHARD_SIZE
                            + " (record-offset int arithmetic overflows beyond this; "
                            + "use SYNAPSE_SHARD_SIZE ≤ " + MAX_SHARD_SIZE + ")");
        }
        if (maxAgents < 1) {
            throw new IllegalArgumentException("MAX_AGENTS must be >= 1; got " + maxAgents);
        }
        // Even with per-agent slices we keep slot ids in 'int'. Guard the ceiling so a
        // future bump of either knob can't silently overflow an int index.
        long maxAddressable = (long) maxAgents * (long) shardSize;
        if (maxAddressable > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "MAX_AGENTS * SHARD_SIZE (" + maxAddressable + ") exceeds Integer.MAX_VALUE; "
                            + "reduce SYNAPSE_MAX_AGENTS or SYNAPSE_SHARD_SIZE");
        }
        if (shardSize < 2) {
            throw new IllegalArgumentException(
                    "SHARD_SIZE must be >= 2 (slot 0 is the reserved root; need at least one "
                            + "writable slot); got " + shardSize);
        }
        if (!Float.isFinite(lambda) || lambda < 0f) {
            throw new IllegalArgumentException("LAMBDA must be finite and >= 0; got " + lambda);
        }
        if (decayUnitMs <= 0) {
            throw new IllegalArgumentException("DECAY_UNIT_MS must be > 0; got " + decayUnitMs);
        }
        if (!Float.isFinite(learningRate)) {
            throw new IllegalArgumentException("LEARNING_RATE must be finite; got " + learningRate);
        }
        if (!Float.isFinite(rootBaseSalience) || rootBaseSalience < 0f || rootBaseSalience > 1f) {
            throw new IllegalArgumentException(
                    "ROOT_BASE_SALIENCE must be finite and within [0,1]; got " + rootBaseSalience);
        }
    }

    /** Bitmask for ring index math: {@code slot = writeHead & shardMask()}. */
    public int shardMask() {
        return shardSize - 1;
    }

    /** Defaults matching the CLAUDE.md spec (no env overrides applied). */
    public static MemoryConfig defaults() {
        return new MemoryConfig(
                64,
                1 << 20,
                0.1f,
                3_600_000L,
                0.1f,
                0.5f,
                "./data",
                "./config",
                0.7f);
    }

    /** Build from {@code SYNAPSE_*} environment variables, falling back to {@link #defaults()}. */
    public static MemoryConfig fromEnv() {
        MemoryConfig d = defaults();
        return new MemoryConfig(
                intEnv("SYNAPSE_MAX_AGENTS", d.maxAgents),
                intEnv("SYNAPSE_SHARD_SIZE", d.shardSize),
                floatEnv("SYNAPSE_LAMBDA", d.lambda),
                longEnv("SYNAPSE_DECAY_UNIT_MS", d.decayUnitMs),
                floatEnv("SYNAPSE_LEARNING_RATE", d.learningRate),
                floatEnv("SYNAPSE_ROOT_BASE_SALIENCE", d.rootBaseSalience),
                strEnv("SYNAPSE_DATA_DIR", d.dataDir),
                strEnv("SYNAPSE_CONFIG_DIR", d.configDir),
                floatEnv("SYNAPSE_EMOTIONAL_THRESHOLD", d.emotionalThreshold));
    }

    private static String strEnv(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    private static int intEnv(String key, int fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a valid int: '" + v + "'", e);
        }
    }

    private static long longEnv(String key, long fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a valid long: '" + v + "'", e);
        }
    }

    private static float floatEnv(String key, float fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a valid float: '" + v + "'", e);
        }
    }
}
