package com.ahmedali.fulfillops.fulfillment.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Native Spring Security OAuth2 Resource Server config (not the deprecated Keycloak adapter). The
 * warehouse workflow under /api/v1/fulfillments/** is OPERATOR/ADMIN-only — no CUSTOMER-facing
 * endpoint exists. Health and info stay public so container/orchestrator health checks never need a
 * token.
 */
@Configuration
public class SecurityConfig {

  private static final String REQUIRED_AUDIENCE = "fulfillops-api";

  // The ops console (apps/ops-console, Phase 10) is a browser SPA calling this API from its own
  // origin — without this, the browser's own CORS preflight blocks every request before it ever
  // reaches Spring Security, regardless of how the bearer token or role check would have gone.
  // Not a secret, so — unlike DB/Kafka/Redis/OIDC settings — a sensible default lives directly in
  // application.yml rather than requiring every environment to set it.
  @Value("${app.cors.allowed-origin}")
  private String allowedOrigin;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers("/api/v1/fulfillments/**")
                    .hasAnyRole("OPERATOR", "ADMIN")
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        // Bearer-token APIs are stateless and have no session cookie for a cross-site request to
        // forge, so CSRF protection (which exists to protect session-cookie auth) is not just
        // unneeded but actively wrong here — left enabled, it 403s legitimate POST/PATCH requests
        // from every properly-authenticated client, not just attackers.
        .csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  private CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of(allowedOrigin));
    configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  // Keycloak puts realm roles under the nested realm_access.roles claim, which
  // Spring Security has no built-in mapping for, so this has to be done by hand.
  @Bean
  public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::realmRolesAsAuthorities);
    return converter;
  }

  // Public (not private) so the JWT authorization test can drive this exact
  // mapping through a real MockMvc request instead of re-asserting a hardcoded
  // authority list.
  public static Collection<GrantedAuthority> realmRolesAsAuthorities(Jwt jwt) {
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
      return List.of();
    }
    return roles.stream()
        .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
        .toList();
  }

  // Only active when an issuer is actually configured (the "local" and
  // "production-like" profiles). The "test" profile leaves this property unset
  // on purpose and supplies its own network-free JwtDecoder instead, so tests
  // never make a real HTTP call to discover OIDC metadata at context startup.
  @Bean
  @ConditionalOnProperty(name = "spring.security.oauth2.resourceserver.jwt.issuer-uri")
  public JwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
    OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
    OAuth2TokenValidator<Jwt> audienceValidator =
        new JwtClaimValidator<Collection<String>>(
            JwtClaimNames.AUD,
            audiences -> audiences != null && audiences.contains(REQUIRED_AUDIENCE));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(defaultValidator, audienceValidator));
    return decoder;
  }
}
