package com.iitm.beacon.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

@ExtendWith(MockitoExtension.class)
class SessionAuthenticatorTest {

    @Mock
    private SecurityContextRepository securityContextRepository;

    @Mock
    private SessionAuthenticationStrategy sessionAuthenticationStrategy;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private SessionAuthenticator authenticator;

    @BeforeEach
    void createAuthenticator() {
        authenticator = new SessionAuthenticator(securityContextRepository, sessionAuthenticationStrategy);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void establishesAuthenticationWithPrincipalAndRolePrefixedAuthority() {
        authenticator.login(request, response, "admin@example.com", "ADMIN");

        ArgumentCaptor<SecurityContext> captor = ArgumentCaptor.forClass(SecurityContext.class);
        verify(securityContextRepository).saveContext(captor.capture(), eq(request), eq(response));

        SecurityContext savedContext = captor.getValue();
        Authentication authentication = savedContext.getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo("admin@example.com");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void setsTheSecurityContextHolderContextToo() {
        authenticator.login(request, response, "visitor@example.com", "VISITOR");

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo("visitor@example.com");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_VISITOR");
    }

    @Test
    void appliesTheSessionStrategyToTheNewAuthentication_beforeTheContextIsSaved() {
        authenticator.login(request, response, "visitor@example.com", "VISITOR");

        ArgumentCaptor<Authentication> applied = ArgumentCaptor.forClass(Authentication.class);
        ArgumentCaptor<SecurityContext> saved = ArgumentCaptor.forClass(SecurityContext.class);
        InOrder order = inOrder(sessionAuthenticationStrategy, securityContextRepository);
        order.verify(sessionAuthenticationStrategy).onAuthentication(applied.capture(), eq(request), eq(response));
        order.verify(securityContextRepository).saveContext(saved.capture(), eq(request), eq(response));
        assertThat(applied.getValue()).isSameAs(saved.getValue().getAuthentication());
    }

    @Test
    void sessionStrategyFails_nothingIsSavedAndNoOneIsLoggedIn() {
        doThrow(new SessionAuthenticationException("cannot rotate"))
                .when(sessionAuthenticationStrategy)
                .onAuthentication(any(), eq(request), eq(response));

        assertThatThrownBy(() -> authenticator.login(request, response, "admin@example.com", "ADMIN"))
                .isInstanceOf(SessionAuthenticationException.class);

        verifyNoInteractions(securityContextRepository);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
