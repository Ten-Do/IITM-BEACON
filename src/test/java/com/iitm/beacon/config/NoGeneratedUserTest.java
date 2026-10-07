package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * The app authenticates only through OTP sessions ({@code
 * common.security.SessionAuthenticator}), so Spring Boot's {@code
 * UserDetailsServiceAutoConfiguration} — an in-memory user with a generated
 * password, announced at WARN on every start, prod included — is excluded:
 * no unused credential exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class NoGeneratedUserTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextHasNoUserDetailsService() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }
}
