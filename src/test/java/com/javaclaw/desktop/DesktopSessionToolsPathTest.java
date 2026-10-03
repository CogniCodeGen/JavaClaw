package com.javaclaw.desktop;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.agent.vision.VisionPreprocessor;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopObservation;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.platform.data.DataRoot;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSessionToolsPathTest {
    @TempDir Path temporary;

    @Test
    void observeShowsGenericPlatformElementDiagnosticsToTheModel() {
        DesktopFrame frame = new DesktopFrame("target", 1, 1234, 1, 1, 4,
                new byte[] { 0, 0, 0, (byte) 255 });
        DesktopTarget target = new DesktopTarget("macos", "target", 1,
                "示例应用", "窗口", 0, 0, 1, 1, DesktopTarget.VISIBLE);
        DesktopObservation observation = new DesktopObservation("session", "observation-1",
                frame, List.of(), List.of(), "AX catalog unavailable\nstatus=unsupported");
        DesktopSessionService service = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "captureObservation" -> CompletableFuture.completedFuture(Optional.of(observation));
                    case "info" -> new DesktopSessionInfo("session", target, true, false);
                    case "commitObservation" -> CompletableFuture.completedFuture(true);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ModelTaskGateway model = request -> CompletableFuture.completedFuture(
                new ModelTaskResult(JsonNodeFactory.instance.objectNode()
                        .put("summary", "窗口可见")
                        .put("visibleText", "")
                        .set("targets", JsonNodeFactory.instance.arrayNode()),
                        "fixture", 1, 1, false, Map.of()));
        DesktopSessionTools tools = new DesktopSessionTools(service,
                new DesktopSessionOwner("workspace", "scope", "test", "source"),
                temporary, null, new VisionPreprocessor(model, RunId.random()));

        String result = tools.observe("session", "查看窗口", false);
        assertTrue(result.contains("[成功]"), result);
        assertTrue(result.contains("辅助功能目录诊断（平台状态，不是应用内容）："
                + "AX catalog unavailable status=unsupported"), result);
    }

    @Test
    void snapshotRejectsSymlinkedScreenshotDirectoryAtWriteTime() throws Exception {
        DataRoot data = new DataRoot(temporary.resolve("data")).prepare();
        Path screenshots = Files.createDirectories(data.path().resolve("screenshots"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.createSymbolicLink(screenshots.resolve("workspace"), outside);

        DesktopSessionService service = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("snapshot")) {
                        return CompletableFuture.completedFuture(Optional.of(new DesktopFrame(
                                "target", 1, 1, 1, 1, 4,
                                new byte[] { 0, 0, 0, (byte) 255 })));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DesktopSessionTools tools = new DesktopSessionTools(service,
                new DesktopSessionOwner("workspace", "scope", "test", "source"),
                screenshots.resolve("workspace"), data);

        String denied = tools.snapshot("session");
        assertTrue(denied.contains("[失败]"), denied);
        try (var entries = Files.list(outside)) {
            assertTrue(entries.findAny().isEmpty());
        }

        Files.delete(screenshots.resolve("workspace"));
        String saved = tools.snapshot("session");
        assertTrue(saved.contains("[成功]"), saved);
        try (var entries = Files.list(screenshots.resolve("workspace"))) {
            assertEquals(1, entries.filter(path -> path.toString().endsWith(".png")).count());
        }
    }
}
