package com.javaclaw.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRulesTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.javaclaw");
    }

    @Test
    void applicationLayerDoesNotDependOnPresentationOrInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage("com.javaclaw.application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.javaclaw.infrastructure..",
                        "com.javaclaw.presentation..",
                        "com.javaclaw.ui..",
                        "com.javaclaw.chat..",
                        "javafx..",
                        "org.springframework..")
                .because("application use cases define ports and must remain UI/framework independent")
                .check(productionClasses);
    }

    @Test
    void infrastructureDoesNotDependOnDesktopPresentation() {
        noClasses()
                .that()
                .resideInAPackage("com.javaclaw.infrastructure..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.javaclaw.presentation..",
                        "com.javaclaw.ui..",
                        "com.javaclaw.chat..",
                        "javafx..")
                .because("infrastructure implements application ports without knowing JavaFX views")
                .check(productionClasses);
    }

    @Test
    void javaFxControllersDoNotReachPersistenceAdapters() {
        noClasses()
                .that()
                .haveSimpleNameEndingWith("Controller")
                .and()
                .resideInAnyPackage("com.javaclaw.ui..", "com.javaclaw.chat..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.javaclaw.infrastructure..",
                        "java.sql..",
                        "org.springframework.jdbc..")
                .because("FXML controllers coordinate application services instead of persistence")
                .check(productionClasses);
    }

    @Test
    void javaFxControllersDoNotUseLegacyWorkspaceManager() {
        noClasses()
                .that()
                .haveSimpleNameEndingWith("Controller")
                .and()
                .resideInAnyPackage("com.javaclaw.ui..", "com.javaclaw.chat..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.javaclaw.config.WorkspaceManager")
                .because("workspace commands and queries cross the application workspace port")
                .check(productionClasses);
    }
}
