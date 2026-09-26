package com.iitm.beacon.config;

import com.iitm.beacon.common.error.RestAccessDeniedHandler;
import com.iitm.beacon.common.error.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * App-wide Spring Security configuration, even though it configures security
 * for every slice (docs/architecture.md §3).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            RestAuthenticationEntryPoint entryPoint,
            RestAccessDeniedHandler deniedHandler)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
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
                        .requestMatchers(HttpMethod.GET, "/api/submissions/mine")
                        .hasRole("VISITOR")
                        .requestMatchers(HttpMethod.PUT, "/api/submissions/mine")
                        .hasRole("VISITOR")
                        .requestMatchers("/css/**", "/js/**", "/images/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/gallery/**")
                        .permitAll()
                        .requestMatchers("/api/moderation/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/", "/gallery/**")
                        .permitAll()
                        .requestMatchers("/admin/login", "/admin/login/**")
                        .permitAll()
                        .requestMatchers("/submissions/login", "/submissions/login/**")
                        .permitAll()
                        .requestMatchers("/submissions/form", "/submissions/confirmation")
                        .hasRole("VISITOR")
                        .requestMatchers("/moderation/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .denyAll())
                .headers(headers -> headers.frameOptions(FrameOptionsConfig::sameOrigin))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
