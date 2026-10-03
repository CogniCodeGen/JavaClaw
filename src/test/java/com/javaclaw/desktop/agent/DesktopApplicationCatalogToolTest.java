package com.javaclaw.desktop.agent;

import com.javaclaw.desktop.api.*;
import com.javaclaw.framework.spi.ToolContract;
import com.javaclaw.framework.spi.ToolEffectCapture;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DesktopApplicationCatalogToolTest {
    @TempDir Path temporary;
    private static final DesktopSessionOwner OWNER = new DesktopSessionOwner("workspace", "run", "chat", "message");

    @Test void launchAdmissionUsesCanonicalIdentityAcrossAliasesWithoutNativeCalls() {
        var apps = List.of(new DesktopApplicationInfo("Reader", "阅读器", "com.example.reader",
                "Reader Launch", List.of("Reader Alias")),
                new DesktopApplicationInfo("Notes", "笔记", "com.example.notes", "Notes", List.of()));
        var tool = tools(new DesktopApplicationCatalog(apps, false));
        assertEquals("desktop.application:unknown", tool.launchResourceKey("Reader"));
        tool.applications(0, 1, "Reader");
        for (String name : List.of("Reader", "Ｒｅａｄｅｒ", "Reader Alias", "Reader Launch", "com.example.reader"))
            assertEquals("desktop.application:com.example.reader", tool.launchResourceKey(name));
        assertEquals("desktop.application:com.example.notes", tool.launchResourceKey("Notes"));
        assertEquals("desktop.application:unknown", tool.launchResourceKey("guess"));
        var partial = tools(new DesktopApplicationCatalog(apps, true));
        partial.applications(0, 64, null);
        assertEquals("desktop.application:unknown", partial.launchResourceKey("Reader Alias"));
    }

    @Test void installedMetadataIsReadOnlyBoundedAndOwnerScoped() throws Exception {
        var apps = IntStream.range(0, 70).mapToObj(index -> new DesktopApplicationInfo(
                "Reader " + index, "阅读器 " + index, "reader" + index + ".exe",
                "阅读器 " + index, List.of("Reader " + index))).toList();
        var tool = tools(new DesktopApplicationCatalog(apps, true));
        var contract = DesktopSessionTools.class.getMethod("applications", Integer.class, Integer.class, String.class)
                .getAnnotation(ToolContract.class);
        assertTrue(contract.idempotent());
        assertEquals(List.of("tool.read"), List.of(contract.permissions()));
        try (var capture = ToolEffectCapture.begin("desktop_session_applications")) {
            String display = tool.applications(null, null);
            var data = capture.data();
            assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
            assertEquals("computer-use", data.path("protocol").asText());
            assertEquals("desktop.applications", data.path("kind").asText());
            assertEquals(64, data.path("count").asInt());
            assertEquals(64, data.path("nextOffset").asInt());
            assertTrue(data.path("truncated").asBoolean());
            assertEquals("阅读器 0", data.path("applications").get(0).path("launchName").asText());
            assertTrue(data.toString().length() < 20_000);
            assertTrue(display.length() < 200, "display text must not duplicate the catalog JSON");
            assertFalse(display.contains("launchName"));
            assertEquals(64, data.path("catalogId").asText().length());
        }
        try (var capture = ToolEffectCapture.begin("desktop_session_applications")) {
            tool.applications(64, 64);
            assertEquals(6, capture.data().path("count").asInt());
            assertFalse(capture.data().path("hasMore").asBoolean());
            assertFalse(capture.data().has("nextOffset"));
        }
        try (var capture = ToolEffectCapture.begin("desktop_session_applications")) {
            tool.applications(0, 65);
            assertEquals(ToolEffectCapture.Signal.ERROR, capture.signal());
            assertEquals("INVALID_ARGUMENTS", capture.data().path("errorCode").asText());
        }
    }

    @Test void queryMatchesAliasesAndPaginatesTheFilteredSnapshotWithoutChangingLaunchNames() {
        var apps = List.of(new DesktopApplicationInfo("Mail", "邮件", "mail.exe", "邮件", List.of("MAIL")),
                new DesktopApplicationInfo("Team Alpha", "会议甲", "alpha.exe", "会议甲", List.of("Meeting Alpha")),
                new DesktopApplicationInfo("Team Beta", "会议乙", "com.example.beta", "com.example.beta", List.of("Meeting Beta")),
                new DesktopApplicationInfo("Team Gamma", "会议丙", "gamma.exe", "会议丙", List.of("Meeting Gamma")));
        var catalog = new DesktopApplicationCatalog(apps, false);
        var first = DesktopToolPayloads.applications(catalog, 0, 2, "ｍｅｅｔｉｎｇ");
        assertEquals(4, first.path("catalogTotalCount").asInt());
        assertEquals(3, first.path("totalCount").asInt());
        assertEquals(2, first.path("nextOffset").asInt());
        assertEquals("会议甲", first.path("applications").get(0).path("launchName").asText());
        assertEquals("com.example.beta", first.path("applications").get(1).path("launchName").asText());
        var second = DesktopToolPayloads.applications(catalog, 2, 2, "ｍｅｅｔｉｎｇ");
        assertEquals(1, second.path("count").asInt());
        assertEquals("会议丙", second.path("applications").get(0).path("launchName").asText());
        assertFalse(second.path("hasMore").asBoolean());
        assertEquals(first.path("catalogId"), second.path("catalogId"));
        assertEquals(first.path("catalogId"), DesktopToolPayloads.applications(catalog, 0, 2, "MAIL").path("catalogId"));
        assertThrows(IllegalArgumentException.class, () -> DesktopToolPayloads.applications(catalog, 4, 2, "meeting"));
    }

    @Test void applicationCatalogToolSchemaExposesOptionalQueryAndRejectsUnboundedQueries() throws Exception {
        var tool = tools(new DesktopApplicationCatalog(List.of(new DesktopApplicationInfo(
                "Meeting", "会议", "meeting.exe", "会议", List.of("会議"))), false));
        try (var capture = ToolEffectCapture.begin("desktop_session_applications")) {
            String display = tool.applications(0, 64, "会議");
            assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
            assertEquals(1, capture.data().path("count").asInt());
            assertEquals("会议", capture.data().path("applications").get(0).path("launchName").asText());
            assertTrue(display.length() < 200);
        }
        try (var capture = ToolEffectCapture.begin("desktop_session_applications")) {
            tool.applications(0, 64, "x".repeat(257));
            assertEquals(ToolEffectCapture.Signal.ERROR, capture.signal());
            assertEquals("INVALID_ARGUMENTS", capture.data().path("errorCode").asText());
        }
    }

    @Test void budgetDropsWholeEntriesAndKeepsAnExactResumeOffset() {
        var apps = IntStream.range(0, 64).mapToObj(index -> new DesktopApplicationInfo(
                "Reader " + index, "阅读器", "reader" + index + ".exe", "reader" + index + ".exe",
                IntStream.range(0, 16).mapToObj(alias -> "界".repeat(80)).toList())).toList();
        var data = DesktopToolPayloads.applications(new DesktopApplicationCatalog(apps, false), 0, 64);
        assertTrue(data.toString().length() < 12_000);
        assertTrue(data.path("count").asInt() > 0 && data.path("count").asInt() < 64);
        assertEquals(data.path("count").asInt(), data.path("nextOffset").asInt());
        data.path("applications").forEach(app -> {
            assertTrue(app.path("launchName").asText().endsWith(".exe"));
            assertEquals(80, app.path("aliases").get(0).asText().length());
        });
    }

    @Test void anIdentityThatCannotFitIntactFailsInsteadOfReturningAZeroProgressPage() {
        var small = new DesktopApplicationInfo("Small", "小应用", "small.exe", "小应用", List.of());
        var large = new DesktopApplicationInfo("Meeting", "会议", "meeting.exe", "会议",
                IntStream.range(0, 16).mapToObj(index -> "metadata".repeat(16) + index).toList());
        var catalog = new DesktopApplicationCatalog(List.of(small, large), false);
        var first = DesktopToolPayloads.applications(catalog, 0, 64, null, 1200);
        assertEquals(1, first.path("count").asInt());
        assertEquals(1, first.path("nextOffset").asInt());
        assertTrue(first.path("hasMore").asBoolean());
        assertTrue(first.toString().length() <= 1200);
        var failure = assertThrows(DesktopToolPayloads.CatalogMessageBudgetException.class,
                () -> DesktopToolPayloads.applications(catalog, 1, 64, null, 1200));
        assertTrue(failure.getMessage().contains("LOCAL_CONTEXT_BUDGET_EXCEEDED"));
        assertTrue(failure.getMessage().contains("offset=1"));
        assertEquals("会议", large.launchName(), "failure must never shorten the identity to make it fit");
        var finished = DesktopToolPayloads.applications(catalog, 2, 64, null, 1200);
        assertEquals(0, finished.path("count").asInt());
        assertFalse(finished.path("hasMore").asBoolean());
        assertFalse(finished.has("nextOffset"));
    }

    private DesktopSessionTools tools(DesktopApplicationCatalog catalog) {
        var service = (DesktopSessionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopSessionService.class}, (proxy, method, args) -> {
                    assertEquals("discoverApplications", method.getName());
                    assertEquals(OWNER, args[0]);
                    return CompletableFuture.completedFuture(catalog);
                });
        return new DesktopSessionTools(service, OWNER, temporary);
    }
}
