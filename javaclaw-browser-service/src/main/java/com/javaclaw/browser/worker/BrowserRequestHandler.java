package com.javaclaw.browser.worker;

import java.util.Objects;
import java.util.function.Consumer;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.protocol.CanonicalJson;

/** 将私有 Worker 信封映射到受限 Browser 会话。 */
final class BrowserRequestHandler implements AutoCloseable {
    private final BrowserSession browser;
    private final CanonicalJson json;

    BrowserRequestHandler(BrowserSession browser, CanonicalJson json) {
        this.browser = Objects.requireNonNull(browser, "browser");
        this.json = Objects.requireNonNull(json, "json");
    }

    BrowserWorkerReply handle(
            BrowserWorkerProtocol.Command request,
            byte[] storageState,
            BrowserLoginControl loginControl,
            Consumer<BrowserWorkerProtocol.WorkerMessage> events) {
        try {
            return switch (request.operation()) {
                case BrowserWorkerProtocol.SNAPSHOT -> snapshot(request, storageState);
                case BrowserWorkerProtocol.LOGIN -> login(request, storageState, loginControl, events);
                case BrowserWorkerProtocol.OAUTH -> oauth(request, loginControl, events);
                default -> throw new IllegalArgumentException("unsupported Browser Worker operation");
            };
        } catch (BrowserLoginInterruptedException interrupted) {
            return new BrowserWorkerReply(
                    BrowserWorkerProtocol.WorkerMessage.failure(request.id(), interrupted.errorCode()), new byte[0]);
        } catch (BrowserOAuthInterruptedException interrupted) {
            return new BrowserWorkerReply(
                    BrowserWorkerProtocol.WorkerMessage.failure(request.id(), interrupted.errorCode()), new byte[0]);
        } catch (RuntimeException failure) {
            return new BrowserWorkerReply(
                    BrowserWorkerProtocol.WorkerMessage.failure(request.id(), "BROWSER_REQUEST_FAILED"), new byte[0]);
        }
    }

    private BrowserWorkerReply oauth(
            BrowserWorkerProtocol.Command request,
            BrowserLoginControl control,
            Consumer<BrowserWorkerProtocol.WorkerMessage> events) {
        BrowserWorkerProtocol.OAuthTask task = json.decode(request.payload(), BrowserWorkerProtocol.OAuthTask.class);
        java.net.URI callback = browser.oauth(
                task,
                Objects.requireNonNull(control, "oauthControl"),
                () -> Objects.requireNonNull(events, "events")
                        .accept(BrowserWorkerProtocol.WorkerMessage.ready(
                                request.id(), json.encode(new BrowserWorkerProtocol.OAuthReady(task.sessionId())))));
        BrowserWorkerProtocol.OAuthCallback result =
                new BrowserWorkerProtocol.OAuthCallback(task.sessionId(), callback);
        return new BrowserWorkerReply(
                BrowserWorkerProtocol.WorkerMessage.success(request.id(), json.encode(result)), new byte[0]);
    }

    private BrowserWorkerReply snapshot(BrowserWorkerProtocol.Command request, byte[] storageState) {
        BrowserWorkerProtocol.SnapshotTask task =
                json.decode(request.payload(), BrowserWorkerProtocol.SnapshotTask.class);
        return new BrowserWorkerReply(
                BrowserWorkerProtocol.WorkerMessage.success(
                        request.id(), json.encode(browser.snapshot(task, storageState))),
                new byte[0]);
    }

    private BrowserWorkerReply login(
            BrowserWorkerProtocol.Command request,
            byte[] storageState,
            BrowserLoginControl control,
            Consumer<BrowserWorkerProtocol.WorkerMessage> events) {
        BrowserWorkerProtocol.LoginTask task = json.decode(request.payload(), BrowserWorkerProtocol.LoginTask.class);
        byte[] saved = browser.login(
                task,
                storageState,
                Objects.requireNonNull(control, "loginControl"),
                () -> Objects.requireNonNull(events, "events")
                        .accept(BrowserWorkerProtocol.WorkerMessage.ready(
                                request.id(), json.encode(new BrowserWorkerProtocol.LoginReady(task.sessionId())))));
        try {
            BrowserWorkerProtocol.LoginSaved result = new BrowserWorkerProtocol.LoginSaved(task.sessionId());
            return new BrowserWorkerReply(
                    BrowserWorkerProtocol.WorkerMessage.sensitiveSuccess(
                            request.id(), json.encode(result), saved.length),
                    saved);
        } finally {
            java.util.Arrays.fill(saved, (byte) 0);
        }
    }

    @Override
    public void close() {
        browser.close();
    }
}
