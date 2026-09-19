package com.iitm.beacon.domain.country;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * Generic repository CRUD/constraint behavior, exercised with fixture codes
 * ("ZZ", "XX", "QQ") deliberately outside the real ISO 3166-1 list so these
 * tests are independent of the seeded reference data verified separately in
 * {@link CountrySeedDataTest}.
 */
class CountryRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savedCountry_roundTripsByCode() {
        Country fixture = Country.builder().code("ZZ").name("Testland").build();
        countryRepository.saveAndFlush(fixture);

        var found = countryRepository.findById("ZZ");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Testland");
    }

    @Test
    void findById_unknownCode_returnsEmpty() {
        assertThat(countryRepository.findById("QQ")).isEmpty();
    }

    @Test
    void duplicateCode_violatesPrimaryKeyUniqueness() {
        // Country has an assigned (non-generated) natural key, so
        // repository.save() would silently fall back to merge() semantics
        // for a second call with the same code — persist() directly via the
        // TestEntityManager forces a genuine duplicate INSERT instead.
        entityManager.persistAndFlush(Country.builder().code("XX").name("Fixtureland").build());

        assertThatThrownBy(() ->
                        entityManager.persistAndFlush(Country.builder().code("XX").name("Duplicate").build()))
                .isInstanceOf(PersistenceException.class);
    }
}
