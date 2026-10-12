package com.javaclaw.code;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.process.ProcessRunner;
import com.javaclaw.util.ProjectAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeToolsBehaviorTest {

    @TempDir
    Path project;

    private final ManagedTaskExecutor executor = new ManagedTaskExecutor();
    private final CodeTools tools = new CodeTools(
            ToolCallOrigin.UNKNOWN, new ProcessRunner(executor));
    private boolean confirmationWasEnabled;

    @BeforeEach
    void allowDeterministicFileMutations() {
        confirmationWasEnabled = ToolConfirmationManager.isEnabled();
        ToolConfirmationManager.setEnabled(false);
        tools.setProjectRootForTest(project);
    }

    @AfterEach
    void closeResources() {
        ToolConfirmationManager.setEnabled(confirmationWasEnabled);
        executor.close();
    }

    @Test
    void projectRootValidationHandlesEmptyOutsideMissingFileAndDirectory() throws Exception {
        assertFailure(tools.setProjectRoot(null), "路径不能为空");
        assertFailure(tools.setProjectRoot(" "), "路径不能为空");
        assertFailure(tools.setProjectRoot(project.toString()), "项目外");

        Path inside = Files.createTempDirectory(
                ProjectAccessPolicy.projectRoot().resolve("target"), "code-root-");
        assertFailure(tools.setProjectRoot(inside.resolve("missing").toString()), "不存在");
        Path file = Files.writeString(inside.resolve("file.txt"), "text");
        assertFailure(tools.setProjectRoot(file.toString()), "不是目录");
        assertTrue(tools.setProjectRoot(inside.toString()).contains("项目根已设为"));
    }

    @Test
    void readReportsPathTypeRangeSizeCredentialAndTruncationBoundaries() throws Exception {
        assertFailure(tools.read(null, 0, 0), "路径不能为空");
        assertFailure(tools.read(" ", 0, 0), "路径不能为空");
        assertFailure(tools.read("../escape.txt", 0, 0), "越出项目根");
        assertFailure(tools.read("missing.txt", 0, 0), "不存在");
        assertFailure(tools.read(project.toString(), 0, 0), "不是文件");

        Path text = Files.writeString(project.resolve("lines.txt"), "one\ntwo\nthree\n");
        assertFailure(tools.read(text.toString(), 8, 0), "超出文件总行数");
        assertFailure(tools.read(text.toString(), 3, 2), "结束行不能小于");
        assertTrue(tools.read(text.toString(), -1, 99).contains("显示: 1-3"));

        Path secret = Files.writeString(project.resolve("secret.txt"),
                "api_key=super-secret-value");
        assertFailure(tools.read(secret.toString(), 0, 0), "凭据");

        Path huge = project.resolve("huge.txt");
        Files.writeString(huge, "x".repeat(8 * 1024 * 1024 + 1));
        assertFailure(tools.read(huge.toString(), 0, 0), "文件过大");

        Path longOutput = project.resolve("long-output.txt");
        Files.writeString(longOutput, ("z".repeat(500) + "\n").repeat(150));
        assertTrue(tools.read(longOutput.toString(), 0, 0).contains("输出达 60000 字符上限"));
    }

    @Test
    void grepValidatesInputsFiltersUnsafeFilesAndBoundsResults() throws Exception {
        assertFailure(tools.grep(null, null, null, false, 0), "检索模式不能为空");
        assertFailure(tools.grep(" ", null, null, false, 0), "检索模式不能为空");
        assertFailure(tools.grep("[", null, null, false, 0), "正则表达式非法");
        Path ordinary = Files.writeString(project.resolve("ordinary.txt"), "needle safe\n");
        assertFailure(tools.grep("needle", ordinary.toString(), null, false, 0), "不是目录");
        assertTrue(tools.grep("absent", null, null, false, 0).contains("无匹配"));

        Files.writeString(project.resolve("long.txt"), "needle " + "x".repeat(400));
        Files.writeString(project.resolve("credential.txt"),
                "needle api_key=super-secret-value");
        Files.write(project.resolve("binary.bin"), new byte[]{0, 'n', 'e', 'e', 'd', 'l', 'e'});
        Files.writeString(project.resolve("oversize.txt"),
                "needle" + "x".repeat(2 * 1024 * 1024));
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("target/skipped.txt"), "needle");

        String result = tools.grep("needle", null, "**/*.txt", false, 2);
        assertTrue(result.contains("匹配 2（达上限"), result);
        assertTrue(result.contains("疑似凭据内容已隐藏"), result);
        assertTrue(result.contains("…"), result);
        assertFalse(result.contains("skipped.txt"), result);
        assertFalse(result.contains("binary.bin"), result);
        assertFalse(result.contains("oversize.txt"), result);
        assertFailure(tools.grep("needle", "../outside", null, false, 1), "越出项目根");
    }

    @Test
    void globValidatesPatternsSkipsBuildTreesAndCapsLargeListings() throws Exception {
        assertFailure(tools.glob(null, null), "glob 模式不能为空");
        assertFailure(tools.glob(" ", null), "glob 模式不能为空");
        assertFailure(tools.glob("[", null), "失败");
        Path file = Files.writeString(project.resolve("single.java"), "class Single {}");
        assertFailure(tools.glob("*.java", file.toString()), "不是目录");
        assertTrue(tools.glob("*.kt", null).contains("无匹配文件"));
        assertFailure(tools.glob("*.java", "../outside"), "越出项目根");

        Path generated = Files.createDirectories(project.resolve("generated"));
        for (int i = 0; i < 305; i++) {
            Files.writeString(generated.resolve("F%03d.java".formatted(i)), "class F {}\n");
        }
        Files.createDirectories(project.resolve("node_modules"));
        Files.writeString(project.resolve("node_modules/Hidden.java"), "class Hidden {}");
        String result = tools.glob("**/*.java", null);
        assertTrue(result.contains("匹配 300（达上限"), result);
        assertFalse(result.contains("Hidden.java"), result);
    }

    @Test
    void editRequiresUniqueSafeReplacementAndWritesAtomically() throws Exception {
        Path file = Files.writeString(project.resolve("edit.txt"), "alpha\nbeta\nbeta\n");
        assertFailure(tools.edit(file.toString(), null, "x"), "old_string 不能为空");
        assertFailure(tools.edit(file.toString(), "", "x"), "old_string 不能为空");
        assertFailure(tools.edit(file.toString(), "alpha", "api_key=super-secret-value"), "凭据");
        assertFailure(tools.edit("missing.txt", "a", "b"), "不存在");
        assertFailure(tools.edit(file.toString(), "missing", "x"), "未找到");
        assertFailure(tools.edit(file.toString(), "beta", "x"), "出现多次");
        assertFailure(tools.edit("../escape.txt", "x", "y"), "越出项目根");

        assertTrue(tools.edit(file.toString(), "alpha\n", null).contains("替换 1 处"));
        assertEquals("beta\nbeta\n", Files.readString(file));
        assertTrue(tools.edit(file.toString(), "beta\nbeta", "gamma\ndelta")
                .contains("行数 2 → 2"));
        assertEquals("gamma\ndelta\n", Files.readString(file));

        Path existingSecret = Files.writeString(project.resolve("existing-secret.txt"),
                "safe\npassword=super-secret-value\n");
        assertFailure(tools.edit(existingSecret.toString(), "safe", "still safe"), "仍包含疑似凭据");
    }

    @Test
    void insertValidatesContentAndLineRangeThenPreservesOrdering() throws Exception {
        Path file = Files.writeString(project.resolve("insert.txt"), "one\ntwo");
        assertFailure(tools.insert("missing.txt", 0, "x"), "不存在");
        assertFailure(tools.insert(file.toString(), -1, "x"), "行号越界");
        assertFailure(tools.insert(file.toString(), 3, "x"), "行号越界");
        assertFailure(tools.insert(file.toString(), 1, "token=super-secret-value"), "凭据");
        assertFailure(tools.insert("../escape.txt", 0, "x"), "越出项目根");

        assertTrue(tools.insert(file.toString(), 0, null).contains("插入 1 行"));
        assertTrue(tools.insert(file.toString(), 2, "middle-a\nmiddle-b").contains("插入 2 行"));
        assertEquals("\none\nmiddle-a\nmiddle-b\ntwo", Files.readString(file));

        Path existingSecret = Files.writeString(project.resolve("insert-secret.txt"),
                "password=super-secret-value");
        assertFailure(tools.insert(existingSecret.toString(), 0, "safe"), "仍包含疑似凭据");
    }

    @Test
    void buildDetectionCoversEverySupportedProjectMarker() throws Exception {
        assertDetect("pom.xml", "mvn -q -B compile", "mvn -q -B test");
        assertDetect("build.gradle", "gradle assemble -q", "gradle test -q");
        Path gradle = Files.createDirectory(project.resolve("gradle-wrapper"));
        Files.writeString(gradle.resolve("build.gradle.kts"), "plugins {}");
        Files.writeString(gradle.resolve("gradlew"), "#!/bin/sh");
        assertEquals("./gradlew assemble -q", CodeTools.autoDetect(gradle, false));
        assertEquals("./gradlew test -q", CodeTools.autoDetect(gradle, true));
        assertDetect("package.json", "npm run build", "npm test");
        assertDetect("go.mod", "go build ./...", "go test ./...");
        assertDetect("Cargo.toml", "cargo build", "cargo test");
        assertDetect("Makefile", "make", "make test");
        assertNull(CodeTools.autoDetect(Files.createDirectory(project.resolve("unknown")), false));
    }

    @Test
    void issueExtractionTruncatesDeduplicatesAndCapsOutput() {
        String longFailure = "ERROR " + "x".repeat(400);
        List<String> longResult = CodeTools.extractIssues(longFailure + "\n" + longFailure);
        assertEquals(1, longResult.size());
        assertEquals(301, longResult.getFirst().length());
        assertTrue(longResult.getFirst().endsWith("…"));

        StringBuilder failures = new StringBuilder("\nall good\n");
        for (int i = 0; i < 50; i++) failures.append("failure-").append(i).append('\n');
        assertEquals(40, CodeTools.extractIssues(failures.toString()).size());
        assertTrue(CodeTools.extractIssues("\nplain output\n").isEmpty());
    }

    @Test
    void strictIsolationRejectsEveryProcessBackedEntryAfterArgumentNormalization() {
        assertFailure(tools.build(null, -1), "严格项目文件隔离");
        assertFailure(tools.test("mvn test", 2_000), "严格项目文件隔离");
        assertFailure(tools.gitStatus(), "严格项目文件隔离");
        assertFailure(tools.gitDiff(null, false), "严格项目文件隔离");
        assertFailure(tools.gitDiff(" src ", true), "严格项目文件隔离");
        assertFailure(tools.gitLog(0, null), "严格项目文件隔离");
        assertFailure(tools.gitLog(999, " src "), "严格项目文件隔离");
        assertFailure(tools.gitCommit("message", false), "严格项目文件隔离");
        assertFailure(tools.gitCommit("message", true), "严格项目文件隔离");

        assertEquals("", CodeTools.firstToken(" "));
        assertEquals("mvn", CodeTools.firstToken(" /usr/local/bin/mvn test "));
        assertThrows(IllegalArgumentException.class,
                () -> CodeTools.parseCommand("mvn test | tee result"));
        assertThrows(IllegalArgumentException.class,
                () -> CodeTools.parseCommand("mvn 'unterminated"));
    }

    private void assertDetect(String marker, String build, String test) throws Exception {
        Path directory = Files.createDirectory(project.resolve("detect-" + marker.replace('.', '-')));
        Files.writeString(directory.resolve(marker), "marker");
        assertEquals(build, CodeTools.autoDetect(directory, false));
        assertEquals(test, CodeTools.autoDetect(directory, true));
    }

    private static void assertFailure(String response, String expected) {
        assertTrue(response.contains("失败"), response);
        assertTrue(response.contains(expected), response);
    }
}
