package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link CatalogConflictException} (decision 28): a clash with the existing
 * catalog — tied to the field that caused it (a slug already in use), or to
 * the change as a whole (the protected {@code general} topic).
 */
class CatalogConflictExceptionTest {

    @Test
    void fieldConflict_keepsTheFieldAndThePlainMessage() {
        CatalogConflictException ex = new CatalogConflictException("slug", "This slug is already in use.");

        assertThat(ex.getField()).contains("slug");
        assertThat(ex.getMessage()).isEqualTo("This slug is already in use.");
    }

    @Test
    void globalConflict_hasNoField() {
        CatalogConflictException ex = new CatalogConflictException("The general topic can't be deleted.");

        assertThat(ex.getField()).isEmpty();
        assertThat(ex.getMessage()).isEqualTo("The general topic can't be deleted.");
    }
}
