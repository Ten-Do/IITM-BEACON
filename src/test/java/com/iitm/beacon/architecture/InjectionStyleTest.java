package com.iitm.beacon.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

/**
 * Regression guard for constructor-only injection (CLAUDE.md: "Constructor
 * injection only — no field @Autowired").
 */
@AnalyzeClasses(packages = "com.iitm.beacon", importOptions = ImportOption.DoNotIncludeTests.class)
class InjectionStyleTest {

    @ArchTest
    static final ArchRule no_field_injection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
}
