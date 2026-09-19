package com.iitm.beacon.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * App-wide Spring Security configuration is cross-cutting infrastructure, not
 * owned by any single feature slice (docs/architecture.md §3).
 */
@AnalyzeClasses(packages = "com.iitm.beacon", importOptions = ImportOption.DoNotIncludeTests.class)
class SecurityConfigLocationTest {

    @ArchTest
    static final ArchRule security_config_resides_in_config_package =
            classes()
                    .that().areAnnotatedWith(EnableWebSecurity.class)
                    .should().resideInAPackage("com.iitm.beacon.config..")
                    .because("app-wide Spring Security configuration is cross-cutting "
                            + "infrastructure, not owned by any single feature slice "
                            + "(docs/architecture.md §3)");
}
