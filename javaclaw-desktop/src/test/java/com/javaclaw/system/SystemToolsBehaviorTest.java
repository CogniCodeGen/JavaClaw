package com.javaclaw.system;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.util.ProjectAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemToolsBehaviorTest {

    private boolean previousConfirmationState;
    private Path testRoot;
    private String relativeRoot;
    private SystemTools tools;

    @BeforeEach
    void setUp() throws IOException {
        previousConfirmationState = ToolConfirmationManager.isEnabled();
        ToolConfirmationManager.setEnabled(false);
        Files.createDirectories(ProjectAccessPolicy.projectRoot().resolve("target"));
        testRoot = Files.createTempDirectory(
                ProjectAccessPolicy.projectRoot().resolve("target"), "system-tools-");
        relativeRoot = ProjectAccessPolicy.projectRoot().relativize(testRoot).toString();
        tools = new SystemTools(null, testRoot.resolve("screenshots"));
    }

    @AfterEach
    void tearDown() throws IOException {
        ToolConfirmationManager.setEnabled(previousConfirmationState);
        if (testRoot == null || !Files.exists(testRoot)) {
            return;
        }
        try (var paths = Files.walk(testRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void exposesReadOnlySystemFactsAndBlocksDesktopAutomation() {
        assertAll(
                () -> assertTrue(tools.getSystemInfo().contains("[成功]")),
                () -> assertTrue(tools.getSystemInfo().contains("允许访问的项目根")),
                () -> assertTrue(tools.getCurrentTime().contains("[成功]")),
                () -> assertTrue(tools.screenshot().contains("严格项目文件隔离")),
                () -> assertTrue(tools.mouseMove(10, 20).contains("严格项目文件隔离")),
                () -> assertTrue(tools.mouseClick("right", 2).contains("严格项目文件隔离")),
                () -> assertTrue(tools.mouseClickAt(10, 20).contains("严格项目文件隔离")),
                () -> assertTrue(tools.mouseScroll(-3).contains("严格项目文件隔离")),
                () -> assertTrue(tools.keyType("blocked").contains("严格项目文件隔离")),
                () -> assertTrue(tools.keyPress("ENTER").contains("严格项目文件隔离")),
                () -> assertTrue(tools.keyCombo("CTRL+C").contains("严格项目文件隔离")));
    }

    @Test
    void performsFileLifecycleInsideProjectBoundary() {
        String directory = relativeRoot + "/nested";
        String source = directory + "/source.txt";
        String copy = directory + "/copy.txt";
        String moved = relativeRoot + "/archive/moved.txt";
        String emptyDirectory = relativeRoot + "/empty";
        String copiedDirectory = relativeRoot + "/empty-copy";
        String movedDirectory = relativeRoot + "/empty-moved";

        assertTrue(tools.fileMkdir(directory).contains("[成功]"));
        assertTrue(tools.fileMkdir(emptyDirectory).contains("[成功]"));
        assertTrue(tools.fileWrite(source, "hello JavaClaw").contains("[成功]"));
        assertTrue(tools.fileWrite(relativeRoot + "/two-kilobytes.txt", "x".repeat(2048))
                .contains("[成功]"));

        String listing = tools.fileList(directory);
        assertTrue(listing.contains("[成功]") && listing.contains("source.txt"), listing);
        String mixedListing = tools.fileList(relativeRoot);
        assertTrue(mixedListing.contains("[目录]") && mixedListing.contains("[文件]"), mixedListing);
        assertTrue(tools.fileList(".").contains("[成功]"));
        String read = tools.fileRead(source);
        assertTrue(read.contains("[成功]") && read.contains("hello JavaClaw"), read);
        assertTrue(tools.fileRead(relativeRoot + "/two-kilobytes.txt").contains("2.0 KB"));

        assertTrue(tools.fileCopy(source, copy).contains("[成功]"));
        assertTrue(tools.fileMove(copy, moved).contains("[成功]"));
        assertTrue(Files.exists(testRoot.resolve("archive/moved.txt")));
        assertTrue(tools.fileCopy(emptyDirectory, copiedDirectory).contains("[成功]"));
        assertTrue(tools.fileMove(copiedDirectory, movedDirectory).contains("[成功]"));

        assertTrue(tools.fileDelete(moved).contains("[成功]"));
        assertFalse(Files.exists(testRoot.resolve("archive/moved.txt")));
        assertTrue(tools.fileDelete(relativeRoot + "/archive").contains("[成功]"));
        assertTrue(tools.fileDelete(source).contains("[成功]"));
        assertTrue(tools.fileDelete(directory).contains("[成功]"));
        assertTrue(tools.fileDelete(emptyDirectory).contains("[成功]"));
        assertTrue(tools.fileDelete(movedDirectory).contains("[成功]"));
    }

    @Test
    void reportsMissingAndStructurallyInvalidFileOperations() throws IOException {
        String missing = relativeRoot + "/missing.txt";
        String nonEmpty = relativeRoot + "/non-empty";
        Files.createDirectories(testRoot.resolve("non-empty"));
        Files.writeString(testRoot.resolve("non-empty/child.txt"), "content");

        assertAll(
                () -> assertTrue(tools.fileList(missing).contains("不是目录或不存在")),
                () -> assertTrue(tools.fileRead(missing).contains("文件不存在")),
                () -> assertTrue(tools.fileRead(nonEmpty).contains("路径不是文件")),
                () -> assertTrue(tools.fileDelete(missing).contains("路径不存在")),
                () -> assertTrue(tools.fileDelete(nonEmpty).contains("目录非空")),
                () -> assertTrue(tools.fileCopy(missing, relativeRoot + "/copy.txt")
                        .contains("源文件不存在")),
                () -> assertTrue(tools.fileMove(missing, relativeRoot + "/moved.txt")
                        .contains("源路径不存在")));
    }

    @Test
    void rejectsOversizedCredentialAndOutOfBoundaryContent() throws IOException {
        Path oversized = testRoot.resolve("oversized.txt");
        Path credential = testRoot.resolve("credential.txt");
        Files.writeString(oversized, "x".repeat(1024 * 1024 + 1));
        Files.writeString(credential, "password: RealSecret-2026!");

        String oversizedPath = relativeRoot + "/oversized.txt";
        String credentialPath = relativeRoot + "/credential.txt";
        assertAll(
                () -> assertTrue(tools.fileRead(oversizedPath).contains("文件过大")),
                () -> assertTrue(tools.fileCopy(oversizedPath, relativeRoot + "/oversized-copy.txt")
                        .contains("[成功]")),
                () -> assertTrue(tools.fileRead(credentialPath).contains("可能包含凭据")),
                () -> assertTrue(tools.fileWrite(relativeRoot + "/denied.txt",
                        "api_key: sk-live-RealSecret2026").contains("疑似凭据")),
                () -> assertTrue(tools.fileCopy(credentialPath, relativeRoot + "/copy.txt")
                        .contains("可能包含凭据")),
                () -> assertTrue(tools.fileMove(credentialPath, relativeRoot + "/moved.txt")
                        .contains("可能包含凭据")),
                () -> assertTrue(tools.fileRead("../outside.txt").contains("[失败]")),
                () -> assertTrue(tools.fileRead(" ").contains("[失败]")));
    }
}
