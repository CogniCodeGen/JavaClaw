package com.javaclaw.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void productFeaturesDependOnlyOnFrameworkApiAndSpi() {
        noClasses()
                .that()
                .resideOutsideOfPackages(
                        "com.javaclaw.framework..",
                        "com.javaclaw.platform.spring..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.javaclaw.framework.core..",
                        "com.javaclaw.framework.springai..")
                .because("product features use framework API/SPI; only the composition root wires internals")
                .check(productionClasses);

        noClasses()
                .that()
                .resideOutsideOfPackages(
                        "com.javaclaw.framework..",
                        "com.javaclaw.platform.spring..",
                        "com.javaclaw.infrastructure..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.javaclaw.framework.extension..",
                        "com.javaclaw.framework.store..",
                        "com.javaclaw.framework.builtin..")
                .because("product features consume public framework API/SPI, not extension loading, stores or built-ins")
                .check(productionClasses);
    }

    @Test
    void onlySpringAiAdapterOwnsChatClientChatModelAndToolCallingLoop() {
        noClasses()
                .that()
                .resideOutsideOfPackage("com.javaclaw.framework.springai..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.ai.chat.client.ChatClient")
                .because("ChatClient construction belongs to framework.springai")
                .check(productionClasses);
        noClasses()
                .that()
                .resideOutsideOfPackage("com.javaclaw.framework.springai..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.ai.chat.model.ChatModel")
                .because("ChatModel construction belongs to framework.springai")
                .check(productionClasses);
        noClasses()
                .that()
                .resideOutsideOfPackage("com.javaclaw.framework.springai..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(
                        "org.springframework.ai.chat.client.advisor.ToolCallingAdvisor")
                .because("the recursive tool-calling loop belongs to framework.springai")
                .check(productionClasses);
    }

    @Test
    void onlySpringAiAdapterDependsOnToolCallback() {
        noClasses()
                .that()
                .resideOutsideOfPackage("com.javaclaw.framework.springai..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.ai.tool.ToolCallback")
                .because("all ToolCallback execution must pass through the framework tool gateway")
                .check(productionClasses);
    }

    @Test
    void removedAgentLibraryCannotReenterProductionCode() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io." + "agent" + "scope..")
                .because("Spring AI 2.0 is the only model and agent substrate")
                .check(productionClasses);
    }

    @Test
    void deliveranceNeverEntersTheHostApplicationClasspath() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.teknek." + "deliverance..")
                .because("Deliverance and native inference must remain in its isolated service-plugin JVM")
                .check(productionClasses);
    }

    @Test
    void servicePluginSourcesUseTheSingleHostAndSamplePluginLayout() throws Exception {
        assertFalse(Files.exists(Path.of("runtime")),
                "runtime/ source aggregator must not return");
        assertFalse(Files.exists(Path.of("service-plugins")),
                "service-plugins/ must not duplicate sample plugin sources");
        Path sample = Path.of("sample-plugins/deliverance");
        assertTrue(Files.isRegularFile(sample.resolve("pom.xml")));
        try (var descriptors = Files.walk(sample.resolve("src"))) {
            assertEquals(1, descriptors.filter(path -> path.getFileName().toString()
                    .equals("plugin.json")).count());
        }
        assertTrue(Files.isDirectory(Path.of("src/main/java/com/javaclaw/service/api")));
        assertTrue(Files.isDirectory(Path.of("src/main/java/com/javaclaw/service/runner")));
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/protocol/service-plugin.yaml")));
    }
}
