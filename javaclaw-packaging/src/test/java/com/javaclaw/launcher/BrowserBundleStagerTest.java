package com.javaclaw.launcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserBundleStagerTest {
    @TempDir
    Path temporary;

    @Test
    void stagesExactDriverRevisionsAndRecordsNativeBinariesInSbom() throws Exception {
        Path distribution = Files.createDirectory(temporary.resolve("distribution"));
        Path library = Files.createDirectory(distribution.resolve("lib"));
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        driver(library);
        component(cache, "chromium-1169", "chrome-linux/chrome");
        component(cache, "chromium_headless_shell-1169", "chrome-linux/headless_shell");
        component(cache, "ffmpeg-1011", "ffmpeg-linux");
        Files.writeString(
                cache.resolve("javaclaw-chromium-1169-credits.html"),
                "<html>" + "fixture notice ".repeat(20) + "</html>");
        BrowserBundleStager.stage(library, cache, "Linux", "amd64");
        var json = new ObjectMapper();
        var bundle = json.readTree(library.resolve("ms-playwright/bundle.json").toFile());
        assertEquals(3, bundle.path("components").size());
        assertEquals("linux", bundle.path("platform").asText());
        assertEquals(
                BrowserBundleStager.sha256(library.resolve("com.microsoft.playwright.driver-bundle.jar")),
                bundle.path("driverSha256").asText());
        assertTrue(Files.isExecutable(library.resolve("ms-playwright/chromium-1169/chrome-linux/chrome")));
        assertThrows(java.io.IOException.class, () -> BrowserBundleStager.stage(library, cache, "Linux", "amd64"));
        Files.writeString(distribution.resolve("sbom.json"), "{\"bomFormat\":\"CycloneDX\",\"components\":[]}");
        Files.writeString(distribution.resolve("THIRD-PARTY.txt"), "Java dependencies\n");
        BrowserBundleStager.augmentSbom(distribution);
        assertEquals(
                3,
                json.readTree(distribution.resolve("sbom.json").toFile())
                        .path("components")
                        .size());
        assertTrue(Files.readString(distribution.resolve("THIRD-PARTY.txt")).contains("Chromium-CREDITS.html"));
    }

    @Test
    void missingExactRevisionFailsBeforeCreatingDestinationAndDoesNotDownload() throws Exception {
        Path library = Files.createDirectory(temporary.resolve("lib"));
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        driver(library);
        component(cache, "chromium-9999", "chrome-linux/chrome");
        assertThrows(java.io.IOException.class, () -> BrowserBundleStager.stage(library, cache, "Linux", "amd64"));
        assertTrue(Files.notExists(library.resolve("ms-playwright")));
        assertEquals(
                temporary.resolve(".javaclaw/cache-v4/ms-playwright"), BrowserBundleStager.cache(Map.of(), temporary));
        assertThrows(
                IllegalArgumentException.class,
                () -> BrowserBundleStager.cache(Map.of("PLAYWRIGHT_BROWSERS_PATH", "0"), temporary));
    }

    private static void component(Path cache, String name, String executable) throws Exception {
        Path root = Files.createDirectory(cache.resolve(name));
        Path binary = root.resolve(executable);
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "synthetic native binary: " + name);
        assertTrue(binary.toFile().setExecutable(true));
        Files.writeString(root.resolve("INSTALLATION_COMPLETE"), "");
    }

    private static void driver(Path library) throws Exception {
        try (var jar = new JarOutputStream(
                Files.newOutputStream(library.resolve("com.microsoft.playwright.driver-bundle.jar")))) {
            jar.putNextEntry(new JarEntry("driver/linux/package/browsers.json"));
            jar.write("""
                    {"browsers":[{"name":"chromium","revision":"1169","browserVersion":"136.0.0.0"},
                    {"name":"chromium-headless-shell","revision":"1169","browserVersion":"136.0.0.0"},
                    {"name":"ffmpeg","revision":"1011"}]}
                    """.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("driver/linux/LICENSE"));
            jar.write("synthetic fixture license".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
