package com.ahmedali.fulfillops.inventory.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

/**
 * Native Spring Security OAuth2 Resource Server config (not the deprecated Keycloak adapter).
 * Product/adjustment commands are ADMIN-only; availability and low-stock reads are open to OPERATOR
 * and ADMIN alike.
 */
@Configuration
public class SecurityConfig {

  private static final String REQUIRED_AUDIENCE = "fulfillops-api";

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/products")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/v1/products/*")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/inventory/*/adjustments")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/v1/inventory/**")
                    .hasAnyRole("OPERATOR", "ADMIN")
                    .anyRequest()
                    .authenticated())
        // Bearer-token APIs are stateless and have no session cookie for a
        // cross-site request to forge, so CSRF protection (which exists to
        // protect session-cookie auth) is not just unneeded but actively wrong
        // here — left enabled, it 403s legitimate POST/PUT/DELETE requests from
        // every properly-authenticated client, not just attackers.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
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
