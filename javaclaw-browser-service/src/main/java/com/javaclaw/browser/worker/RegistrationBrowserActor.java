package com.javaclaw.browser.worker;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import com.javaclaw.browser.protocol.BrowserRegistrationProtocol;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.WorkerStatus;

/** Workspace 登记的唯一 Playwright Actor；页面、候选与导出均在主线程拥有，不接受助手动作。 */
final class RegistrationBrowserActor implements AutoCloseable {
    private final SiteRegistrationContracts.WorkerTask task;
    private final Supplier<Playwright> factory;
    private final InteractiveBrowserNetwork network;
    private final RegistrationCredentials credentials = new RegistrationCredentials();
    private final List<Page> pages = new ArrayList<>();
    private BrowserContracts.AccessLease lease;
    private VisibleBrowserContext runtime;
    private BrowserContext context;
    private Page selected;
    private State state = State.ACTIVE;
    private long revision;
    private boolean exporting;
    private String observedUri = "";
    private String observedTitle = "";
    private SiteRegistrationContracts.Page observation =
            new SiteRegistrationContracts.Page(0, Optional.empty(), "", List.of());

    RegistrationBrowserActor(
            SiteRegistrationContracts.WorkerTask task,
            InteractiveWorkerConnection connection,
            Supplier<Playwright> factory) {
        this.task = task;
        this.factory = factory;
        lease = task.lease();
        network = new InteractiveBrowserNetwork(connection, this::networkLease);
    }

    WorkerStatus open() {
        requireActive();
        runtime = new VisibleBrowserContext(factory, new byte[0], false);
        context = runtime.context();
        network.configure(context);
        credentials.install(context, this::allowed, () -> revision++);
        installSelection();
        context.onPage(this::register);
        Page first = context.newPage();
        register(first);
        network.requireTarget(task.initialUri().toString());
        try {
            first.navigate(
                    task.initialUri().toString(),
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(20_000));
        } catch (RuntimeException failure) {
            if (!pendingNavigation(first, failure)) {
                throw failure;
            }
            // 初始地址跳转到尚未授权的来源时保留窗口；用户授权并刷新后才能继续请求。
        }
        return view();
    }

    private boolean pendingNavigation(Page page, RuntimeException failure) {
        String message = failure.getMessage();
        return runtime.connected()
                && !page.isClosed()
                && !network.pendingOrigins().isEmpty()
                && message != null
                && message.contains("ERR_BLOCKED_BY_CLIENT");
    }

    private void installSelection() {
        context.exposeBinding("__javaclawRegistrationFocus", (source, arguments) -> {
            if (source.frame() == source.page().mainFrame() && pages.contains(source.page())) {
                select(source.page());
            }
            return null;
        });
        context.addInitScript("""
                (() => {
                  if (window !== window.top) return;
                  const focused = () => {
                    if (document.visibilityState === 'visible') {
                      window.__javaclawRegistrationFocus().catch(() => {});
                    }
                  };
                  window.addEventListener('focus', focused);
                  document.addEventListener('visibilitychange', focused);
                  focused();
                })();
                """);
    }

