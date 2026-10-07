package com.iitm.beacon.config;

import com.iitm.beacon.common.error.ErrorResponseWriter;
import com.iitm.beacon.common.error.PageAccessDeniedHandler;
import com.iitm.beacon.common.error.PageNotFoundHandler;
import com.iitm.beacon.common.error.RestAccessDeniedHandler;
import com.iitm.beacon.common.error.RestAuthenticationEntryPoint;
import com.iitm.beacon.common.web.ApiRequests;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.DelegatingAccessDeniedHandler;
import org.springframework.security.web.access.RequestMatcherDelegatingAccessDeniedHandler;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.FlashMapManager;

/**
 * App-wide Spring Security configuration, even though it configures security
 * for every slice (docs/architecture.md §3).
 *
 * <p>A request without a (live) session to a page that needs a login is
 * redirected to its role's login page: {@link #ADMIN_PAGES} to {@code
 * /admin/login}, {@link #VISITOR_PAGES} to {@code /submissions/login}. A GET
 * of such a page is first saved in the {@link #requestCache() request
 * cache}, so the login's verify handler can send the user back to it
 * ({@code common.security.PostLoginRedirect}). Every other unauthenticated
 * request to the JSON API keeps {@link RestAuthenticationEntryPoint}'s JSON
 * 401.
 *
 * <p>A logged-in user of the other role is treated the same way on those
 * pages (BL-033): an admin session opening a visitor page, or a visitor
 * session opening an admin page, is redirected to that page's own login
 * page — logging in there replaces the session's role — and a GET of the
 * page is saved for the return trip. Every other request to the JSON API
 * the session's role isn't allowed keeps {@link RestAccessDeniedHandler}'s
 * JSON 403.
 *
 * <p>A request outside {@code /api/**} that reaches the {@code denyAll()}
 * tail — a path no route serves, such as a mistyped link or a browser's
 * icon probe — is answered with the site's HTML 404 page ({@link
 * PageNotFoundHandler}), with a session or without one: nothing is there,
 * so "not found" is the truthful answer, and it neither redirects to a login
 * nor is saved in the request cache. Under {@code /api/**} the tail keeps
 * the JSON 401 and 403.
 *
 * <p>CSRF protection (BL-004), cookie-to-header: every response sets the
 * {@link #csrfTokenRepository() XSRF-TOKEN cookie} when the request didn't
 * carry one, and every state-changing request must send the token back —
 * a REST client or script as the cookie's value in the {@code X-XSRF-TOKEN}
 * header, a server-rendered form as the masked token in its hidden {@code
 * _csrf} field ({@link SpaCsrfTokenRequestHandler}). A request without a
 * valid token gets a 403, whoever sends it — the JSON one under {@code
 * /api/**}, the HTML error page ({@link PageAccessDeniedHandler}) anywhere
 * else — never a login redirect. Every OTP login renews the token along with
 * the session id ({@link #sessionAuthenticationStrategy}).
 *
 * <p>Logging out is {@code POST /logout} with the CSRF token (the headers'
 * "Log out" button): the session is invalidated, the security context
 * cleared, the session cookie expired and the token cookie cleared (Spring's
 * {@code CsrfLogoutHandler}), so the next page gets a new token. An admin
 * lands on the admin login page, anyone else — a visitor, or a browser that
 * wasn't logged in — on the homepage ({@link #afterLogout}). Any other
 * method on {@code /logout} is a 405 ({@link LogoutMethodFilter}).
 *
 * <p>Every response carries a Content-Security-Policy ({@link
 * #SITE_POLICY}; the submission form's {@link #SUBMISSION_FORM_POLICY} adds
 * what Alpine.js and the photo picker need; none for the dev-only H2
 * console) and {@code Referrer-Policy: same-origin}. Before the CSRF check —
 * the first thing that reads a request's body — {@link
 * NonUploadMultipartFilter} refuses a large multipart body anywhere but the
 * submission endpoints.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    /** The admin's HTML pages: the moderation queue and the catalog (decision 28). */
    private static final RequestMatcher ADMIN_PAGES =
            new OrRequestMatcher(PATHS.matcher("/moderation/**"), PATHS.matcher("/catalog/**"));

    /** The public homepage dashboard (decision 30). */
    private static final String[] DASHBOARD_PAGES = {"/"};

    /** The public gallery's pages: the list and one article. */
    private static final String[] GALLERY_PAGES = {"/gallery", "/gallery/*"};

    /** The visitor's HTML pages that need a login. */
    private static final RequestMatcher VISITOR_PAGES =
            new OrRequestMatcher(PATHS.matcher("/submissions/form"), PATHS.matcher("/submissions/confirmation"));

    /** The submission form: the one page running Alpine.js, and the photo picker's previews. */
    private static final RequestMatcher SUBMISSION_FORM = PATHS.matcher("/submissions/form");

    /** The dev-only H2 console: its own pages, inline scripts and all. */
    private static final RequestMatcher H2_CONSOLE = PATHS.matcher("/h2-console/**");

    /**
     * The Content-Security-Policy of every response but the submission form's
     * and the dev-only H2 console's: scripts, styles, fonts, fetches and
     * frames of this site only — no inline script or style, no event handler
     * attribute, no {@code eval} — images of this site or {@code data:} URLs
     * (the pages' empty icon), no plugin, no foreign {@code <base>} or form
     * target, and no framing but by this site's own pages.
     */
    static final String SITE_POLICY = contentSecurityPolicy("'self'", "'self' data:");

    /**
     * The submission form's policy: the site's, plus {@code 'unsafe-eval'} —
     * Alpine.js's standard build evaluates its attribute expressions — and
     * {@code blob:} images, the photo picker's previews of the chosen files.
     */
    static final String SUBMISSION_FORM_POLICY = contentSecurityPolicy("'self' 'unsafe-eval'", "'self' data: blob:");

    /** The servlet container's session cookie name, unless {@code server.servlet.session.cookie.name} sets another. */
    private static final String DEFAULT_SESSION_COOKIE = "JSESSIONID";

    /** Reads a header token or a masked form-field token; one instance, shared with the login's renewal. */
    private static final CsrfTokenRequestHandler CSRF_REQUEST_HANDLER = new SpaCsrfTokenRequestHandler();

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * The CSRF token lives in the {@code XSRF-TOKEN} cookie, not in the
     * session — so it outlives an expired session, and a form posted after
     * the session expired still reaches the login redirect (decision 23).
     * Readable by scripts (not {@code HttpOnly}): a client copies it into the
     * {@code X-XSRF-TOKEN} header. Path {@code /}, {@code SameSite=Lax},
     * like the session cookie — and {@code Secure} whenever the session
     * cookie is ({@code server.servlet.session.cookie.secure}, {@code
     * BEACON_COOKIE_SECURE}), else only on a request that is itself https.
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository(ServerProperties server) {
        boolean alwaysSecure = Boolean.TRUE.equals(server.getServlet().getSession().getCookie().getSecure());
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> {
            cookie.sameSite("Lax");
            if (alwaysSecure) {
                cookie.secure(true);
            }
        });
        return repository;
    }

    /**
     * Applied by {@code common.security.SessionAuthenticator} on every OTP
     * login, which runs outside Spring Security's own login filters (so
     * nothing else applies it), in this order: the session moves to a new id
     * — a session id known before the login is worthless after it (session
     * fixation) — keeping its attributes, such as the page saved in the
     * {@link #requestCache() request cache}; then the CSRF token is replaced
     * (a new {@code XSRF-TOKEN} cookie on the login's own response).
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        CsrfAuthenticationStrategy renewCsrfToken = new CsrfAuthenticationStrategy(csrfTokenRepository);
        renewCsrfToken.setRequestHandler(CSRF_REQUEST_HANDLER);
        return new CompositeSessionAuthenticationStrategy(
                List.of(new ChangeSessionIdAuthenticationStrategy(), renewCsrfToken));
    }

    /**
     * Saves only a GET of a page that redirects to a login page — the one
     * page worth returning to after the login. Never a form POST (it can't
     * be replayed by a redirect), never the JSON API, and never a stray
     * request that merely hits the {@code denyAll()} tail (e.g. a browser
     * asking for an icon between the redirect and the login), which would
     * otherwise overwrite the saved page.
     *
     * <p>No {@code continue} query parameter: the saved URL stays exactly as
     * requested. That parameter only tells Spring's {@code
     * RequestCacheAwareFilter} when to look for a saved request to replay,
     * and there is nothing left to replay: {@code PostLoginRedirect} removes
     * the saved request during the login, before the redirect back.
     */
    @Bean
    public RequestCache requestCache() {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(new AndRequestMatcher(
                PATHS.matcher(HttpMethod.GET, "/**"), new OrRequestMatcher(ADMIN_PAGES, VISITOR_PAGES)));
        requestCache.setMatchingRequestParameterName(null);
        return requestCache;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            RequestCache requestCache,
            RestAuthenticationEntryPoint apiEntryPoint,
            RestAccessDeniedHandler apiDeniedHandler,
            PageAccessDeniedHandler pageDeniedHandler,
            PageNotFoundHandler pageNotFound,
            FlashMapManager flashMapManager,
            NonUploadMultipartProperties multipartLimit,
            ErrorResponseWriter errorResponses,
            ServerProperties server)
            throws Exception {
        // Before the CSRF check, the first thing that reads a body; after the
        // security headers, so a refusal carries them too.
        http.addFilterAfter(
                new NonUploadMultipartFilter(multipartLimit.nonUploadMultipartMaxSize(), errorResponses),
                HeaderWriterFilter.class);
        // After the CSRF check, like any other 405; before the logout itself.
        http.addFilterBefore(new LogoutMethodFilter(errorResponses), LogoutFilter.class);
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(CSRF_REQUEST_HANDLER)
                        // The dev-only H2 console's own pages post without the app's token.
                        .ignoringRequestMatchers(H2_CONSOLE))
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(rc -> rc.requestCache(requestCache))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/auth/otp/**", "/api/submissions/otp/**")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        .requestMatchers("/h2-console/**")
                        .permitAll()
                        .requestMatchers("/uploads/**")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET, "/api/submissions/contact-types", "/api/submissions/achievements")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/submissions")
                        .hasRole("VISITOR")
                        .requestMatchers(HttpMethod.GET, "/api/submissions/mine", "/api/submissions/session")
                        .hasRole("VISITOR")
                        .requestMatchers(HttpMethod.PUT, "/api/submissions/mine")
                        .hasRole("VISITOR")
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/gallery/**")
                        .permitAll()
                        // The homepage dashboard's figures (decision 30) — public, read-only.
                        .requestMatchers(HttpMethod.GET, "/api/analytics/summary")
                        .permitAll()
                        .requestMatchers("/api/moderation/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/api/catalog/**")
                        .hasRole("ADMIN")
                        // The homepage dashboard (decision 30) is public and read-only.
                        .requestMatchers(HttpMethod.GET, DASHBOARD_PAGES)
                        .permitAll()
                        .requestMatchers(HttpMethod.HEAD, DASHBOARD_PAGES)
                        .permitAll()
                        // The public gallery pages — the list and one article — are read-only.
                        .requestMatchers(HttpMethod.GET, GALLERY_PAGES)
                        .permitAll()
                        .requestMatchers(HttpMethod.HEAD, GALLERY_PAGES)
                        .permitAll()
                        // The article's contact reveal, the only POST under /gallery: the CSRF
                        // token like any POST, plus its handler's own same-origin check
                        // (common.web.SameOriginOnly, decision 27).
                        .requestMatchers(HttpMethod.POST, "/gallery/*/contact")
                        .permitAll()
                        .requestMatchers("/admin/login", "/admin/login/**")
                        .permitAll()
                        .requestMatchers("/submissions/login", "/submissions/login/**")
                        .permitAll()
                        .requestMatchers("/submissions/form", "/submissions/confirmation")
                        .hasRole("VISITOR")
                        .requestMatchers("/moderation/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/catalog/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .denyAll())
                .headers(headers -> headers.frameOptions(FrameOptionsConfig::sameOrigin)
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                SUBMISSION_FORM, new ContentSecurityPolicyHeaderWriter(SUBMISSION_FORM_POLICY)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(new OrRequestMatcher(SUBMISSION_FORM, H2_CONSOLE)),
                                new ContentSecurityPolicyHeaderWriter(SITE_POLICY))))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint(apiEntryPoint, pageNotFound))
                        .accessDeniedHandler(accessDeniedHandler(
                                requestCache, apiDeniedHandler, pageDeniedHandler, pageNotFound, flashMapManager)))
                .logout(logout -> logout.logoutRequestMatcher(PATHS.matcher(HttpMethod.POST, "/logout"))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .addLogoutHandler(expireSessionCookie(server))
                        .logoutSuccessHandler(afterLogout()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Where a logout lands, by who logged out — the authentication as it was
     * before the logout cleared it: an admin on the admin login page; anyone
     * else on the homepage, a browser that wasn't logged in too (an expired
     * session behind a stale button). A relative {@code Location}, like
     * every redirect of the app's.
     */
    private static LogoutSuccessHandler afterLogout() {
        RedirectStrategy redirect = new DefaultRedirectStrategy();
        return (request, response, authentication) -> {
            boolean admin = authentication != null && authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
            redirect.sendRedirect(request, response, admin ? "/admin/login" : "/");
        };
    }

    /**
     * Expires the session cookie on the whole site, with the attributes it
     * was set with: {@code HttpOnly}, and {@code Secure} whenever the session
     * cookie is ({@code server.servlet.session.cookie.secure}) or the request
     * is https. The session itself is already invalidated, so the old id is
     * worthless either way; this only stops the browser sending it.
     */
    private static LogoutHandler expireSessionCookie(ServerProperties server) {
        var configured = server.getServlet().getSession().getCookie();
        String name = configured.getName() != null ? configured.getName() : DEFAULT_SESSION_COOKIE;
        boolean alwaysSecure = Boolean.TRUE.equals(configured.getSecure());
        return (request, response, authentication) -> {
            Cookie expired = new Cookie(name, "");
            expired.setPath("/");
            expired.setMaxAge(0);
            expired.setHttpOnly(true);
            expired.setSecure(alwaysSecure || request.isSecure());
            response.addCookie(expired);
        };
    }

    private static String contentSecurityPolicy(String scriptSources, String imageSources) {
        return String.join("; ",
                "default-src 'self'",
                "script-src " + scriptSources,
                "style-src 'self'",
                "img-src " + imageSources,
                "font-src 'self'",
                "connect-src 'self'",
                "object-src 'none'",
                "base-uri 'self'",
                "form-action 'self'",
                "frame-ancestors 'self'");
    }

    /** Each role's pages, in matching order, with the login page they send a user without that role to. */
    private static Map<RequestMatcher, String> loginPages() {
        LinkedHashMap<RequestMatcher, String> loginPages = new LinkedHashMap<>();
        loginPages.put(ADMIN_PAGES, "/admin/login");
        loginPages.put(VISITOR_PAGES, "/submissions/login");
        return loginPages;
    }

    /**
     * Where a request without a (live) session that isn't allowed goes: a
     * login-protected page to its role's login page, the JSON API to the
     * JSON 401 ({@link ApiRequests}, as {@code GlobalExceptionHandler} tells
     * them apart), and anything else to the HTML 404 page. Outside the API,
     * every rule but those pages' is a {@code permitAll()} or the {@code
     * denyAll()} tail, so "anything else" is a path no route serves.
     */
    private static AuthenticationEntryPoint authenticationEntryPoint(
            AuthenticationEntryPoint apiEntryPoint, AuthenticationEntryPoint pageNotFound) {
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> entryPoints = new LinkedHashMap<>();
        loginPages().forEach((pages, loginPage) -> entryPoints.put(pages, redirectTo(loginPage)));
        entryPoints.put(ApiRequests::matches, apiEntryPoint);
        DelegatingAuthenticationEntryPoint entryPoint = new DelegatingAuthenticationEntryPoint(entryPoints);
        entryPoint.setDefaultEntryPoint(pageNotFound);
        return entryPoint;
    }

    /**
     * A CSRF failure — the {@code CsrfFilter} reports it here for any
     * request, logged in or not — is a 403: the JSON one for the API, the
     * HTML error page for a page ({@link ApiRequests} tells them apart, as
     * {@code GlobalExceptionHandler} does); sending a user whose token is
     * missing or stale to a login page would explain nothing. The one
     * exception is a submission form upload too large for the container to
     * read, which loses its token with the rest of its body: it goes back to
     * the form with the upload error ({@link UnreadableFormUploadHandler}).
     *
     * <p>Any other denial reaches this handler only for an authenticated
     * user — an anonymous one goes to the {@link #authenticationEntryPoint
     * entry point} — so on one of the pages it means a session of the other
     * role: that page's login page replaces it. Done like the entry point's
     * own redirect, which Spring's {@code ExceptionTranslationFilter}
     * precedes with saving the request in the {@link #requestCache() request
     * cache}. The JSON API answers the JSON 403; anywhere else, the denial
     * came from the {@code denyAll()} tail — a path no route serves — and is
     * the HTML 404 page, saved nowhere.
     */
    private static AccessDeniedHandler accessDeniedHandler(
            RequestCache requestCache,
            AccessDeniedHandler apiDeniedHandler,
            AccessDeniedHandler pageDeniedHandler,
            AccessDeniedHandler pageNotFound,
            FlashMapManager flashMapManager) {
        LinkedHashMap<RequestMatcher, AccessDeniedHandler> notAllowed = new LinkedHashMap<>();
        loginPages().forEach((pages, loginPage) -> notAllowed.put(pages, toLoginPage(loginPage, requestCache)));
        notAllowed.put(ApiRequests::matches, apiDeniedHandler);
        AccessDeniedHandler wrongRole = new RequestMatcherDelegatingAccessDeniedHandler(notAllowed, pageNotFound);

        LinkedHashMap<RequestMatcher, AccessDeniedHandler> apiOrPage = new LinkedHashMap<>();
        apiOrPage.put(ApiRequests::matches, apiDeniedHandler);
        AccessDeniedHandler csrfFailure = new RequestMatcherDelegatingAccessDeniedHandler(apiOrPage, pageDeniedHandler);

        LinkedHashMap<Class<? extends AccessDeniedException>, AccessDeniedHandler> byFailure = new LinkedHashMap<>();
        byFailure.put(CsrfException.class, new UnreadableFormUploadHandler(flashMapManager, csrfFailure));
        return new DelegatingAccessDeniedHandler(byFailure, wrongRole);
    }

    private static AccessDeniedHandler toLoginPage(String loginPage, RequestCache requestCache) {
        AuthenticationEntryPoint redirect = redirectTo(loginPage);
        return (request, response, denied) -> {
            requestCache.saveRequest(request, response);
            redirect.commence(request, response, new InsufficientAuthenticationException(denied.getMessage(), denied));
        };
    }

    /**
     * A relative {@code Location}: an absolute one would be built from the
     * request's own scheme and host, i.e. {@code http://} behind a proxy
     * that terminates TLS.
     */
    private static AuthenticationEntryPoint redirectTo(String loginPage) {
        LoginUrlAuthenticationEntryPoint entryPoint = new LoginUrlAuthenticationEntryPoint(loginPage);
        entryPoint.setFavorRelativeUris(true);
        return entryPoint;
    }
}
