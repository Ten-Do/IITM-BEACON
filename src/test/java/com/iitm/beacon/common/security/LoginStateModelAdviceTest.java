package com.iitm.beacon.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestWrapper;

/**
 * {@link LoginStateModelAdvice#loggedIn}: whether the page is rendered for a
 * browser that is logged in — as a visitor or as an admin — which is what
 * the visitor header needs to show its "Log out" button.
 */
class LoginStateModelAdviceTest {

    private final LoginStateModelAdvice advice = new LoginStateModelAdvice();

    @Test
    void noUser_isNotLoggedIn() {
        assertThat(advice.loggedIn(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void visitor_isLoggedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(new UsernamePasswordAuthenticationToken(
                "visitor@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR"))));

        assertThat(advice.loggedIn(request)).isTrue();
    }

    @Test
    void admin_isLoggedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThat(advice.loggedIn(request)).isTrue();
    }

    /** Spring Security's anonymous user is nobody: its request reports no principal. */
    @Test
    void anonymousUser_isNotLoggedIn() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken(
                        "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        try {
            SecurityContextHolderAwareRequestWrapper request =
                    new SecurityContextHolderAwareRequestWrapper(new MockHttpServletRequest(), "ROLE_");

            assertThat(advice.loggedIn(request)).isFalse();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
