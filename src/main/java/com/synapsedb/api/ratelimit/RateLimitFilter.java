package com.synapsedb.api.ratelimit;

import com.synapsedb.api.auth.AgentContext;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Token-bucket rate limiting (Bucket4j) applied at the servlet filter level.
 *
 * <p>Two strategies:
 * <ul>
 *   <li><b>Per-agent</b>: {@code /api/v1/agents/{id}/**} routes read the {@link AgentContext}
 *       set by {@link com.synapsedb.api.auth.ApiKeyFilter} (order 1) and apply a per-agent
 *       bucket. Different agents never share a bucket.</li>
 *   <li><b>Per-IP registration</b>: {@code POST /api/v1/agents} is unauthenticated; the
 *       bucket is keyed by {@code request.getRemoteAddr()} to limit shard-allocation abuse.
 *       Note: behind a proxy {@code remoteAddr} is the proxy IP — see {@code T-TRUSTED-PROXY}.</li>
 * </ul>
 *
 * <p>Buckets are in-memory ({@link ConcurrentHashMap}); they reset on restart.
 * Distributed rate limiting is deferred to {@code T-RATELIMIT-DISTRIBUTED}.
 *
 * <p>On rejection: HTTP 429 with a {@code Retry-After} header (seconds until next refill)
 * and a JSON body matching the {@link com.synapsedb.api.error.ApiError} schema.
 *
 * <p>Metrics: {@code synapse.ratelimit.rejections} counter tagged {@code type=agent|registration}.
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties props;
    private final Counter agentRejectionCounter;
    private final Counter registrationRejectionCounter;

    private final ConcurrentHashMap<Integer, Bucket> agentBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket>  ipBuckets    = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties props, MeterRegistry meterRegistry) {
        this.props = props;
        this.agentRejectionCounter = Counter.builder("synapse.ratelimit.rejections")
                .description("Rate-limit rejections (429 responses emitted)")
                .tag("type", "agent")
                .register(meterRegistry);
        this.registrationRejectionCounter = Counter.builder("synapse.ratelimit.rejections")
                .description("Rate-limit rejections (429 responses emitted)")
                .tag("type", "registration")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathWithinApp(request);
        // Swagger, API-docs, and actuator are exempt from rate limiting.
        return path.startsWith("/swagger-ui")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path   = pathWithinApp(request);
        String method = request.getMethod();

        // ── Registration route: per-IP bucket ──────────────────────────────────
        if ("POST".equalsIgnoreCase(method) && "/api/v1/agents".equals(path)) {
            String ip = request.getRemoteAddr();
            Bucket bucket = ipBuckets.computeIfAbsent(ip, k ->
                    buildBucket(props.registrationCapacity, props.registrationPeriodSeconds));
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                reject(response, probe, registrationRejectionCounter, request);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // ── Authenticated agent routes: per-agent bucket ───────────────────────
        // AgentContext is set by ApiKeyFilter (order 1) for all authenticated requests.
        // If absent (e.g. filter chain short-circuited the auth filter), pass through.
        AgentContext ctx = (AgentContext) request.getAttribute(AgentContext.ATTRIBUTE);
        if (ctx != null) {
            Bucket bucket = agentBuckets.computeIfAbsent(ctx.agentId(), k ->
                    buildBucket(props.agentCapacity, props.agentPeriodSeconds));
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                reject(response, probe, agentRejectionCounter, request);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private Bucket buildBucket(int capacity, int periodSeconds) {
        Bandwidth limit = Bandwidth.classic(capacity,
                Refill.intervally(capacity, Duration.ofSeconds(periodSeconds)));
        return Bucket.builder().addLimit(limit).build();
    }

    private void reject(HttpServletResponse response, ConsumptionProbe probe,
                        Counter counter, HttpServletRequest request) throws IOException {
        counter.increment();
        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
        int status = HttpStatus.TOO_MANY_REQUESTS.value();
        String path = pathWithinApp(request);
        long timestamp = System.currentTimeMillis();
        response.setStatus(status);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(
                "{\"status\":" + status + ",\"error\":\"Too Many Requests\","
                        + "\"message\":\"Rate limit exceeded. Retry after " + retryAfterSeconds + " seconds.\","
                        + "\"path\":\"" + path + "\","
                        + "\"timestamp\":" + timestamp + "}");
    }

    private static String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        return (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx))
                ? uri.substring(ctx.length()) : uri;
    }
}
