package com.iitm.beacon.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Enforces that domain holds only JPA entities and Spring Data repositories
 * (docs/architecture.md §3) — no business logic.
 */
@AnalyzeClasses(packages = "com.iitm.beacon", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainPurityTest {

    @ArchTest
    static final ArchRule domain_contains_no_services_or_controllers =
            noClasses()
                    .that().resideInAPackage("com.iitm.beacon.domain..")
                    .should().beAnnotatedWith(Service.class)
                    .orShould().beAnnotatedWith(RestController.class)
                    .orShould().beAnnotatedWith(Controller.class)
                    .because("domain holds only JPA entities and Spring Data repositories "
                            + "(docs/architecture.md §3)");
}
