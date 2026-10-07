package com.iitm.beacon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@link UserDetailsServiceAutoConfiguration} is excluded: users log in only
 * through an OTP session ({@code common.security.SessionAuthenticator}), so
 * Boot's in-memory user with a generated password would be an unused
 * credential.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class BeaconApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeaconApplication.class, args);
    }
}
