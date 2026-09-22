package com.courtpulse.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.OAuthScope;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.servlet.DispatcherType;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CourtPulseAuthenticationProperties.class)
@SecurityScheme(
        name = "oidcBearer",
        type = SecuritySchemeType.OAUTH2,
        flows = @OAuthFlows(authorizationCode = @OAuthFlow(
                authorizationUrl = "https://identity.example/authorize",
                tokenUrl = "https://identity.example/token",
                scopes = {
                    @OAuthScope(name = "openid", description = "Authenticate the user"),
                    @OAuthScope(name = "courtpulse:ops", description = "Read operational state")
                })))
public class SecurityConfiguration {
    @Bean
    SecurityProblemWriter securityProblemWriter(
            @Qualifier("queryObjectMapper") ObjectMapper objectMapper) {
        return new SecurityProblemWriter(objectMapper);
    }

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            CourtPulseAuthenticationProperties properties,
            SecurityProblemWriter problems) throws Exception {
        if (properties.isEnabled()) {
            validateEnabledConfiguration(properties);
        }
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(properties.getAuthoritiesClaim());
        authorities.setAuthorityPrefix(properties.getAuthorityPrefix());
        JwtAuthenticationConverter authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(authorities);
        DefaultBearerTokenResolver bearerTokens = new DefaultBearerTokenResolver();
        bearerTokens.setAllowFormEncodedBodyParameter(false);
        bearerTokens.setAllowUriQueryParameter(false);

        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/operations/**")
                            .hasAuthority(properties.getOperationsAuthority())
                        .requestMatchers("/api/v1/me", "/api/v1/me/**").authenticated()
                        .requestMatchers("/api/v1/auth/config").permitAll()
                        .requestMatchers("/api/v1/games", "/api/v1/games/**", "/ws/v1/games").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**")
                            .hasAuthority(properties.getOperationsAuthority())
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(bearerTokens)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.unauthorized(request, response)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.unauthorized(request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.forbidden(request, response)))
                .headers(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "courtpulse.auth.enabled", havingValue = "false", matchIfMissing = true)
    JwtDecoder disabledJwtDecoder() {
        return token -> { throw new BadJwtException("OIDC authentication is not configured"); };
    }

    @Bean
    @ConditionalOnProperty(name = "courtpulse.auth.enabled", havingValue = "true")
    JwtDecoder oidcJwtDecoder(CourtPulseAuthenticationProperties properties) {
        validateEnabledConfiguration(properties);
        String issuer = properties.getIssuerUri();
        String audience = properties.getAudience();
        NimbusJwtDecoder decoder;
        if (properties.getJwkSetUri() == null || properties.getJwkSetUri().isBlank()) {
            decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
        } else {
            decoder = NimbusJwtDecoder.withJwkSetUri(
                    requireHttpUri("jwk-set-uri", properties.getJwkSetUri())).build();
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(audience),
                new SubjectValidator()));
        return decoder;
    }

    static void validateEnabledConfiguration(CourtPulseAuthenticationProperties properties) {
        requireExternalIssuer(properties.getIssuerUri());
        requireText("audience", properties.getAudience());
        requireText("client-id", properties.getClientId());
        String scope = requireText("browser-scope", properties.getBrowserScope());
        if (java.util.Arrays.stream(scope.trim().split("\\s+")).noneMatch("openid"::equals)) {
            throw new IllegalStateException(
                    "courtpulse.auth.browser-scope must contain openid when authentication is enabled");
        }
        requireText("authorities-claim", properties.getAuthoritiesClaim());
        requireText("authority-prefix", properties.getAuthorityPrefix());
        requireText("operations-authority", properties.getOperationsAuthority());
        if (properties.getJwkSetUri() != null && !properties.getJwkSetUri().isBlank()) {
            requireHttpUri("jwk-set-uri", properties.getJwkSetUri());
        }
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("courtpulse.auth." + name + " is required when authentication is enabled");
        }
        return value;
    }

    private static String requireHttpUri(String name, String value) {
        String required = requireText(name, value);
        URI uri;
        try {
            uri = URI.create(required);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("courtpulse.auth." + name + " must be an absolute HTTP URI");
        }
        if (!uri.isAbsolute()
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException("courtpulse.auth." + name + " must be an absolute HTTP URI");
        }
        return required;
    }

    private static String requireExternalIssuer(String value) {
        String required = requireHttpUri("issuer-uri", value);
        URI uri = URI.create(required);
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback)) {
            throw new IllegalStateException(
                    "courtpulse.auth.issuer-uri must use HTTPS except for explicit loopback development");
        }
        return required;
    }
}
