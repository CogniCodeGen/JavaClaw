package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.CodingContracts;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 显式发行验收使用真实已下载并再次核验摘要的归档，实际命令经过生产沙箱而非进程替身。 */
@EnabledIfSystemProperty(named = "javaclaw.toolchainArchives", matches = ".+")
class PublishedCodingExecutionTest {
    @TempDir
    Path temporary;

    @Test
    void 真实五类发行工具链在断网沙箱中执行并运行Gradle项目任务() throws Exception {
        var toolchains = new FixtureToolchains();
        try (var fixture = new CodingTestFixture(temporary, toolchains)) {
            PublishedToolchainFixture.install(fixture, toolchains);
            int number = 0;
            var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
            for (String executable :
                    List.of("java", "javac", "mvn", "gradle", "node", "npm", "pnpm", "python", "pip")) {
                var result = fixture.invoke(
                        "command_run",
                        new CodingContracts.CommandRun(
                                executable.equals("gradle")
                                        ? List.of(executable, "--version", "--stacktrace")
                                        : List.of(executable, "--version"),
                                ".",
                                30,
                                65536),
                        "published-" + number++);
                checks.add(() -> assertTrue(
                        result.success(),
                        executable + ": " + result.response().payload().json()));
            }
            Files.writeString(fixture.root.resolve("settings.gradle"), "rootProject.name = 'coding-native-test'\n");
            Files.writeString(
                    fixture.root.resolve("build.gradle"),
                    "tasks.register('verifyCoding') { doLast { println('CODING_GRADLE_OK') } }\n");
            var build = fixture.invoke(
                    "command_run",
                    new CodingContracts.CommandRun(List.of("gradle", "verifyCoding", "--stacktrace"), ".", 30, 65536),
                    "published-gradle-build");
            checks.add(
                    () -> assertTrue(build.success(), build.response().payload().json()));
            checks.add(() -> assertTrue(build.response().payload().json().contains("CODING_GRADLE_OK")));
            org.junit.jupiter.api.Assertions.assertAll(checks);
        }
    }
}
