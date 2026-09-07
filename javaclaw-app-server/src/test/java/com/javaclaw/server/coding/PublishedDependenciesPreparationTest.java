package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingContracts.PackageManager;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 显式发行门禁：真实仓库经生产 CONNECT Broker 准备，关闭租约后以新断网进程执行。 */
@EnabledIfSystemProperty(named = "javaclaw.codingNetworkAcceptance", matches = "true")
class PublishedDependenciesPreparationTest {
    @TempDir
    Path temporary;

    @Test
    void nativePackageManagersPreparePublicDependenciesBeforeSeparateOfflineRuns() throws Exception {
        var toolchains = new FixtureToolchains();
        Set<String> repositories =
                Set.of("repo.maven.apache.org", "registry.npmjs.org", "pypi.org", "files.pythonhosted.org");
        try (var fixture = new CodingTestFixture(temporary, toolchains, repositories)) {
            PublishedToolchainFixture.install(fixture, toolchains);
            projects(fixture.root);
            var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
            checks.add(() -> prepareAndRun(fixture, PackageManager.NPM, "npm", List.of(), List.of("npm", "test")));
            checks.add(() -> prepareAndRun(fixture, PackageManager.PNPM, "pnpm", List.of(), List.of("pnpm", "test")));
            checks.add(() -> prepareAndRun(
                    fixture,
                    PackageManager.PIP,
                    "python",
                    List.of(),
                    List.of("python", "-c", "import idna; assert idna.__version__ == '3.10'")));
            checks.add(() ->
                    prepareAndRun(fixture, PackageManager.MAVEN, "maven", List.of("test"), List.of("mvn", "test")));
            checks.add(() -> prepareAndRun(
                    fixture,
                    PackageManager.GRADLE,
                    "gradle",
                    List.of("verifyCoding"),
                    List.of("gradle", "verifyCoding")));
            org.junit.jupiter.api.Assertions.assertAll(checks);
        }
    }

    private static void prepareAndRun(
            CodingTestFixture fixture,
            PackageManager manager,
            String project,
            List<String> targets,
            List<String> command)
            throws Exception {
        System.out.println("Coding acceptance: " + manager + " prepare through controlled proxy");
        var prepared = fixture.invoke(
                "dependencies_prepare",
                new CodingContracts.DependenciesPrepare(manager, project, targets),
                project + "-prepare");
        assertTrue(
                prepared.success(),
                manager + " prepare: " + prepared.response().payload().json());
        System.out.println("Coding acceptance: " + manager + " run in a separate offline process");
        var executed = fixture.invoke(
                "command_run", new CodingContracts.CommandRun(command, project, 180, 65536), project + "-offline");
        assertTrue(
                executed.success(),
                manager + " offline: " + executed.response().payload().json());
        if (manager == PackageManager.MAVEN || manager == PackageManager.GRADLE) {
            String report = manager == PackageManager.MAVEN
                    ? "target/surefire-reports/TEST-NativeTest.xml"
                    : "build/test-results/test/TEST-NativeTest.xml";
            String tests = Files.readString(fixture.root.resolve(project).resolve(report));
            assertTrue(tests.contains("tests=\"1\""), manager + " did not execute the real project test");
            assertTrue(tests.contains("failures=\"0\""), manager + " reported a project test failure");
        }
        System.out.println("Coding acceptance: " + manager + " prepare and offline verification passed");
    }

    private static void projects(Path root) throws Exception {
        nodeProjects(root);
        javaProjects(root);
        Path python = Files.createDirectories(root.resolve("python"));
        Files.writeString(python.resolve("requirements.txt"), "idna==3.10\n");
    }

    private static void nodeProjects(Path root) throws Exception {
        for (String name : List.of("npm", "pnpm")) {
            Path project = Files.createDirectories(root.resolve(name));
            Files.writeString(project.resolve("package.json"), """
                    {"name":"coding-acceptance","version":"1.0.0","private":true,
                     "scripts":{"postinstall":"node prepare-check.js","test":"node test.js"},
                     "dependencies":{"is-number":"7.0.0"}}
                    """);
            Files.writeString(
                    project.resolve("test.js"),
                    "const assert = require('node:assert'); assert(require('is-number')(42));\n"
                            + "assert.equal(require('node:fs').readFileSync('prepared-by-lifecycle', 'utf8'), 'prepared');\n");
            Files.writeString(
                    project.resolve("prepare-check.js"),
                    "require('node:fs').writeFileSync('prepared-by-lifecycle', 'prepared');\n");
        }
    }

    private static void javaProjects(Path root) throws Exception {
        Path maven = Files.createDirectories(root.resolve("maven/src/test/java"));
        Files.writeString(root.resolve("maven/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>coding-test</artifactId><version>1.0</version>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                  <dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId>
                    <version>4.13.2</version><scope>test</scope></dependency></dependencies>
                </project>
                """);
        Files.writeString(maven.resolve("NativeTest.java"), """
                public class NativeTest {
                    @org.junit.Test public void verifiesManagedCompilation() {
                        org.junit.Assert.assertEquals(4, 2 + 2);
                    }
                }
                """);
        Path gradle = Files.createDirectories(root.resolve("gradle"));
        Files.writeString(gradle.resolve("settings.gradle"), "rootProject.name = 'coding-acceptance'\n");
        Files.writeString(gradle.resolve("build.gradle"), """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies { testImplementation 'junit:junit:4.13.2' }
                tasks.register('verifyCoding') { dependsOn tasks.test }
                """);
        Path gradleTest = Files.createDirectories(gradle.resolve("src/test/java"));
        Files.writeString(gradleTest.resolve("NativeTest.java"), """
                public class NativeTest {
                    @org.junit.Test public void verifiesManagedGradleCompilation() {
                        org.junit.Assert.assertEquals(4, 2 + 2);
                    }
                }
                """);
    }
}
