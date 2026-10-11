package com.iitm.beacon.domain;

import com.iitm.beacon.common.crypto.CryptoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * Shared base for every repository test. {@code Replace.NONE} is essential:
 * without it, {@code @DataJpaTest}'s own auto-configuration tries to swap
 * in an embedded database instead of the shared PostgreSQL test database
 * every test context runs on ({@code testsupport.SharedTestDatabase}) — and
 * fails, as the project has no embedded database.
 *
 * <p>{@code @DataJpaTest}'s type-exclude filter also drops beans discovered
 * purely via {@code @ConfigurationPropertiesScan} (like {@link
 * CryptoProperties}), even though it still picks up {@code
 * EncryptedValueConverter} as an {@code AttributeConverter}. Since that
 * converter depends on {@link CryptoProperties}, it must be re-enabled
 * explicitly here or every repository test touching an encrypted column
 * fails to start its context.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableConfigurationProperties(CryptoProperties.class)
public abstract class AbstractRepositoryTest {
}
