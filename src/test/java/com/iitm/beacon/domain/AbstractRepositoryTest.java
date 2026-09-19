package com.iitm.beacon.domain;

import com.iitm.beacon.common.crypto.CryptoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * Shared base for every repository test. {@code Replace.NONE} is essential:
 * without it, {@code @DataJpaTest}'s own auto-configuration silently swaps in
 * a default embedded H2 datasource that ignores the custom
 * {@code NON_KEYWORDS=VALUE;MODE=PostgreSQL} URL from
 * src/test/resources/application.yml, reintroducing the H2/Postgres
 * portability problems that URL exists to fix.
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
