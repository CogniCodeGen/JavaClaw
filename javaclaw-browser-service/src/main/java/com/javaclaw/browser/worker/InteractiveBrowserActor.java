package com.javaclaw.browser.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import com.javaclaw.browser.client.BrowserActionResult;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserContracts.ControlMode;
import com.javaclaw.builtin.contracts.BrowserContracts.Operation;

/** 一个 Thread 与账号绑定对应一个可见 Context；所有 Playwright 对象严格在创建它们的 actor 线程使用。 */
final class InteractiveBrowserActor implements AutoCloseable {
    private final BrowserContracts.OpenTask task;
    private BrowserContracts.AccessLease lease;
    private final InteractiveBrowserNetwork network;
    private final java.util.function.Supplier<Playwright> factory;
    private VisibleBrowserContext runtime;
    private BrowserContext context;
    private InteractiveBrowserPages pages;
    private InteractiveBrowserActions actions;
    private boolean closed;

    InteractiveBrowserActor(BrowserContracts.OpenTask task, InteractiveWorkerConnection connection) {
        this(task, connection, Playwright::create);
    }

    InteractiveBrowserActor(
            BrowserContracts.OpenTask task,
            InteractiveWorkerConnection connection,
            java.util.function.Supplier<Playwright> factory) {
        this.task = task;
        lease = task.lease();
        network = new InteractiveBrowserNetwork(connection, () -> lease);
        this.factory = factory;
    }

    BrowserActionResult open(byte[] storageState) {
        requireActive();
        if (storageState.length > BrowserWorkerProtocol.MAXIMUM_STATE_BYTES) {
            throw new IllegalArgumentException("Browser storage state exceeds limit");
        }
        runtime = new VisibleBrowserContext(factory, storageState, true);
        context = runtime.context();
        network.configure(context);
        pages = new InteractiveBrowserPages(context);
        if (storageState.length > 0 || task.owner().account().isPresent() || lease.mode() == ControlMode.HUMAN) {
            pages.restrictScreenshots();
        }
        actions = new InteractiveBrowserActions(context, pages, network, () -> lease);
        Page page = pages.newPage();
        network.requireTarget(task.uri().toString());
        page.navigate(
                task.uri().toString(),
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(20_000));
        return observe(page, Optional.empty());
    }

    BrowserActionResult act(BrowserContracts.Action action, byte[] privateInput) throws IOException {
        requireActive();
        boolean observation = action.operation() == Operation.SNAPSHOT || action.operation() == Operation.SCREENSHOT;
        if (lease.mode() != ControlMode.ASSISTANT && !observation) {
            throw new IllegalStateException("BROWSER_HUMAN_CONTROL");
        }
        if (action.operation() != Operation.NAVIGATE && action.operation() != Operation.NEW_TAB) {
            network.requireTarget(pages.page(action.target().pageId()).url());
        }
        if (action.operation() == Operation.SCREENSHOT) {
            return screenshot(pages.page(action.target().pageId()));
        }
        Optional<InteractiveBrowserActions.ArtifactBytes> artifact = actions.execute(action, privateInput);
        Page page =
                switch (action.operation()) {
                    case NEW_TAB, CLOSE_TAB, SWITCH_TAB -> pages.page("");
                    default -> pages.page(action.target().pageId());
                };
        try {
            return observe(page, artifact);
        } finally {
            artifact.ifPresent(InteractiveBrowserActions.ArtifactBytes::close);
        }
    }

    BrowserContracts.SessionView updateLease(BrowserContracts.AccessLease next) {
        if (next.generation() <= lease.generation()) {
            throw new IllegalArgumentException("Browser control generation must increase");
        }
        lease = next;
        if (next.mode() == ControlMode.HUMAN) {
            pages.restrictScreenshots();
        }
        pages.invalidate();
        return view();
    }

    BrowserContracts.SessionView view() {
        return new BrowserContracts.SessionView(
                task.sessionId(),
                task.owner(),
                closed ? BrowserContracts.SessionState.CLOSED : BrowserContracts.SessionState.OPEN,
                lease,
                closed || pages == null ? List.of() : pages.tabs());
    }

