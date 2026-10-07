package com.iitm.beacon.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Gives every page's model {@code loggedIn}: whether the browser is logged
 * in, as a visitor or as an admin. The visitor header ({@code
 * templates/layout/header-visitor.html}) shows its "Log out" button only
 * then; the admin header always has one, since every page using it needs
 * the admin login. Logging out works the same for either role, so the
 * button doesn't care which.
 *
 * <p>Spring Security's anonymous user reports no principal, so it counts as
 * not logged in. A page rendered outside Spring MVC — the site's error page
 * ({@code common.error.ErrorPageRenderer}) — never gets the attribute, and
 * so never shows the button.
 */
@ControllerAdvice
public class LoginStateModelAdvice {

    @ModelAttribute("loggedIn")
    public boolean loggedIn(HttpServletRequest request) {
        return request.getUserPrincipal() != null;
    }
}
