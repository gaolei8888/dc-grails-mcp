package grails.plugin.mcp.security

import groovy.transform.CompileDynamic
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * Optional OAuth2 Resource Server security for /mcp/** endpoints.
 *
 * Activates ONLY when the host app sets:
 *     grails.mcp.security.enabled: true
 *
 * When active, requires a valid Bearer JWT on all /mcp/** requests.
 * JWT is validated against the issuer configured at:
 *     spring.security.oauth2.resourceserver.jwt.issuer-uri
 *
 * When inactive (default), this class is not loaded — /mcp has no auth.
 *
 * The @Order(1) with securityMatcher("/mcp/**") ensures this filter chain
 * handles only MCP requests, leaving the host app's existing security
 * (e.g. grails-spring-security-core) untouched for other paths.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(
    prefix = 'grails.mcp.security',
    name = 'enabled',
    havingValue = 'true'
)
@CompileDynamic
class McpSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher('/mcp/**')
            .authorizeHttpRequests { auth -> auth.anyRequest().authenticated() }
            .oauth2ResourceServer { oauth2 -> oauth2.jwt {} }
            .sessionManagement { s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { csrf -> csrf.disable() }

        return http.build()
    }
}
