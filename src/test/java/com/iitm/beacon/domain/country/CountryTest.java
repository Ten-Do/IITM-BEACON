package com.iitm.beacon.domain.country;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Country#DISPLAY_ORDER} — the order every country list a visitor
 * picks from is shown in (decision 1): the {@code ZZ} "Other" entry first,
 * then the countries by name. No Spring, no database.
 */
class CountryTest {

    private static Country country(String code, String name) {
        return Country.builder().code(code).name(name).build();
    }

    private static List<String> codesInDisplayOrder(Country... countries) {
        List<Country> list = new ArrayList<>(List.of(countries));
        list.sort(Country.DISPLAY_ORDER);
        return list.stream().map(Country::getCode).toList();
    }

    @Test
    void other_goesFirst_thenTheCountriesByName() {
        assertThat(codesInDisplayOrder(
                        country("US", "United States of America"),
                        country("ZZ", "Other"),
                        country("DE", "Germany"),
                        country("IN", "India")))
                .containsExactly("ZZ", "DE", "IN", "US");
    }

    /** "Other" isn't sorted in by its name: it precedes even names that come before it alphabetically. */
    @Test
    void other_precedesANameThatSortsBeforeIt() {
        assertThat(codesInDisplayOrder(country("AF", "Afghanistan"), country("ZZ", "Other")))
                .containsExactly("ZZ", "AF");
    }

    @Test
    void withoutOther_theCountriesAreSimplyByName() {
        assertThat(codesInDisplayOrder(
                        country("ZW", "Zimbabwe"), country("AD", "Andorra"), country("IN", "India")))
                .containsExactly("AD", "IN", "ZW");
    }

    @Test
    void otherAlone_isTheWholeList() {
        assertThat(codesInDisplayOrder(country("ZZ", "Other"))).containsExactly("ZZ");
    }

    @Test
    void anEmptyList_staysEmpty() {
        assertThat(codesInDisplayOrder()).isEmpty();
    }

    /** "Other" is recognised by its code, not its name: a renamed ZZ still leads, a country named "Other" doesn't. */
    @Test
    void otherIsRecognisedByItsCode_notItsName() {
        assertThat(codesInDisplayOrder(
                        country("XQ", "Other"), country("ZZ", "Somewhere else"), country("AD", "Andorra")))
                .containsExactly("ZZ", "AD", "XQ");
    }

    /** Two entries of the same name (possible only for rows added by hand) still get one fixed order. */
    @Test
    void sameName_goesByCode() {
        assertThat(codesInDisplayOrder(country("XB", "Testland"), country("XA", "Testland")))
                .containsExactly("XA", "XB");
    }

    @Test
    void isOther_onlyForTheZzEntry() {
        assertThat(country("ZZ", "Other").isOther()).isTrue();
        assertThat(country("IN", "India").isOther()).isFalse();
        assertThat(country("XQ", "Other").isOther()).isFalse();
    }
}
