package com.iitm.beacon.domain.country;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Comparator;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ISO 3166-1 alpha-2 country reference data (decision 1). Seeded once, in
 * full, via migration — not grown from submissions. The table also holds
 * one entry that is not a country: {@link #OTHER_CODE} "Other", for a
 * submitter whose country isn't listed.
 */
@Entity
@Table(name = "country")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "code")
public class Country {

    /**
     * The "Other" entry's code. {@code ZZ} is user-assigned in ISO 3166, so
     * it never collides with a real country (CLDR uses it for "unknown
     * region").
     */
    public static final String OTHER_CODE = "ZZ";

    /**
     * The order countries are offered in (decision 1): "Other" first, then
     * the countries by name — by code where two names are equal.
     */
    public static final Comparator<Country> DISPLAY_ORDER =
            Comparator.comparing((Country country) -> !country.isOther())
                    .thenComparing(Country::getName)
                    .thenComparing(Country::getCode);

    @Id
    @Column(name = "code", length = 2, nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    /** Whether this is the "Other" entry ({@link #OTHER_CODE}) rather than a country. */
    public boolean isOther() {
        return OTHER_CODE.equals(code);
    }
}
