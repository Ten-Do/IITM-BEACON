package com.iitm.beacon.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Arrays;

/**
 * Enforces decision 9 (docs/decisions.md): no feature slice imports another
 * feature slice's classes directly — only domain/common/config. Rules for
 * slices not yet implemented in code pass vacuously and start enforcing
 * automatically once those slices exist.
 */
@AnalyzeClasses(packages = "com.iitm.beacon", importOptions = ImportOption.DoNotIncludeTests.class)
class SliceIsolationTest {

    private static final String ROOT = "com.iitm.beacon";
    private static final String[] SLICES = {
        "gallery", "submission", "moderation", "catalogadmin", "adminauth", "analytics"
    };

    @ArchTest
    static final ArchRule gallery_must_not_depend_on_other_slices = sliceIsolationRule("gallery");

    @ArchTest
    static final ArchRule submission_must_not_depend_on_other_slices = sliceIsolationRule("submission");

    @ArchTest
    static final ArchRule moderation_must_not_depend_on_other_slices = sliceIsolationRule("moderation");

    @ArchTest
    static final ArchRule catalogadmin_must_not_depend_on_other_slices = sliceIsolationRule("catalogadmin");

    @ArchTest
    static final ArchRule adminauth_must_not_depend_on_other_slices = sliceIsolationRule("adminauth");

    @ArchTest
    static final ArchRule analytics_must_not_depend_on_other_slices = sliceIsolationRule("analytics");

    private static ArchRule sliceIsolationRule(String slice) {
        String[] otherSlicePackages = Arrays.stream(SLICES)
                .filter(s -> !s.equals(slice))
                .map(s -> ROOT + "." + s + "..")
                .toArray(String[]::new);

        return noClasses()
                .that().resideInAPackage(ROOT + "." + slice + "..")
                .should().dependOnClassesThat().resideInAnyPackage(otherSlicePackages)
                .because("feature slices depend only on domain/common/config, never on each other "
                        + "directly (docs/architecture.md §1, decision 9)")
                // Slices not yet implemented (e.g. gallery, moderation) contain no classes
                // yet — without this, ArchUnit fails a rule that matched zero classes instead
                // of passing vacuously, which would block this rule from existing ahead of
                // those slices being built.
                .allowEmptyShould(true);
    }
}
