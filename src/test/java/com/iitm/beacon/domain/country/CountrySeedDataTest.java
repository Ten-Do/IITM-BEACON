package com.iitm.beacon.domain.country;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the country seed migrations (decision 1) — V12's full ISO 3166-1
 * alpha-2 country list, plus V19's one non-country entry, {@code ZZ}
 * "Other".
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
    void seededRowCount_isTheFullIso3166AlphaTwoListPlusOther() {
        assertThat(countryRepository.count()).isEqualTo(249 + 1);
    }

    @Test
    void otherIsSeeded_underTheUserAssignedCodeZz() {
        assertThat(countryRepository.findById("ZZ"))
                .isPresent()
                .get()
                .extracting(Country::getName)
                .isEqualTo("Other");
        assertThat(Country.OTHER_CODE).isEqualTo("ZZ");
    }

    @Test
    void otherIsTheOnlyEntryCalledOther() {
        assertThat(countryRepository.findAll())
                .filteredOn(country -> "Other".equalsIgnoreCase(country.getName()))
                .extracting(Country::getCode)
                .containsExactly("ZZ");
    }
}
