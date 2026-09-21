package com.javaclaw.browser.client;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.WorkerStatus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class BrowserRegistrationSessionsTest {
    @Test
    void 状态每次真实查询且完成只回调一次并清零所有私有数组() throws Exception {
        var processes = new ArrayList<Process>();
        AtomicReference<byte[]> savedState = new AtomicReference<>();
        AtomicReference<byte[]> savedCredentials = new AtomicReference<>();
        try (BrowserRegistrationSessions sessions = sessions(processes, Duration.ofSeconds(3))) {
            WorkerStatus first = sessions.begin(task("/normal"), network(), new CancellationSource());
            WorkerStatus current = sessions.status(first.sessionId());
            assertTrue(current.page().pageRevision() > first.page().pageRevision());
            assertEquals(
                    "saved",
                    sessions.complete(
                            first.sessionId(),
                            confirmation(current, Optional.of("candidate")),
                            (status, state, credentials) -> {
                                assertEquals(current, status);
                                assertTrue(processes.getFirst().isAlive(), "私有导出后进程必须等待宿主明确整树清理");
                                assertEquals("user\0secret", new String(credentials, StandardCharsets.UTF_8));
                                savedState.set(state);
                                savedCredentials.set(credentials);
                                return "saved";
                            }));
            assertArrayEquals(new byte[savedState.get().length], savedState.get());
            assertArrayEquals(new byte[savedCredentials.get().length], savedCredentials.get());
            assertEquals(State.CANCELLED, sessions.status(first.sessionId()).state());
            assertThrows(
                    IllegalStateException.class,
                    () -> sessions.complete(
                            first.sessionId(),
                            confirmation(current, Optional.empty()),
                            (status, state, credentials) -> "never"));
        }
        assertStopped(processes);
    }

    @Test
    void 租约必须递增且保持截止时间与人工模式并可重复取消() throws Exception {
        var processes = new ArrayList<Process>();
        try (BrowserRegistrationSessions sessions = sessions(processes, Duration.ofSeconds(3))) {
            var task = task("/normal");
            WorkerStatus first = sessions.begin(task, network(), new CancellationSource());
            assertThrows(IllegalStateException.class, () -> sessions.begin(task, network(), new CancellationSource()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> sessions.updateLease(first.sessionId(), task.lease(), new CancellationSource()));
            var wrong = new BrowserContracts.AccessLease(
                    BrowserContracts.ControlMode.ASSISTANT,
                    "wrong",
                    2,
                    task.lease().expiresAt(),
                    task.lease().allowedOrigins());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> sessions.updateLease(first.sessionId(), wrong, new CancellationSource()));
            var next = new BrowserContracts.AccessLease(
                    BrowserContracts.ControlMode.HUMAN,
                    "next",
                    2,
                    task.lease().expiresAt(),
                    Set.of(URI.create("https://example.com"), URI.create("https://other.com")));
            assertEquals(
                    2,
                    sessions.updateLease(first.sessionId(), next, new CancellationSource())
                            .access()
                            .generation());
            assertEquals(State.CANCELLED, sessions.cancel(first.sessionId()).state());
            assertEquals(State.CANCELLED, sessions.cancel(first.sessionId()).state());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> sessions.status(UUID.randomUUID().toString()));
        }
        assertStopped(processes);
    }

    @Test
    void 损坏私有长度与回调异常均关闭且不会保留或重放秘密() throws Exception {
        var processes = new ArrayList<Process>();
        try (BrowserRegistrationSessions sessions = sessions(processes, Duration.ofSeconds(3))) {
            WorkerStatus invalid = sessions.begin(task("/bad-size"), network(), new CancellationSource());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> sessions.complete(
                            invalid.sessionId(),
                            confirmation(invalid, Optional.empty()),
                            (status, state, credentials) -> {
                                throw new AssertionError("损坏结果不能到达持久化回调");
                            }));
            assertEquals(State.FAILED, sessions.status(invalid.sessionId()).state());
            WorkerStatus normal = sessions.begin(task("/normal"), network(), new CancellationSource());
            AtomicReference<byte[]> borrowed = new AtomicReference<>();
            var failed = assertThrows(
                    BrowserWorkerException.class,
                    () -> sessions.complete(
                            normal.sessionId(),
                            confirmation(normal, Optional.empty()),
                            (status, state, credentials) -> {
                                borrowed.set(state);
                                throw new IOException("private-value");
                            }));
            assertFalse(failed.getMessage().contains("private-value"));
            assertArrayEquals(new byte[borrowed.get().length], borrowed.get());
            assertEquals(State.FAILED, sessions.status(normal.sessionId()).state());
        }
        assertStopped(processes);
    }

    @Test
    void 不匹配身份和非空公开状态私有负载均拒绝并终止() throws Exception {
        var processes = new ArrayList<Process>();
        try (BrowserRegistrationSessions sessions = sessions(processes, Duration.ofSeconds(3))) {
            assertThrows(
                    BrowserWorkerException.class,
                    () -> sessions.begin(task("/bad-generation"), network(), new CancellationSource()));
            WorkerStatus unexpected = sessions.begin(task("/private-status"), network(), new CancellationSource());
            assertEquals(State.FAILED, sessions.status(unexpected.sessionId()).state());
            WorkerStatus closed = sessions.begin(task("/window-closed"), network(), new CancellationSource());
            assertEquals(State.CANCELLED, sessions.status(closed.sessionId()).state());
        }
        assertStopped(processes);
    }

    @Test
    void 超时不重启进程或重发完成且过期回收不等待调用结束() throws Exception {
        var processes = new ArrayList<Process>();
        try (BrowserRegistrationSessions sessions = sessions(processes, Duration.ofSeconds(2))) {
            WorkerStatus first = sessions.begin(task("/slow-status"), network(), new CancellationSource());
            assertEquals(State.FAILED, sessions.status(first.sessionId()).state());
            WorkerStatus completing = sessions.begin(task("/slow-complete"), network(), new CancellationSource());
            assertThrows(
                    BrowserWorkerException.class,
                    () -> sessions.complete(
                            completing.sessionId(),
                            confirmation(completing, Optional.empty()),
                            (status, state, credentials) -> "never"));
            assertEquals(State.FAILED, sessions.status(completing.sessionId()).state());
            var expiring = task("/slow-start", Instant.now().plusMillis(100));
            assertThrows(
                    BrowserWorkerException.class, () -> sessions.begin(expiring, network(), new CancellationSource()));
            assertEquals(State.EXPIRED, sessions.status(expiring.sessionId()).state());
            assertEquals(3, processes.size());
        }
        assertStopped(processes);
    }

    @Test
    void 不可用能力与无效期限不启动且关闭服务拒绝新登记() throws Exception {
        var processes = new ArrayList<Process>();
        try (BrowserWorkerClient client = new BrowserWorkerClient(() -> start(processes), Duration.ofSeconds(3))) {
            assertThrows(UnsupportedOperationException.class, client::registrations);
        }
        assertTrue(processes.isEmpty());
        BrowserRegistrationSessions sessions = sessions(processes, Duration.ofSeconds(3));
        try (sessions) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> sessions.begin(
                            task("/normal", Instant.now().minusSeconds(1)), network(), new CancellationSource()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> sessions.begin(
                            task("/normal", Instant.now().plusSeconds(1000)), network(), new CancellationSource()));
            var cancelled = new CancellationSource();
            cancelled.cancel("cancelled");
            assertThrows(
                    com.javaclaw.api.TurnCancelledException.class,
                    () -> sessions.begin(task("/normal"), network(), cancelled));
        }
        assertThrows(
                IllegalStateException.class,
                () -> sessions.begin(task("/normal"), network(), new CancellationSource()));
        try (var unavailable = new BrowserRegistrationSessions(
                () -> {
                    throw new IOException("unavailable");
                },
                Duration.ofSeconds(3))) {
            assertThrows(
                    BrowserWorkerException.class,
                    () -> unavailable.begin(task("/normal"), network(), new CancellationSource()));
        }
        assertTrue(processes.isEmpty());
    }

    @Test
    void 原生回收缺少成功证据时取消和截止均明确失败() throws Exception {
        var processes = new ArrayList<Process>();
        try (var sessions = new BrowserRegistrationSessions(
                () -> {
                    Process process = start(processes);
                    return new InteractiveBrowserProcess(process, () -> {}, () -> {
                        process.destroyForcibly();
                        throw new IllegalStateException("Worker monitor did not confirm complete cleanup");
                    });
                },
                Duration.ofSeconds(3))) {
            WorkerStatus manual = sessions.begin(task("/normal"), network(), new CancellationSource());
            assertThrows(IllegalStateException.class, () -> sessions.cancel(manual.sessionId()));
            assertEquals(State.FAILED, sessions.status(manual.sessionId()).state());
        }
        try (var sessions = new BrowserRegistrationSessions(
                () -> {
                    Process process = start(processes);
                    return new InteractiveBrowserProcess(process, () -> {}, () -> {
                        process.destroyForcibly();
                        throw new IllegalStateException("Worker monitor did not confirm complete cleanup");
                    });
                },
                Duration.ofSeconds(3))) {
            WorkerStatus expiring =
                    sessions.begin(task("/normal", Instant.now().plusSeconds(1)), network(), new CancellationSource());
            Thread.sleep(1_100);
            assertEquals(State.FAILED, sessions.status(expiring.sessionId()).state());
        }
        assertStopped(processes);
    }

    private static BrowserRegistrationSessions sessions(List<Process> processes, Duration timeout) {
        return new BrowserRegistrationSessions(
                () -> {
                    Process process = start(processes);
                    return new InteractiveBrowserProcess(process, () -> {}, process::destroyForcibly);
                },
                timeout);
    }

    private static Process start(List<Process> processes) throws IOException {
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        "com.javaclaw.browser.testing.FakeRegistrationWorkerMain")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        processes.add(process);
        return process;
    }

    private static SiteRegistrationContracts.WorkerTask task(String path) {
        return task(path, Instant.now().plusSeconds(60));
    }

    private static SiteRegistrationContracts.WorkerTask task(String path, Instant deadline) {
        var lease = new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.HUMAN,
                "registration",
                1,
                deadline,
                Set.of(URI.create("https://example.com")));
        return new SiteRegistrationContracts.WorkerTask(
                UUID.randomUUID().toString(), WorkspaceId.random(), URI.create("https://example.com" + path), lease);
    }

    private static SiteRegistrationContracts.CompleteRequest confirmation(
            WorkerStatus status, Optional<String> candidate) {
        return new SiteRegistrationContracts.CompleteRequest(
                status.sessionId(), status.access().generation(), status.page().pageRevision(), candidate, "Example");
    }

    private static InteractiveBrowserNetworkExchange network() {
        return (request, body, cancellation) ->
                new BrowserNetworkResult(new BrowserWorkerProtocol.NetworkResponse(200, Map.of(), false), new byte[0]);
    }

    private static void assertStopped(List<Process> processes) throws InterruptedException {
        for (Process process : processes) {
            assertTrue(process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(process.isAlive());
        }
    }
}
