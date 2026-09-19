package com.iitm.beacon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the full Spring application context must load successfully,
 * including JPA/Flyway/Hibernate validation against the migrated schema.
 */
@SpringBootTest
class BeaconApplicationTests {

    @Test
    void contextLoads() {
    }
}
