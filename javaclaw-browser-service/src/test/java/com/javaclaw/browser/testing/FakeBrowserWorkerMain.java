package com.javaclaw.browser.testing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.protocol.CanonicalJson;

/** BrowserWorkerClient 进程边界测试使用的一次性假 Worker。 */
public final class FakeBrowserWorkerMain {
    private FakeBrowserWorkerMain() {}

    /** 读取命令、执行一次反向网络交换并返回确定快照。 */
    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "success" : args[0];
        CanonicalJson json = new CanonicalJson();
        BrowserWorkerProtocol.Command command =
                BrowserFrameIo.readJson(System.in, json, BrowserWorkerProtocol.Command.class);
        byte[] state = BrowserFrameIo.readBinary(
                System.in, command.sensitiveStateBytes(), BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
        try {
            if ("exit".equals(mode)) {
                return;
            }
            if (BrowserWorkerProtocol.LOGIN.equals(command.operation())) {
                login(json, command, mode);
                return;
            }
            if (BrowserWorkerProtocol.OAUTH.equals(command.operation())) {
                oauth(json, command, mode);
                return;
            }
            BrowserWorkerProtocol.SnapshotTask task =
                    json.decode(command.payload(), BrowserWorkerProtocol.SnapshotTask.class);
            if (!"error".equals(mode)) {
                networkRoundTrip(json, command, task);
            }
            SiteContracts.PageSnapshot result =
                    new SiteContracts.PageSnapshot(task.uri(), "Fake", "worker-process", Instant.EPOCH);
            BrowserWorkerProtocol.WorkerMessage response =
                    switch (mode) {
                        case "error" -> BrowserWorkerProtocol.WorkerMessage.failure(command.id(), "TEST_REJECTION");
                        case "mismatch" ->
                            BrowserWorkerProtocol.WorkerMessage.success(command.id() + 1, json.encode(result));
                        default -> BrowserWorkerProtocol.WorkerMessage.success(command.id(), json.encode(result));
                    };
            BrowserFrameIo.writeJson(System.out, json, response);
        } finally {
            Arrays.fill(state, (byte) 0);
        }
    }

