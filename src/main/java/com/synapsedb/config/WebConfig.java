package com.synapsedb.config;

import com.synapsedb.api.auth.AgentAuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link AgentAuthorizationInterceptor} on every agent-scoped route so the
 * authenticated identity is re-checked against Spring's resolved {@code {id}} path variable
 * (Phase 4 CSO H1 defense-in-depth).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AgentAuthorizationInterceptor agentAuthorization;

    public WebConfig(AgentAuthorizationInterceptor agentAuthorization) {
        this.agentAuthorization = agentAuthorization;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(agentAuthorization).addPathPatterns("/api/v1/agents/**");
    }
}
