package com.iitm.beacon.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

@ExtendWith(MockitoExtension.class)
class SessionAuthenticatorTest {

    @Mock
    private SecurityContextRepository securityContextRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void establishesAuthenticationWithPrincipalAndRolePrefixedAuthority() {
        SessionAuthenticator authenticator = new SessionAuthenticator(securityContextRepository);

        authenticator.login(request, response, "admin@example.com", "ADMIN");

        ArgumentCaptor<SecurityContext> captor = ArgumentCaptor.forClass(SecurityContext.class);
        verify(securityContextRepository).saveContext(captor.capture(), org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.eq(response));

        SecurityContext savedContext = captor.getValue();
        Authentication authentication = savedContext.getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo("admin@example.com");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void setsTheSecurityContextHolderContextToo() {
        SessionAuthenticator authenticator = new SessionAuthenticator(securityContextRepository);

        authenticator.login(request, response, "visitor@example.com", "VISITOR");

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo("visitor@example.com");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_VISITOR");
    }
}