    private void register(Page page) {
        if (pages.contains(page)) {
            return;
        }
        if (pages.size() >= BrowserContracts.MAXIMUM_TABS) {
            page.close();
            return;
        }
        pages.add(page);
        select(page);
        page.setDefaultTimeout(5_000);
        page.onDialog(dialog -> dialog.dismiss());
        page.onDownload(download -> download.cancel());
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                revision++;
            }
        });
        page.onClose(ignored -> {
            pages.remove(page);
            if (selected == page) {
                select(pages.isEmpty() ? null : pages.getLast());
            }
        });
    }

    private void select(Page page) {
        if (selected != page) {
            selected = page;
            revision++;
        }
    }

    WorkerStatus updateLease(BrowserContracts.AccessLease next) {
        requireActive();
        if (next.mode() != BrowserContracts.ControlMode.HUMAN
                || next.generation() <= lease.generation()
                || !next.expiresAt().equals(lease.expiresAt())) {
            throw new IllegalArgumentException("BROWSER_STALE_OBSERVATION");
        }
        lease = next;
        return view();
    }

    WorkerStatus view() {
        refresh();
        return new WorkerStatus(
                task.sessionId(),
                state,
                new SiteRegistrationContracts.Access(
                        lease.generation(), lease.allowedOrigins(), network.pendingOrigins(), lease.expiresAt()),
                observation);
    }

    private void refresh() {
        if (state != State.ACTIVE) {
            return;
        }
        if (!lease.active(Instant.now())) {
            terminate(State.EXPIRED);
        } else if (runtime != null && (selected == null || selected.isClosed())) {
            terminate(State.CANCELLED);
        } else if (runtime != null && !runtime.connected()) {
            terminate(State.FAILED);
        } else if (selected != null) {
            observe();
        }
    }

    private void observe() {
        Page page = selected;
        // 在同一个页面任务里读取地址与标题，避免 title() 泵送导航事件后拼出混合页面快照。
        List<?> metadata = (List<?>) page.evaluate("() => [location.href, document.title]");
        if (selected != page || page.isClosed()) {
            return;
        }
        String uri = (String) metadata.get(0);
        String title = (String) metadata.get(1);
        if (!uri.equals(observedUri) || !title.equals(observedTitle)) {
            observedUri = uri;
            observedTitle = title;
            revision++;
        }
        Optional<URI> visible = visibleUri(uri);
        List<SiteRegistrationContracts.CredentialCandidate> candidates = visible.map(SiteContracts::originOf)
                .map(origin -> credentials.descriptions().stream()
                        .filter(candidate -> candidate.origin().equals(origin))
                        .toList())
                .orElseGet(List::of);
        String safeTitle = credentials.redact(title).replaceAll("\\p{Cntrl}", " ");
        observation = new SiteRegistrationContracts.Page(
                revision, visible, safeTitle.substring(0, Math.min(safeTitle.length(), 1000)), candidates);
    }

    private Optional<URI> visibleUri(String value) {
        try {
            URI visible = SiteRegistrationContracts.displayUri(URI.create(value));
            if (!allowed(SiteContracts.originOf(visible))) {
                return Optional.empty();
            }
            return Optional.of(URI.create(visible.getScheme() + "://" + visible.getRawAuthority()
                    + credentials.redact(visible.getRawPath())));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    RegistrationExport complete(SiteRegistrationContracts.CompleteRequest request) {
        WorkerStatus confirmed = view();
        requireConfirmation(request, confirmed);
        URI origin = SiteContracts.originOf(confirmed.page().uri().orElseThrow());
        byte[] secret = credentials.selected(request.credentialId(), origin);
        byte[] storage = new byte[0];
        exporting = true;
        try {
            storage = RegistrationStorage.filter(
                    context.storageState(new BrowserContext.StorageStateOptions().setIndexedDB(true)), origin);
            // storageState 也会泵送页面事件；导出前后必须保持同一页面、候选与授权确认。
            requireConfirmation(request, view());
            return new RegistrationExport(
                    new BrowserRegistrationProtocol.PrivateResult(confirmed, storage.length, secret.length),
                    storage,
                    secret);
        } finally {
            Arrays.fill(secret, (byte) 0);
            Arrays.fill(storage, (byte) 0);
            terminate(State.CANCELLED);
        }
    }

    private void requireConfirmation(SiteRegistrationContracts.CompleteRequest request, WorkerStatus current) {
        if (state != State.ACTIVE || !lease.active(Instant.now())) {
            throw new IllegalStateException("BROWSER_LEASE_INACTIVE");
        }
        if (!task.sessionId().equals(request.sessionId())
                || request.expectedGeneration() != lease.generation()
                || request.expectedPageRevision() != current.page().pageRevision()
                || current.page().uri().isEmpty()) {
            throw new IllegalStateException("BROWSER_STALE_OBSERVATION");
        }
    }

    private BrowserContracts.AccessLease networkLease() {
        // 完成确认后停止全部页面网络；状态导出不能开启新请求或更新确认时的登录状态。
        return exporting
                ? new BrowserContracts.AccessLease(
                        BrowserContracts.ControlMode.NONE,
                        lease.leaseId(),
                        lease.generation(),
                        lease.expiresAt(),
                        lease.allowedOrigins())
                : lease;
    }

    private boolean allowed(URI origin) {
        return state == State.ACTIVE
                && lease.active(Instant.now())
                && lease.allowedOrigins().contains(origin);
    }

    private void requireActive() {
        refresh();
        if (state != State.ACTIVE) {
            throw new IllegalStateException("BROWSER_LEASE_INACTIVE");
        }
    }

    void pump() {
        refresh();
        if (state == State.ACTIVE && selected != null) {
            try {
                selected.waitForTimeout(25);
            } catch (RuntimeException failure) {
                refresh();
                if (state == State.ACTIVE) {
                    terminate(State.FAILED);
                }
            }
        } else {
            java.util.concurrent.locks.LockSupport.parkNanos(25_000_000);
        }
    }

    private void terminate(State terminal) {
        if (state != State.ACTIVE) {
            return;
        }
        state = terminal;
        credentials.close();
        if (runtime != null) {
            runtime.close();
            runtime = null;
            context = null;
        }
        pages.clear();
        selected = null;
        observedUri = "";
        observedTitle = "";
    }

    @Override
    public void close() {
        terminate(State.CANCELLED);
    }
}
