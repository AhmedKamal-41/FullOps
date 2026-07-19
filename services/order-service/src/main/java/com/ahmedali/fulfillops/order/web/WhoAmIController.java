package com.ahmedali.fulfillops.order.web;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Secured probe: proves a caller's token was validated and its roles resolved. */
@RestController
public class WhoAmIController {

  @GetMapping("/api/v1/whoami")
  public WhoAmIResponse whoAmI(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
    List<String> roles =
        authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    return new WhoAmIResponse(jwt.getSubject(), jwt.getClaimAsString("preferred_username"), roles);
  }

  private record WhoAmIResponse(String subject, String username, List<String> roles) {}
}
