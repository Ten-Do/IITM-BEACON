package com.iitm.beacon.domain.country;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the V12 Flyway seed migration (decision 1) — the full ISO 3166-1
 * alpha-2 country list.
 */
class CountrySeedDataTest extends AbstractRepositoryTest {

    @Autowired
    private CountryRepository countryRepository;

    @Test
    void knownCodes_roundTripToExpectedCanonicalNames() {
        assertThat(countryRepository.findById("IN")).isPresent().get().extracting(Country::getName).isEqualTo("India");
        assertThat(countryRepository.findById("US"))
                .isPresent()
                .get()
                .extracting(Country::getName)
                .isEqualTo("United States of America");
        assertThat(countryRepository.findById("DE"))
                .isPresent()
                .get()
                .extracting(Country::getName)
                .isEqualTo("Germany");
    }

    @Test
    void seededRowCount_matchesTheFullIso3166AlphaTwoList() {
        assertThat(countryRepository.count()).isEqualTo(249);
    }
}
