package com.iitm.beacon.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Establishes a session-backed authenticated principal after a successful
 * OTP verification, shared by both {@code adminauth.AdminAuthController} and
 * {@code submission.VisitorAuthController} (only a {@code common} class and a
 * framework interface — never one slice importing another, decision 9).
 *
 * <p>The logins are done by hand, outside Spring Security's own login
 * filters, so the {@link SessionAuthenticationStrategy} those filters would
 * apply is applied here, before the new principal is stored: {@code
 * config.SecurityConfig}'s strategy moves the session to a new id (session
 * fixation, BL-034), keeping its attributes — the page saved for {@link
 * PostLoginRedirect} survives. If the strategy fails, no one is logged in.
 */
@Component
public class SessionAuthenticator {

    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public SessionAuthenticator(
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    public void login(HttpServletRequest request, HttpServletResponse response, String principal, String role) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