    private static void oauth(CanonicalJson json, BrowserWorkerProtocol.Command command, String mode) throws Exception {
        BrowserWorkerProtocol.OAuthTask task = json.decode(command.payload(), BrowserWorkerProtocol.OAuthTask.class);
        if ("oauth-error-before-ready".equals(mode)) {
            BrowserFrameIo.writeJson(
                    System.out, json, BrowserWorkerProtocol.WorkerMessage.failure(command.id(), "OAUTH_TEST_FAILED"));
            return;
        }
        long networkSequence = "oauth-network-sequence".equals(mode) ? 2 : 1;
        networkRoundTrip(json, command, task.authorizationUri(), networkSequence);
        long readyCommandId = "oauth-command-mismatch".equals(mode) ? command.id() + 1 : command.id();
        String readySessionId = "oauth-ready-mismatch".equals(mode)
                ? java.util.UUID.randomUUID().toString()
                : task.sessionId();
        BrowserFrameIo.writeJson(
                System.out,
                json,
                BrowserWorkerProtocol.WorkerMessage.ready(
                        readyCommandId, json.encode(new BrowserWorkerProtocol.OAuthReady(readySessionId))));
        if ("oauth-wait".equals(mode)) {
            awaitOAuthCancel(json, command, task);
            return;
        }
        if ("oauth-expired".equals(mode)) {
            BrowserFrameIo.writeJson(
                    System.out, json, BrowserWorkerProtocol.WorkerMessage.failure(command.id(), "OAUTH_EXPIRED"));
            return;
        }
        String state = query(task.authorizationUri(), "state");
        java.net.URI callback = java.net.URI.create(task.redirectUri() + "?code=fake-code&state=" + state);
        String callbackSessionId = "oauth-callback-mismatch".equals(mode)
                ? java.util.UUID.randomUUID().toString()
                : task.sessionId();
        BrowserWorkerProtocol.OAuthCallback result =
                new BrowserWorkerProtocol.OAuthCallback(callbackSessionId, callback);
        if ("oauth-sensitive-result".equals(mode)) {
            BrowserFrameIo.writeJson(
                    System.out,
                    json,
                    BrowserWorkerProtocol.WorkerMessage.sensitiveSuccess(command.id(), json.encode(result), 1));
            BrowserFrameIo.writeBinary(System.out, new byte[] {1}, BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
            return;
        }
        BrowserFrameIo.writeJson(
                System.out, json, BrowserWorkerProtocol.WorkerMessage.success(command.id(), json.encode(result)));
    }

    private static void awaitOAuthCancel(
            CanonicalJson json, BrowserWorkerProtocol.Command command, BrowserWorkerProtocol.OAuthTask task)
            throws Exception {
        Path control = Path.of(System.getenv("JAVACLAW_BROWSER_CONTROL_ROOT"))
                .resolve("oauth-" + task.sessionId() + ".control");
        long deadline = System.nanoTime() + task.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(control)
                    && "CANCEL"
                            .equals(Files.readString(control, StandardCharsets.US_ASCII)
                                    .strip())) {
                BrowserFrameIo.writeJson(
                        System.out, json, BrowserWorkerProtocol.WorkerMessage.failure(command.id(), "OAUTH_CANCELLED"));
                return;
            }
            Thread.sleep(10);
        }
        BrowserFrameIo.writeJson(
                System.out, json, BrowserWorkerProtocol.WorkerMessage.failure(command.id(), "OAUTH_EXPIRED"));
    }

    private static String query(java.net.URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && pair.substring(0, separator).equals(name)) {
                return pair.substring(separator + 1);
            }
        }
        throw new IllegalArgumentException("missing OAuth query field");
    }

    private static void login(CanonicalJson json, BrowserWorkerProtocol.Command command, String mode) throws Exception {
        BrowserWorkerProtocol.LoginTask task = json.decode(command.payload(), BrowserWorkerProtocol.LoginTask.class);
        if ("login-error-before-ready".equals(mode)) {
            BrowserFrameIo.writeJson(
                    System.out, json, BrowserWorkerProtocol.WorkerMessage.failure(command.id(), "LOGIN_TEST_FAILED"));
            return;
        }
        long networkSequence = "login-network-sequence".equals(mode) ? 2 : 1;
        networkRoundTrip(json, command, task.uri(), networkSequence);
        long readyCommandId = "login-command-mismatch".equals(mode) ? command.id() + 1 : command.id();
        String readySessionId = "login-ready-mismatch".equals(mode)
                ? java.util.UUID.randomUUID().toString()
                : task.sessionId();
        BrowserFrameIo.writeJson(
                System.out,
                json,
                BrowserWorkerProtocol.WorkerMessage.ready(
                        readyCommandId, json.encode(new BrowserWorkerProtocol.LoginReady(readySessionId))));
        if ("login-expired".equals(mode)) {
            BrowserFrameIo.writeJson(
                    System.out, json, BrowserWorkerProtocol.WorkerMessage.failure(command.id(), "LOGIN_EXPIRED"));
            return;
        }
        awaitLoginDecision(json, command, task, mode);
    }

    private static void awaitLoginDecision(
            CanonicalJson json,
            BrowserWorkerProtocol.Command command,
            BrowserWorkerProtocol.LoginTask task,
            String mode)
            throws Exception {
        Path root = Path.of(System.getenv("JAVACLAW_BROWSER_CONTROL_ROOT"));
        Path control = root.resolve("login-" + task.sessionId() + ".control");
        long deadline = System.nanoTime() + task.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(control)) {
                String decision =
                        Files.readString(control, StandardCharsets.US_ASCII).strip();
                if ("CANCEL".equals(decision)) {
                    BrowserFrameIo.writeJson(
                            System.out,
                            json,
                            BrowserWorkerProtocol.WorkerMessage.failure(command.id(), "LOGIN_CANCELLED"));
                    return;
                }
                if ("SAVE".equals(decision)) {
                    if ("login-empty-state".equals(mode)) {
                        BrowserFrameIo.writeJson(
                                System.out,
                                json,
                                BrowserWorkerProtocol.WorkerMessage.success(
                                        command.id(),
                                        json.encode(new BrowserWorkerProtocol.LoginSaved(task.sessionId()))));
                        return;
                    }
                    byte[] state =
                            "{\"cookies\":[{\"name\":\"session\",\"value\":\"browser-login-marker\"}],\"origins\":[]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    try {
                        String savedSessionId = "login-saved-mismatch".equals(mode)
                                ? java.util.UUID.randomUUID().toString()
                                : task.sessionId();
                        BrowserFrameIo.writeJson(
                                System.out,
                                json,
                                BrowserWorkerProtocol.WorkerMessage.sensitiveSuccess(
                                        command.id(),
                                        json.encode(new BrowserWorkerProtocol.LoginSaved(savedSessionId)),
                                        state.length));
                        BrowserFrameIo.writeBinary(System.out, state, BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
                    } finally {
                        Arrays.fill(state, (byte) 0);
                    }
                    return;
                }
            }
            Thread.sleep(10);
        }
        BrowserFrameIo.writeJson(
                System.out, json, BrowserWorkerProtocol.WorkerMessage.failure(command.id(), "LOGIN_EXPIRED"));
    }

    private static void networkRoundTrip(
            CanonicalJson json, BrowserWorkerProtocol.Command command, BrowserWorkerProtocol.SnapshotTask task)
            throws Exception {
        networkRoundTrip(json, command, task.uri());
    }

    private static void networkRoundTrip(CanonicalJson json, BrowserWorkerProtocol.Command command, java.net.URI uri)
            throws Exception {
        networkRoundTrip(json, command, uri, 1);
    }

    private static void networkRoundTrip(
            CanonicalJson json, BrowserWorkerProtocol.Command command, java.net.URI uri, long sequence)
            throws Exception {
        BrowserWorkerProtocol.NetworkRequest request =
                new BrowserWorkerProtocol.NetworkRequest(uri, "GET", Map.of("x-worker", List.of("test")));
        BrowserFrameIo.writeJson(
                System.out,
                json,
                BrowserWorkerProtocol.WorkerMessage.network(command.id(), sequence, json.encode(request), 0));
        BrowserFrameIo.writeBinary(System.out, new byte[0], BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
        BrowserWorkerProtocol.HostMessage response =
                BrowserFrameIo.readJson(System.in, json, BrowserWorkerProtocol.HostMessage.class);
        if (response.error().isPresent()) {
            throw new IllegalStateException("host denied fake network request");
        }
        byte[] body = BrowserFrameIo.readBinary(
                System.in, response.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
        Arrays.fill(body, (byte) 0);
    }
}
