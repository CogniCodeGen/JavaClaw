package com.javaclaw.desktop.golden;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.InterfaceDensity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementCenterGoldenIT {
    private static final String UPDATE_PROPERTY = "javaclaw.update.ui.golden";
    private static final Path MODULE_ROOT =
            Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
    private static final Path REFERENCE_DIRECTORY = MODULE_ROOT
            .resolve("../docs/images/screenshots/settings-center/macos-reference")
            .normalize();
    private static final Path ARTIFACT_DIRECTORY =
            MODULE_ROOT.resolve("target/ui-golden").resolve(platform());

    @Test
    void 九主题三密度和两个窗口规格形成完整可审阅矩阵() throws IOException {
        List<UiGoldenCase> cases = UiGoldenCase.matrix();
        boolean updating = Boolean.getBoolean(UPDATE_PROPERTY);
        String referenceBefore =
                updating ? "" : Files.readString(REFERENCE_DIRECTORY.resolve(UiGoldenManifest.FILE_NAME));
        assertMatrix(cases);
        prepareArtifactDirectory();
        try (GoldenAsyncFailures failures = new GoldenAsyncFailures()) {
            renderArtifacts(cases);
        }
        writeManifest(ARTIFACT_DIRECTORY, platform(), cases);
        if (updating) {
            updateMacOsReferences(cases);
        }
        verifyReferences(cases);
        assertRenderedArtifactsMatchReferences(cases);
        if (!updating) {
            assertEquals(
                    referenceBefore,
                    Files.readString(REFERENCE_DIRECTORY.resolve(UiGoldenManifest.FILE_NAME)),
                    "常规 verify 不得改写仓库参考图清单");
        }
    }

    private static void assertMatrix(List<UiGoldenCase> cases) {
        int expected =
                AppearanceTheme.values().length * InterfaceDensity.values().length * GoldenViewport.values().length;
        assertEquals(54, expected);
        assertEquals(expected, cases.size());
        assertEquals(
                expected, cases.stream().map(UiGoldenCase::fileName).distinct().count());
        assertTrue(cases.stream()
                .allMatch(golden -> golden.fileName()
                        .matches("settings-center--theme-[a-z]+--density-[a-z]+--viewport-(minimum|standard)\\.png")));
    }

    private static void renderArtifacts(List<UiGoldenCase> cases) throws IOException {
        ManagementCenterGoldenFixture fixture = new ManagementCenterGoldenFixture();
        AtomicReference<List<String>> navigationEntries = new AtomicReference<>();
        for (UiGoldenCase golden : cases) {
            var rendered = fixture.render(golden);
            WritableImage image = rendered.image();
            navigationEntries.compareAndSet(null, rendered.navigationEntries());
            assertEquals(navigationEntries.get(), rendered.navigationEntries(), "主题与密度不能改变生产导航目录");
            GoldenPng.write(image, ARTIFACT_DIRECTORY.resolve(golden.fileName()));
            assertEquals(golden.viewport().width(), (int) image.getWidth());
            assertEquals(golden.viewport().height(), (int) image.getHeight());
        }
        assertProductionNavigation(navigationEntries.get());
    }

    private static void assertProductionNavigation(List<String> entries) {
        assertEquals(30, entries.size(), "Golden 必须读取全部生产管理入口，不能使用手写示意目录");
        for (String title : List.of(
                "外观",
                "模型服务",
                "Agent",
                "权限方案",
                "MCP 外部工具",
                "网站会话",
                "工作区",
                "编程环境",
                "后台任务",
                "工作流",
                "定时任务",
                "知识库",
                "技能",
                "诊断")) {
            assertTrue(entries.stream().anyMatch(entry -> entry.contains("title=" + title)), () -> "导航缺少：" + title);
        }
    }

    private static void prepareArtifactDirectory() throws IOException {
        Files.createDirectories(ARTIFACT_DIRECTORY);
        try (var files = Files.list(ARTIFACT_DIRECTORY)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    private static void updateMacOsReferences(List<UiGoldenCase> cases) throws IOException {
        assertEquals("macos", platform(), "参考图只能由 macOS 显式更新");
        Files.createDirectories(REFERENCE_DIRECTORY);
        removeExistingReferenceImages();
        for (UiGoldenCase golden : cases) {
            Files.copy(
                    ARTIFACT_DIRECTORY.resolve(golden.fileName()),
                    REFERENCE_DIRECTORY.resolve(golden.fileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        writeManifest(REFERENCE_DIRECTORY, "macos-reference", cases);
    }

    private static void removeExistingReferenceImages() throws IOException {
        try (var files = Files.list(REFERENCE_DIRECTORY)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".png"))
                    .toList()) {
                Files.delete(file);
            }
        }
    }

    private static void verifyReferences(List<UiGoldenCase> cases) throws IOException {
        Set<String> expected = cases.stream().map(UiGoldenCase::fileName).collect(Collectors.toUnmodifiableSet());
        Set<String> actual;
        try (var files = Files.list(REFERENCE_DIRECTORY)) {
            actual = files.filter(path -> path.getFileName().toString().endsWith(".png"))
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
        }
        assertEquals(expected, actual, "macOS 参考截图矩阵必须完整且不能混入旧文件");
        assertReferenceDimensions(cases);
        String expectedManifest = UiGoldenManifest.create("macos-reference", REFERENCE_DIRECTORY, cases);
        assertEquals(expectedManifest, Files.readString(REFERENCE_DIRECTORY.resolve(UiGoldenManifest.FILE_NAME)));
    }

    private static void assertReferenceDimensions(List<UiGoldenCase> cases) throws IOException {
        for (UiGoldenCase golden : cases) {
            GoldenPng.Dimensions dimensions = GoldenPng.readDimensions(REFERENCE_DIRECTORY.resolve(golden.fileName()));
            assertEquals(golden.viewport().width(), dimensions.width());
            assertEquals(golden.viewport().height(), dimensions.height());
        }
    }

    private static void assertRenderedArtifactsMatchReferences(List<UiGoldenCase> cases) throws IOException {
        if (!"macos".equals(platform())) {
            return;
        }
        for (UiGoldenCase golden : cases) {
            Path artifact = ARTIFACT_DIRECTORY.resolve(golden.fileName());
            Path reference = REFERENCE_DIRECTORY.resolve(golden.fileName());
            assertEquals(-1L, Files.mismatch(artifact, reference), () -> "UI Golden 像素变化：" + golden.fileName());
        }
    }

    private static void writeManifest(Path directory, String platformName, List<UiGoldenCase> cases)
            throws IOException {
        Files.writeString(
                directory.resolve(UiGoldenManifest.FILE_NAME), UiGoldenManifest.create(platformName, directory, cases));
    }

    private static String platform() {
        String name = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (name.contains("mac")) {
            return "macos";
        }
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("linux")) {
            return "linux";
        }
        return "other";
    }
}
