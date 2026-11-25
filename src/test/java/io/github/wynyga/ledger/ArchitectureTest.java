package io.github.wynyga.ledger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Dependencies point inwards: adapters -> application -> domain. Frameworks stay in the adapters and config. */
@AnalyzeClasses(packages = "io.github.wynyga.ledger", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    static final String[] FRAMEWORKS = {"org.springframework..", "jakarta..", "org.hibernate..", "tools.jackson.."};

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
            .orShould().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..", "..config..");

    @ArchTest
    static final ArchRule applicationIsPlainJava = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
            .orShould().dependOnClassesThat().resideInAnyPackage("..adapter..", "..config..");

    @ArchTest
    static final ArchRule webDoesNotReachIntoPersistence = noClasses().that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.out..");

    @ArchTest
    static final ArchRule persistenceDoesNotKnowTheWeb = noClasses().that().resideInAPackage("..adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.in..");
}
