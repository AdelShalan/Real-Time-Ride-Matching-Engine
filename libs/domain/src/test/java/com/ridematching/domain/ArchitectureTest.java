package com.ridematching.domain;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the dependency rule from ADR-0001.
 *
 * <p>Hexagonal architecture rots quietly: someone adds one Jackson annotation to a domain
 * record for convenience, and months later the domain module transitively depends on half
 * of Spring. A rule that is not checked is not a rule, so this runs in the build.
 */
class ArchitectureTest {

    private static JavaClasses domainClasses;

    @BeforeAll
    static void importDomain() {
        domainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ridematching.domain");
    }

    @Test
    @DisplayName("domain must not depend on any framework")
    void domainIsFrameworkFree() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.validation..",
                        // Both Jackson generations: Boot 4 ships Jackson 3 under tools.jackson,
                        // and banning only the old package would have silently reopened the
                        // hole this rule exists to close.
                        "com.fasterxml.jackson..",
                        "tools.jackson..",
                        "org.apache.kafka..",
                        "io.lettuce..",
                        "redis.clients..",
                        "org.hibernate..",
                        "io.micrometer..")
                .because("the domain model must be unit-testable without infrastructure (ADR-0001)")
                .check(domainClasses);
    }

    @Test
    @DisplayName("domain must not reach back into adapters or services")
    void domainDoesNotDependOnOuterLayers() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ridematching.platform..",
                        "..adapters..",
                        "..application..")
                .because("dependencies point inward: adapters -> application -> domain (ADR-0001)")
                .check(domainClasses);
    }
}
