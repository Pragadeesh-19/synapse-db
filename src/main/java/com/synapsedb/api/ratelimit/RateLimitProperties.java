package com.synapsedb.api.ratelimit;

import org.springframework.stereotype.Component;

/**
 * Rate-limiting configuration read from {@code SYNAPSE_RATELIMIT_*} environment variables.
 *
 * <p>Two strategies are configured:
 * <ul>
 *   <li><b>Per-agent</b>: applied to all authenticated {@code /api/v1/agents/{id}/**} routes.
 *       Default: 60 tokens refilled every 60 seconds.</li>
 *   <li><b>Per-IP registration</b>: applied to {@code POST /api/v1/agents} (unauthenticated).
 *       Default: 5 tokens refilled every 60 seconds (limits shard-allocation abuse).</li>
 * </ul>
 *
 * <p>Buckets are in-memory ({@link java.util.concurrent.ConcurrentHashMap}); they reset on
 * restart. Distributed rate limiting is deferred ({@code T-RATELIMIT-DISTRIBUTED}).
 */
@Component
public class RateLimitProperties {

    /** Maximum requests per agent within {@link #agentPeriodSeconds}. */
    public final int agentCapacity;

    /** Refill period in seconds for per-agent buckets. */
    public final int agentPeriodSeconds;

    /** Maximum registration requests per IP within {@link #registrationPeriodSeconds}. */
    public final int registrationCapacity;

    /** Refill period in seconds for per-IP registration buckets. */
    public final int registrationPeriodSeconds;

    public RateLimitProperties() {
        this.agentCapacity            = intEnv("SYNAPSE_RATELIMIT_AGENT_CAPACITY",  60);
        this.agentPeriodSeconds       = intEnv("SYNAPSE_RATELIMIT_AGENT_PERIOD_SEC", 60);
        this.registrationCapacity     = intEnv("SYNAPSE_RATELIMIT_REG_CAPACITY",      5);
        this.registrationPeriodSeconds = intEnv("SYNAPSE_RATELIMIT_REG_PERIOD_SEC",  60);
    }

    /** Constructor for test overrides — accepts explicit values instead of reading env vars. */
    public RateLimitProperties(int agentCapacity, int agentPeriodSeconds,
                               int registrationCapacity, int registrationPeriodSeconds) {
        this.agentCapacity = agentCapacity;
        this.agentPeriodSeconds = agentPeriodSeconds;
        this.registrationCapacity = registrationCapacity;
        this.registrationPeriodSeconds = registrationPeriodSeconds;
    }

    private static int intEnv(String key, int fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(v.trim());
            if (parsed <= 0) throw new IllegalArgumentException(key + " must be > 0; got " + parsed);
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a valid int: '" + v + "'", e);
        }
    }
}
