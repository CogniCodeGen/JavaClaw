package com.javaclaw.browser.worker;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserContracts.Operation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserDownloadTest {
    @Test
    void 点击生成的下载只接受内存内容并清理名称和释放字节() throws Exception {
        try (var fixture = new InteractiveActionFixture()) {
            byte[] payload = "download body".getBytes(StandardCharsets.UTF_8);
            fixture.fake().onDownload.accept(download("../folder\\report.txt", payload, new AtomicInteger()));
            var available = fixture.pages.snapshot(fixture.page).downloads().getFirst();
            assertFalse(available.fileName().contains("/"));
            assertFalse(available.fileName().contains("\\"));
            byte[] retained;
            try (var artifact = fixture.actions
                    .execute(InteractiveActionFixture.action(Operation.DOWNLOAD, available.downloadId()), new byte[0])
                    .orElseThrow()) {
                retained = artifact.bytes();
                assertArrayEquals(payload, retained);
                assertEquals(available.fileName(), artifact.file().fileName());
                assertEquals("application/octet-stream", artifact.file().mediaType());
            }
            assertArrayEquals(new byte[payload.length], retained);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.actions.execute(
                            InteractiveActionFixture.action(Operation.DOWNLOAD, "unknown"), new byte[0]));
        }
    }

    @Test
    void 下载数量和单文件大小受限且超额任务被取消() throws Exception {
        try (var fixture = new InteractiveActionFixture()) {
            AtomicInteger cancelled = new AtomicInteger();
            fixture.fake()
                    .onDownload
                    .accept(download("huge.bin", new byte[BrowserContracts.MAXIMUM_ARTIFACT_BYTES + 1], cancelled));
            String id =
                    fixture.pages.snapshot(fixture.page).downloads().getFirst().downloadId();
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.actions.execute(
                            InteractiveActionFixture.action(Operation.DOWNLOAD, id), new byte[0]));
            for (int index = 0; index < 8; index++) {
                fixture.fake().onDownload.accept(download("file.txt", new byte[0], cancelled));
            }
            assertEquals(8, fixture.pages.snapshot(fixture.page).downloads().size());
            assertEquals(1, cancelled.get());
            assertEquals("download.bin", InteractiveBrowserPages.fileName(null));
            assertEquals("download.bin", InteractiveBrowserPages.fileName(" "));
            assertEquals(200, InteractiveBrowserPages.fileName("a".repeat(250)).length());
        }
    }

    @Test
    void 链接下载通过私有Broker发送会话Cookie且保留响应文件类型() throws Exception {
        byte[] payload = "report".getBytes(StandardCharsets.UTF_8);
        try (var network = new InteractiveActionNetworkFixture(
                        200, Map.of("Content-Type", List.of("text/plain")), payload, false);
                var fixture = new InteractiveActionFixture(network.connection)) {
            fixture.fake().link = true;
            fixture.playwright.cookies.add(new Cookie("sid", "private-token"));
            try (var artifact = fixture.actions
                    .execute(fixture.element(Operation.DOWNLOAD_LINK, 0, ""), new byte[0])
                    .orElseThrow()) {
                assertArrayEquals(payload, artifact.bytes());
                assertEquals("report.txt", artifact.file().fileName());
                assertEquals("text/plain", artifact.file().mediaType());
                assertEquals(
                        List.of("sid=private-token"),
                        network.request().headers().get("cookie"));
                assertEquals("GET", network.request().method());
            }
        }
    }

    @Test
    void 无Cookie无类型的链接仍走私有网络并采用二进制文件类型() throws Exception {
        try (var network = new InteractiveActionNetworkFixture(204, Map.of(), new byte[0], false);
                var fixture = new InteractiveActionFixture(network.connection)) {
            fixture.fake().link = true;
            fixture.fake().linkUri = "https://docs.example.com/";
            try (var artifact = fixture.actions
                    .execute(fixture.element(Operation.DOWNLOAD_LINK, 0, ""), new byte[0])
                    .orElseThrow()) {
                assertEquals("download.bin", artifact.file().fileName());
                assertEquals("application/octet-stream", artifact.file().mediaType());
                assertEquals(0, artifact.bytes().length);
                assertTrue(network.request().headers().isEmpty());
            }
        }
    }

    @Test
    void 非成功和截断或超限链接响应都不能发布为下载() throws Exception {
        for (int status : List.of(199, 300)) {
            rejectedResponse(status, new byte[] {1}, false);
        }
        rejectedResponse(200, new byte[] {1}, true);
        rejectedResponse(200, new byte[BrowserContracts.MAXIMUM_ARTIFACT_BYTES + 1], false);
        try (var fixture = new InteractiveActionFixture()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.actions.execute(fixture.element(Operation.DOWNLOAD_LINK, 0, ""), new byte[0]));
            fixture.fake().link = true;
            fixture.fake().linkUri = "https://other.example.com/file";
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.actions.execute(fixture.element(Operation.DOWNLOAD_LINK, 0, ""), new byte[0]));
        }
    }

    private static void rejectedResponse(int status, byte[] body, boolean truncated) throws Exception {
        try (var network = new InteractiveActionNetworkFixture(status, Map.of(), body, truncated);
                var fixture = new InteractiveActionFixture(network.connection)) {
            fixture.fake().link = true;
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.actions.execute(fixture.element(Operation.DOWNLOAD_LINK, 0, ""), new byte[0]));
            assertEquals("GET", network.request().method());
        }
    }

    private static Download download(String name, byte[] body, AtomicInteger cancelled) {
        return InteractivePlaywrightFixture.proxy(Download.class, (target, method, args) -> switch (method.getName()) {
            case "suggestedFilename" -> name;
            case "createReadStream" -> new ByteArrayInputStream(body);
            case "cancel" -> {
                cancelled.incrementAndGet();
                yield null;
            }
            default -> null;
        });
    }
}
