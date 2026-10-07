package com.iitm.beacon.config;

import com.iitm.beacon.common.error.RestAccessDeniedHandler;
import com.iitm.beacon.common.error.RestAuthenticationEntryPoint;
import java.util.LinkedHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

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
 * request — the JSON API, the {@code denyAll()} tail — keeps {@link
 * RestAuthenticationEntryPoint}'s JSON 401.
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

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
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
            RequestCache requestCache,
            RestAuthenticationEntryPoint apiEntryPoint,
            RestAccessDeniedHandler deniedHandler)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
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
                        // The article's contact reveal, the only POST under /gallery. CSRF is off
                        // (BL-004): its handler's own same-origin check (common.web.SameOriginOnly)
                        // is what keeps other sites and naive scrapers out.
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
                .headers(headers -> headers.frameOptions(FrameOptionsConfig::sameOrigin))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint(apiEntryPoint))
                        .accessDeniedHandler(deniedHandler))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private static AuthenticationEntryPoint authenticationEntryPoint(AuthenticationEntryPoint apiEntryPoint) {
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> loginPages = new LinkedHashMap<>();
        loginPages.put(ADMIN_PAGES, redirectTo("/admin/login"));
        loginPages.put(VISITOR_PAGES, redirectTo("/submissions/login"));
        DelegatingAuthenticationEntryPoint entryPoint = new DelegatingAuthenticationEntryPoint(loginPages);
        entryPoint.setDefaultEntryPoint(apiEntryPoint);
        return entryPoint;
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
