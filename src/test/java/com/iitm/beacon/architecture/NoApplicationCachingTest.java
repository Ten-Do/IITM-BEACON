package com.iitm.beacon.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.iitm.beacon.common.ratelimit.RateLimiterService;
import com.iitm.beacon.submission.VisitorOtpService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * NFR-CATALOG-CONFIGURABILITY: the catalog tables "are read per request, with
 * no application-level caching to invalidate". Guards the ways a cache could
 * creep in: Spring's cache abstraction ({@code @Cacheable}, {@code
 * @EnableCaching}, a {@code CacheManager}), Hibernate's second-level entity
 * cache ({@code @jakarta.persistence.Cacheable}, {@code
 * @org.hibernate.annotations.Cache}), and a hand-rolled Caffeine cache —
 * which is allowed only for the OTP and rate-limit state, never catalog data.
 */
@AnalyzeClasses(packages = "com.iitm.beacon", importOptions = ImportOption.DoNotIncludeTests.class)
class NoApplicationCachingTest {

    @ArchTest
    static final ArchRule no_spring_cache_abstraction =
            noClasses()
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.cache..")
                    .because("catalog changes must take effect on the next request (NFR-CATALOG-CONFIGURABILITY)");

    @ArchTest
    static final ArchRule no_second_level_entity_cache =
            noClasses()
                    .should().beAnnotatedWith(jakarta.persistence.Cacheable.class)
                    .orShould().beAnnotatedWith(org.hibernate.annotations.Cache.class)
                    .because("catalog changes must take effect on the next request (NFR-CATALOG-CONFIGURABILITY)");

    @ArchTest
    static final ArchRule caffeine_only_holds_otp_and_rate_limit_state =
            noClasses()
                    .that().doNotHaveFullyQualifiedName(VisitorOtpService.class.getName())
                    .and().doNotHaveFullyQualifiedName(RateLimiterService.class.getName())
                    .should().dependOnClassesThat().resideInAPackage("com.github.benmanes.caffeine..")
                    .because("an in-memory cache of catalog data would outlive an admin's change"
                            + " (NFR-CATALOG-CONFIGURABILITY)");
}
