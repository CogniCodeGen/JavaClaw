package com.javaclaw.browser.client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.BrowserContracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserClientTest {
    @Test
    void 同一线程跨Turn保留进程并拒绝退休租约晚到网络() {
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        BrowserContracts.OpenTask task = task();
        CancellationSource firstTurn = new CancellationSource();
        try (BrowserWorkerClient client = client(launches, Duration.ofSeconds(3));
                BrowserActionResult opened = client.openInteractive(task, new byte[0], network(requests), firstTurn)) {
            String process = opened.observation().page().text();
            assertEquals(1, requests.get());
            BrowserContracts.AccessLease next = lease(BrowserContracts.ControlMode.ASSISTANT, 2);
            client.updateInteractiveLease(task.sessionId(), next, new CancellationSource());
            assertEquals(1, requests.get(), "旧控制代次的反向网络不得交给Broker");
            firstTurn.cancel("previous Turn ended");
            try (BrowserActionResult nextPage = client.actInteractive(
                    task.sessionId(),
                    new BrowserContracts.Action(
                            BrowserContracts.Operation.NAVIGATE,
                            BrowserContracts.Target.current(),
                            BrowserContracts.ActionInput.text("https://docs.example.com/next")),
                    new byte[0],
                    new CancellationSource())) {
                assertEquals(process, nextPage.observation().page().text());
                assertEquals("/next", nextPage.observation().page().uri().getPath());
            }
            assertEquals(1, launches.get());
            assertEquals(2, requests.get());
            assertEquals(
                    BrowserContracts.SessionState.CLOSED,
                    client.closeInteractive(task.sessionId()).state());
        }
    }

    @Test
    void 无租约不转发网络且截图和私有凭据数组均有独立生命周期() {
        AtomicInteger requests = new AtomicInteger();
        BrowserContracts.OpenTask task = task();
        AtomicReference<byte[]> captured = new AtomicReference<>();
        try (BrowserWorkerClient client = client(new AtomicInteger(), Duration.ofSeconds(3));
                BrowserActionResult ignored =
                        client.openInteractive(task, new byte[0], network(requests), new CancellationSource())) {
            client.updateInteractiveLease(
                    task.sessionId(), lease(BrowserContracts.ControlMode.NONE, 2), new CancellationSource());
            assertThrows(
                    com.javaclaw.api.TurnCancelledException.class,
                    () -> client.actInteractive(
                            task.sessionId(),
                            BrowserContracts.Action.simple(BrowserContracts.Operation.SNAPSHOT),
                            new byte[0],
                            new CancellationSource()));
            assertEquals(1, requests.get(), "无租约宿主必须拒绝动作及网络");
            client.updateInteractiveLease(
                    task.sessionId(), lease(BrowserContracts.ControlMode.ASSISTANT, 3), new CancellationSource());
            try (BrowserActionResult screenshot = client.actInteractive(
                    task.sessionId(),
                    BrowserContracts.Action.simple(BrowserContracts.Operation.SCREENSHOT),
                    new byte[0],
                    new CancellationSource())) {
                byte[] detached = screenshot.content();
                detached[0] = 9;
                assertArrayEquals(new byte[] {1, 2, 3}, screenshot.content());
                assertEquals(3, screenshot.observation().frame().orElseThrow().controlGeneration());
            }
            client.updateInteractiveLease(
                    task.sessionId(), lease(BrowserContracts.ControlMode.HUMAN, 4), new CancellationSource());
            assertEquals(
                    "saved",
                    client.captureInteractiveCredentials(
                            task.sessionId(),
                            client.interactiveStatus(task.sessionId()).lease(),
                            URI.create("https://docs.example.com"),
                            new BrowserContracts.CredentialsTarget("page-1", "user", "password"),
                            bytes -> {
                                captured.set(bytes);
                                assertEquals(
                                        "alice\0secret", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                                return "saved";
                            }));
            assertArrayEquals(new byte[12], captured.get(), "私有回调返回后原数组立即清空");
            boolean hasIndexedDb = client.saveInteractiveState(
                    task.sessionId(),
                    bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("indexedDB"));
            assertTrue(hasIndexedDb);
        }
    }

    @Test
    void 未知能力不启动进程且超时不重试业务动作() {
        AtomicInteger launched = new AtomicInteger();
        try (BrowserWorkerClient disabled = new BrowserWorkerClient(command(launched), Duration.ofSeconds(1))) {
            assertFalse(disabled.interactiveAvailable());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> disabled.openInteractive(
                            task(), new byte[0], network(new AtomicInteger()), new CancellationSource()));
            assertEquals(0, launched.get());
        }
        BrowserContracts.OpenTask task = task();
        try (BrowserWorkerClient client = client(launched, Duration.ofSeconds(1));
                BrowserActionResult ignored = client.openInteractive(
                        task, new byte[0], network(new AtomicInteger()), new CancellationSource())) {
            assertThrows(
                    BrowserWorkerException.class,
                    () -> client.actInteractive(
                            task.sessionId(),
                            BrowserContracts.Action.simple(BrowserContracts.Operation.WAIT),
                            new byte[0],
                            new CancellationSource()));
            assertEquals(1, launched.get());
            assertEquals(
                    BrowserContracts.SessionState.CLOSED,
                    client.interactiveStatus(task.sessionId()).state());
        }
    }

    @Test
    void 旧动作晚回执不能把刚发布的新控制代次回退() throws Exception {
        CountDownLatch networkStarted = new CountDownLatch(1);
        CountDownLatch releaseNetwork = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        var task = task();
        InteractiveBrowserNetworkExchange exchange = (request, body, cancellation) -> {
            if (requests.incrementAndGet() == 2) {
                networkStarted.countDown();
                if (!releaseNetwork.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("fixture gate timed out");
                }
            }
            cancellation.throwIfCancelled();
            return new BrowserNetworkResult(
                    new BrowserWorkerProtocol.NetworkResponse(200, Map.of(), false), new byte[0]);
        };
        try (BrowserWorkerClient client = client(new AtomicInteger(), Duration.ofSeconds(4));
                BrowserActionResult ignored =
                        client.openInteractive(task, new byte[0], exchange, new CancellationSource())) {
            var old = CompletableFuture.runAsync(() -> {
                try (BrowserActionResult read = client.actInteractive(
                        task.sessionId(),
                        task.lease(),
                        BrowserContracts.Action.simple(BrowserContracts.Operation.SNAPSHOT),
                        new byte[0],
                        new CancellationSource())) {}
            });
            assertTrue(networkStarted.await(2, TimeUnit.SECONDS));
            var next = lease(BrowserContracts.ControlMode.HUMAN, 2);
            var updated = CompletableFuture.supplyAsync(
                    () -> client.updateInteractiveLease(task.sessionId(), next, new CancellationSource()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (client.interactiveStatus(task.sessionId()).lease().generation() != 2
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(2, client.interactiveStatus(task.sessionId()).lease().generation());
            releaseNetwork.countDown();
            assertThrows(CompletionException.class, old::join);
            assertEquals(next, updated.get(3, TimeUnit.SECONDS).lease());
            assertEquals(next, client.interactiveStatus(task.sessionId()).lease());
        } finally {
            releaseNetwork.countDown();
        }
    }

    @Test
    void 私有状态保存不能解除原Turn的后台网络取消信号() {
        AtomicInteger requests = new AtomicInteger();
        var task = task();
        var turn = new CancellationSource();
        try (BrowserWorkerClient client = client(new AtomicInteger(), Duration.ofSeconds(3));
                BrowserActionResult ignored = client.openInteractive(task, new byte[0], network(requests), turn)) {
            client.saveInteractiveState(task.sessionId(), bytes -> true);
            turn.cancel("Turn ended");
            assertThrows(
                    com.javaclaw.api.TurnCancelledException.class,
                    () -> client.actInteractive(
                            task.sessionId(),
                            task.lease(),
                            BrowserContracts.Action.simple(BrowserContracts.Operation.SNAPSHOT),
                            new byte[0],
                            new CancellationSource()));
            assertEquals(1, requests.get());
        }
    }

    @Test
    void 原生监护未确认回收时本地状态不能标记已关闭() throws Exception {
        Process child = command(new AtomicInteger()).start();
        try {
            var owned = new InteractiveBrowserProcess(child, () -> {}, () -> {
                child.destroyForcibly();
                child.waitFor(3, TimeUnit.SECONDS);
                throw new IllegalStateException("fixture cleanup unverified");
            });
            var connection = new InteractiveBrowserConnection(
                    owned, task(), network(new AtomicInteger()), Duration.ofSeconds(3));
            assertThrows(IllegalStateException.class, connection::close);
            assertEquals(BrowserContracts.SessionState.FAILED, connection.view().state());
        } finally {
            child.destroyForcibly();
        }
    }

    private static BrowserWorkerClient client(AtomicInteger launches, Duration timeout) {
        return new BrowserWorkerClient(
                command(launches), timeout, null, new BrowserWorkerCapabilities(false, false, true));
    }

    private static BrowserWorkerClient.WorkerLauncher command(AtomicInteger launches) {
        return () -> {
            launches.incrementAndGet();
            String java =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            return new ProcessBuilder(
                            java,
                            "-cp",
                            System.getProperty("java.class.path"),
                            "com.javaclaw.browser.testing.FakeInteractiveBrowserWorkerMain")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        };
    }

    private static InteractiveBrowserNetworkExchange network(AtomicInteger count) {
        return (request, body, cancellation) -> {
            cancellation.throwIfCancelled();
            count.incrementAndGet();
            return new BrowserNetworkResult(
                    new BrowserWorkerProtocol.NetworkResponse(200, Map.of(), false), new byte[0]);
        };
    }

    private static BrowserContracts.OpenTask task() {
        BrowserContracts.Owner owner =
                new BrowserContracts.Owner(WorkspaceId.random(), ThreadId.random(), Optional.empty());
        return new BrowserContracts.OpenTask(
                UUID.randomUUID().toString(),
                owner,
                URI.create("https://docs.example.com/start"),
                lease(BrowserContracts.ControlMode.ASSISTANT, 1));
    }

    private static BrowserContracts.AccessLease lease(BrowserContracts.ControlMode mode, long generation) {
        return new BrowserContracts.AccessLease(
                mode,
                UUID.randomUUID().toString(),
                generation,
                Instant.now().plusSeconds(60),
                Set.of(URI.create("https://docs.example.com")));
    }
}
