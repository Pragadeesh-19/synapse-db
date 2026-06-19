package com.synapsedb.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;

/**
 * Defense-in-depth agentId check. {@link ApiKeyFilter} authenticates by parsing the agent id
 * from the raw URI with a regex. This interceptor re-checks the authenticated {@link AgentContext}
 * against the {@code {id}} path variable Spring actually resolved, collapsing authorization to one
 * source of truth: if the filter's URI parse ever diverges from Spring's mapping (proxy rewrite,
 * path-normalization change), the request is rejected here before reaching another agent's shard.
 */
@Component
public final class AgentAuthorizationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Object varsObj = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(varsObj instanceof Map<?, ?> vars)) {
            return true; // no path variables — not an agent-scoped route (e.g. registration)
        }
        Object idValue = vars.get("id");
        if (idValue == null) {
            return true; // route has no {id} — nothing to authorize against
        }
        int pathAgentId;
        try {
            pathAgentId = Integer.parseInt(idValue.toString());
        } catch (NumberFormatException e) {
            return true; // malformed id — let the controller produce a 400/404
        }

        AgentContext ctx = AgentContext.from(request);
        if (ctx == null) {
            // An agent-scoped route reached here without authentication — fail closed.
            reject(response, HttpStatus.UNAUTHORIZED, "Not authenticated");
            return false;
        }
        if (ctx.agentId() != pathAgentId) {
            reject(response, HttpStatus.FORBIDDEN, "API key does not authorize agent " + pathAgentId);
            return false;
        }
        return true;
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String body = "{\"status\":" + status.value()
                + ",\"error\":\"" + status.getReasonPhrase() + "\""
                + ",\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        response.getWriter().write(body);
    }
}