    byte[] saveState() {
        // 私有持久化权由宿主账号 StateLease 决定；NONE 仍可读 Context 状态但所有网络继续被路由阻断。
        if (closed || context == null) {
            throw new IllegalStateException("BROWSER_SESSION_CLOSED");
        }
        byte[] bytes = context.storageState(new BrowserContext.StorageStateOptions().setIndexedDB(true))
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > BrowserWorkerProtocol.MAXIMUM_STATE_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalStateException("Browser storage state exceeds limit");
        }
        return bytes;
    }

    List<BrowserContracts.LoginForm> prepare(InteractiveBrowserProtocol.FormsRequest request) {
        requireLease(request.expectedLease());
        requireMode(ControlMode.HUMAN);
        Page page = pages.page("");
        network.requireTarget(page.url());
        return pages.loginForms(page, request.expectedOrigin());
    }

    byte[] capture(InteractiveBrowserProtocol.CredentialsRequest request) {
        requireLease(request.expectedLease());
        requireMode(ControlMode.HUMAN);
        Page page = pages.page(request.target().pageId());
        network.requireTarget(page.url());
        return InteractiveBrowserCredentials.capture(page, pages, request);
    }

    BrowserActionResult fillCredentials(InteractiveBrowserProtocol.CredentialsRequest request, byte[] bytes) {
        requireLease(request.expectedLease());
        requireMode(ControlMode.ASSISTANT);
        Page page = pages.page(request.target().pageId());
        network.requireTarget(page.url());
        InteractiveBrowserCredentials.fill(page, pages, request, bytes);
        return observe(page, Optional.empty());
    }

    BrowserActionResult act(InteractiveBrowserProtocol.ActionRequest request, byte[] bytes) throws IOException {
        requireLease(request.expectedLease());
        return act(request.action(), bytes);
    }

    private void requireMode(ControlMode mode) {
        if (lease.mode() != mode) {
            throw new IllegalStateException("BROWSER_CONTROL_MODE_CHANGED");
        }
    }

    private void requireLease(BrowserContracts.AccessLease expected) {
        requireActive();
        if (!lease.equals(expected)) {
            throw new IllegalStateException("BROWSER_STALE_OBSERVATION");
        }
    }

    void pump() {
        if (pages != null && !pages.empty()) {
            pages.pump();
        }
    }

    boolean alive() {
        return !closed && runtime != null && runtime.connected() && !pages.empty();
    }

    private BrowserActionResult screenshot(Page page) {
        BrowserContracts.PageSnapshot snapshot = pages.snapshot(page);
        byte[] png = pages.screenshot(page);
        try {
            BrowserContracts.Frame frame = pages.frame(page, png, lease.generation());
            var artifact = new BrowserContracts.Artifact(
                    new BrowserContracts.FileSpec("browser.png", "image/png"), png.length);
            return new BrowserActionResult(
                    new BrowserContracts.Observation(view(), snapshot, Optional.of(frame), Optional.of(artifact)), png);
        } finally {
            Arrays.fill(png, (byte) 0);
        }
    }

    private BrowserActionResult observe(Page page, Optional<InteractiveBrowserActions.ArtifactBytes> bytes) {
        BrowserContracts.PageSnapshot snapshot = pages.snapshot(page);
        Optional<BrowserContracts.Artifact> artifact =
                bytes.map(value -> new BrowserContracts.Artifact(value.file(), value.bytes().length));
        return new BrowserActionResult(
                new BrowserContracts.Observation(view(), snapshot, Optional.empty(), artifact),
                bytes.map(InteractiveBrowserActions.ArtifactBytes::bytes).orElseGet(() -> new byte[0]));
    }

    private void requireActive() {
        if (closed || !lease.active(Instant.now())) {
            throw new IllegalStateException("BROWSER_LEASE_INACTIVE");
        }
    }

    @Override
    public void close() {
        closed = true;
        if (pages != null) {
            pages.close();
        }
        if (runtime != null) {
            runtime.close();
            runtime = null;
            context = null;
        }
    }
}
