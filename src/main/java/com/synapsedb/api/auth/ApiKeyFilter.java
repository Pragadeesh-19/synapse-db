package com.synapsedb.api.auth;

import com.synapsedb.config.ApiKeyConfigLoader;
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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates {@code /api/v1/agents/{id}/**} requests by SHA-256-hashing the
 * {@code X-Api-Key} header, looking up the owning agent, and verifying it matches the
 * {@code {id}} path variable.
 *
 * <pre>
 *  request
 *    │
 *    ▼  shouldNotFilter()?  ── yes ──▶  pass through (registration, swagger, api-docs)
 *    │ no
 *    ▼
 *  X-Api-Key present? ── no ──▶ 401
 *    │ yes
 *    ▼
 *  hash → AgentKeyRecord? ── no ──▶ 401
 *    │ yes
 *    ▼
 *  key.agentId == {id}? ── no ──▶ 403
 *    │ yes
 *    ▼
 *  set AgentContext, continue chain
 * </pre>
 *
 * The allowlist is exact: registration (no key exists yet) and swagger/api-docs are the
 * only open paths. Everything else under {@code /api/v1/agents/} requires a valid key
 * whose agent matches the URL.
 */
@Component
@Order(1)
public final class ApiKeyFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Api-Key";

    private static final Pattern AGENT_PATH = Pattern.compile("^/api/v1/agents/(\\d+)(?:/.*)?$");

    private final ApiKeyConfigLoader keys;

    public ApiKeyFilter(ApiKeyConfigLoader keys) {
        this.keys = keys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathWithinApp(request);
        // Registration mints the first key — it cannot require one yet.
        if ("POST".equalsIgnoreCase(request.getMethod()) && path.equals("/api/v1/agents")) {
            return true;
        }
        return path.startsWith("/swagger-ui")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String rawKey = request.getHeader(API_KEY_HEADER);
        Optional<AgentKeyRecord> found = keys.lookupByRawKey(rawKey);
        if (found.isEmpty()) {
            reject(response, HttpStatus.UNAUTHORIZED,
                    "Missing or invalid " + API_KEY_HEADER + " header");
            return;
        }
        AgentKeyRecord record = found.get();

        Integer pathAgentId = parseAgentId(pathWithinApp(request));
        if (pathAgentId == null) {
            reject(response, HttpStatus.UNAUTHORIZED, "Request is not scoped to an agent");
            return;
        }
        if (record.agentId() != pathAgentId) {
            reject(response, HttpStatus.FORBIDDEN,
                    "API key does not authorize agent " + pathAgentId);
            return;
        }

        request.setAttribute(AgentContext.ATTRIBUTE, new AgentContext(record.agentId(), record.label()));
        chain.doFilter(request, response);
    }

    private static String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        return (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) ? uri.substring(ctx.length()) : uri;
    }

    private static Integer parseAgentId(String path) {
        Matcher m = AGENT_PATH.matcher(path);
        if (!m.matches()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null; // overflow on an absurdly long id string
        }
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String body = "{\"status\":" + status.value()
                + ",\"error\":\"" + status.getReasonPhrase() + "\""
                + ",\"message\":\"" + escape(message) + "\"}";
        response.getWriter().write(body);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
