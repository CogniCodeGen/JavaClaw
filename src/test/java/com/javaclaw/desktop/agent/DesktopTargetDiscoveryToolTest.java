package com.javaclaw.desktop.agent;

import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.framework.spi.ToolContract;
import com.javaclaw.framework.spi.ToolEffectCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DesktopTargetDiscoveryToolTest {
    @TempDir Path temporary;
    private static final DesktopSessionOwner OWNER = new DesktopSessionOwner("workspace", "run", "chat", "message");

    @Test void aLargeDiscoveryHasBoundedPagesAndAnExactResumeOffsetWithoutDuplicatingRowsInDisplay() {
        var targets = IntStream.range(0, 165).mapToObj(index -> target("window-" + index, "QQ",
                "com.tencent.qq", "联系人 " + index)).toList();
        DesktopSessionTools tools = tools(targets);
        var seen = new HashSet<String>();
        int offset = 0;
        String inventoryId = "";
        do {
            try (var capture = ToolEffectCapture.begin("desktop_session_targets")) {
                String display = tools.targets(null, offset, 32);
                var data = capture.data();
                assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
                assertTrue(data.toString().length() <= 8_000);
                assertEquals(165, data.path("totalCount").asInt());
                assertEquals(165, data.path("inventoryTotalCount").asInt());
                assertEquals(data.path("count").asInt(), data.path("targets").size());
                assertTrue(data.path("count").asInt() > 0);
                assertFalse(data.path("complete").asBoolean());
                assertTrue(data.path("truncated").asBoolean());
                assertFalse(data.path("inputAuthority").asBoolean());
                assertFalse(data.path("freshObservation").asBoolean());
                assertTrue(display.length() < 400, "human summary must not duplicate the full discovery");
                assertFalse(display.contains("window-"));
                if (inventoryId.isEmpty()) inventoryId = data.path("inventoryId").asText();
                assertEquals(inventoryId, data.path("inventoryId").asText());
                for (var row : data.path("targets")) assertTrue(seen.add(row.path("targetId").asText()));
                if (!data.path("hasMore").asBoolean()) {
                    assertFalse(data.has("nextOffset"));
                    break;
                }
                assertEquals(offset + data.path("count").asInt(), data.path("nextOffset").asInt());
                offset = data.path("nextOffset").asInt();
            }
        } while (true);
        assertEquals(165, seen.size());
    }

    @Test void anApplicationQueryMatchesOwnershipAndNeverMatchesAnotherApplicationsWindowTitle() {
        var targets = List.of(target("status", "控制中心", "com.apple.controlcenter", "QQ com.tencent.qq"),
                target("qq", "QQ", "com.tencent.qq", "联系人"),
                target("notes", "Notes", "com.apple.notes", "QQ"));
        var filtered = DesktopToolPayloads.targets(targets, 0, 16, "ｑｑ");
        assertEquals(1, filtered.path("totalCount").asInt());
        assertEquals("qq", filtered.path("targets").get(0).path("targetId").asText());
        assertTrue(filtered.path("complete").asBoolean());
        assertFalse(filtered.path("truncated").asBoolean());
        assertFalse(filtered.path("hasMore").asBoolean());
        assertEquals(filtered.path("inventoryId"), DesktopToolPayloads.targets(targets, 0, 16,
                "com.tencent.qq").path("inventoryId"));
        var absent = DesktopToolPayloads.targets(targets, 0, 16, "Missing");
        assertEquals(0, absent.path("count").asInt());
        assertTrue(absent.path("complete").asBoolean());
        assertFalse(absent.path("hasMore").asBoolean());
    }

    @Test void displayLabelsAreUnicodeSafeAndBoundedWhileNativeIdentitiesRemainExact() {
        String id = "id-" + "界".repeat(128);
        String ownerId = "com.example." + "identity".repeat(30);
        String title = "😀\"\n".repeat(3_000);
        var value = new DesktopTarget("provider", id, 42, "😀".repeat(1_000), title,
                0, 0, 800, 600, DesktopTarget.VISIBLE, ownerId, "parent", DesktopTarget.NATIVE_PARENT);
        var data = DesktopToolPayloads.targets(List.of(value), 0, 16, null);
        var row = data.path("targets").get(0);
        assertEquals(id, row.path("targetId").asText());
        assertEquals(ownerId, row.path("applicationId").asText());
        assertEquals("parent", row.path("parentTargetId").asText());
        assertEquals("provider", row.path("providerId").asText());
        assertEquals(256, row.path("title").asText().codePointCount(0, row.path("title").asText().length()));
        assertEquals(128, row.path("application").asText().codePointCount(0, row.path("application").asText().length()));
        assertTrue(row.path("titleTruncated").asBoolean());
        assertTrue(row.path("applicationTruncated").asBoolean());
        assertTrue(data.toString().length() <= 8_000);
        assertEquals("😀".repeat(128), row.path("application").asText());
        assertEquals(title, value.title(), "native observations must not be rewritten");
    }

    @Test void oversizedNativeIdentitiesFailIntactInsteadOfInventingAnIdOrReturningAZeroProgressPage() {
        var small = target("small", "QQ", "com.tencent.qq", "QQ");
        var huge = target("identity-" + "x".repeat(4_000), "QQ", "com.tencent.qq", "QQ");
        var first = DesktopToolPayloads.targets(List.of(small, huge), 0, 16, null, 1_500);
        assertEquals(1, first.path("count").asInt());
        assertEquals(1, first.path("nextOffset").asInt());
        var failure = assertThrows(DesktopToolPayloads.TargetMessageBudgetException.class,
                () -> DesktopToolPayloads.targets(List.of(small, huge), 1, 16, null, 1_500));
        assertTrue(failure.getMessage().contains("offset=1"));
        assertEquals(4_009, huge.id().length());
    }

    @Test void oneReadOnlyToolEntrySupportsEmptyArgumentsAndRejectsInvalidPagination() throws Exception {
        var method = DesktopSessionTools.class.getMethod("targets", String.class, Integer.class, Integer.class);
        assertNotNull(method.getAnnotation(Tool.class));
        var contract = method.getAnnotation(ToolContract.class);
        assertTrue(contract.idempotent());
        assertEquals(List.of("tool.read"), List.of(contract.permissions()));
        for (var parameter : method.getParameters()) assertFalse(parameter.getAnnotation(ToolParam.class).required());
        assertNull(DesktopSessionTools.class.getMethod("targets").getAnnotation(Tool.class));
        var tools = tools(List.of(target("qq", "QQ", "com.tencent.qq", "联系人")));
        try (var capture = ToolEffectCapture.begin("desktop_session_targets")) {
            tools.targets();
            assertEquals(1, capture.data().path("count").asInt());
            assertTrue(capture.data().path("complete").asBoolean());
        }
        for (Runnable invalid : List.<Runnable>of(() -> tools.targets(null, -1, 16),
                () -> tools.targets(null, 0, 33), () -> tools.targets("x".repeat(257), 0, 16),
                () -> tools.targets("Q\nQ", 0, 16))) {
            try (var capture = ToolEffectCapture.begin("desktop_session_targets")) {
                invalid.run();
                assertEquals(ToolEffectCapture.Signal.ERROR, capture.signal());
                assertEquals("INVALID_ARGUMENTS", capture.data().path("errorCode").asText());
            }
        }
        var changed = new ArrayList<>(List.of(target("qq", "QQ", "com.tencent.qq", "联系人")));
        String oldInventory = DesktopToolPayloads.targets(changed).path("inventoryId").asText();
        changed.add(target("second", "Notes", "com.apple.notes", "笔记"));
        assertNotEquals(oldInventory, DesktopToolPayloads.targets(changed).path("inventoryId").asText());
    }

    private DesktopSessionTools tools(List<DesktopTarget> targets) {
        var service = (DesktopSessionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopSessionService.class}, (proxy, method, args) -> {
                    assertEquals("discoverTargets", method.getName());
                    assertEquals(OWNER, args[0]);
                    return CompletableFuture.completedFuture(targets);
                });
        return new DesktopSessionTools(service, OWNER, temporary);
    }

    private static DesktopTarget target(String id, String application, String applicationId, String title) {
        return new DesktopTarget("test", id, 42, application, title, 0, 0, 800, 600,
                DesktopTarget.VISIBLE, applicationId);
    }
}
