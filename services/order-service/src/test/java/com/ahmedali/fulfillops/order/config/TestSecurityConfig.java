package com.ahmedali.fulfillops.order.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The "test" profile deliberately configures no OIDC issuer (see application-test.yml) so context
 * startup never makes a real network call. This fills the JwtDecoder bean that the security filter
 * chain still needs to wire up — its key is never actually used, because tests authenticate
 * requests directly via Spring Security Test's jwt() request post-processor, which bypasses
 * decoding entirely.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

  @Bean
  public JwtDecoder jwtDecoder() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    return NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
  }
}
