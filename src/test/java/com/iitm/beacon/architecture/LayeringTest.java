package com.iitm.beacon.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * Enforces Controller -> Service -> Repository -> DB (CLAUDE.md,
 * docs/architecture.md §1).
 */
@AnalyzeClasses(packages = "com.iitm.beacon", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringTest {

    @ArchTest
    static final ArchRule controllers_must_not_depend_on_repositories_directly =
            noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                    .because("Controller -> Service -> Repository; a Controller reaching a "
                            + "Repository directly skips the Service layer (CLAUDE.md, "
                            + "docs/architecture.md §1)");

    @ArchTest
    static final ArchRule controllers_are_properly_annotated =
            classes()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().beAnnotatedWith(RestController.class)
                    .orShould().beAnnotatedWith(Controller.class);

    @ArchTest
    static final ArchRule controllers_reside_in_a_feature_slice =
            classes()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().resideInAnyPackage(
                            "com.iitm.beacon.gallery..",
                            "com.iitm.beacon.submission..",
                            "com.iitm.beacon.moderation..",
                            "com.iitm.beacon.catalogadmin..",
                            "com.iitm.beacon.adminauth..",
                            "com.iitm.beacon.analytics..");
}
