package com.synapsedb.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Directly exercises the H1 defense-in-depth guard. In the live flow {@link ApiKeyFilter}
 * already guarantees the authenticated agent matches the URI, so the mismatch branch is only
 * reachable if the filter's URI parse ever diverged from Spring's path mapping — exactly the
 * scenario this interceptor exists to catch.
 */
class AgentAuthorizationInterceptorTest {

    private final AgentAuthorizationInterceptor interceptor = new AgentAuthorizationInterceptor();

    private MockHttpServletRequest requestWithPathId(String id) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", id));
        return req;
    }

    @Test
    @DisplayName("authenticated agent matches resolved path id → allowed")
    void matchAllowed() throws Exception {
        MockHttpServletRequest req = requestWithPathId("5");
        req.setAttribute(AgentContext.ATTRIBUTE, new AgentContext(5, "a"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(req, resp, new Object()));
        assertEquals(200, resp.getStatus());
    }

    @Test
    @DisplayName("authenticated agent differs from resolved path id → 403 (the divergence catch)")
    void mismatchForbidden() throws Exception {
        MockHttpServletRequest req = requestWithPathId("6"); // Spring mapped agent 6
        req.setAttribute(AgentContext.ATTRIBUTE, new AgentContext(5, "a")); // key authorized agent 5
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(403, resp.getStatus());
    }

    @Test
    @DisplayName("agent-scoped route with no AgentContext → 401 (fail closed)")
    void missingContextUnauthorized() throws Exception {
        MockHttpServletRequest req = requestWithPathId("5");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("route without an {id} path variable → passes through")
    void noPathIdPassthrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(); // no URI_TEMPLATE_VARIABLES
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(req, resp, new Object()));
    }
}
