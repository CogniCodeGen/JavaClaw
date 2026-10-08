package com.javaclaw.browser;

import com.javaclaw.framework.api.InteractionSurfaceEvent;
import com.javaclaw.framework.spi.InteractionInvocation;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Identity is scoped to actual Playwright objects, never tab index, URL, or a recovered DOM ref. */
final class BrowserSurfaceTracker {
    private static final Logger log = LoggerFactory.getLogger(BrowserSurfaceTracker.class);
    private final String runtimeId = "browser:" + UUID.randomUUID();
    private final Map<Page, Identity> pages = new IdentityHashMap<>();
    private Consumer<InteractionSurfaceEvent> observer = ignored -> { };
    private String currentContext = "";

    synchronized void observer(Consumer<InteractionSurfaceEvent> value) {
        observer = Objects.requireNonNull(value);
    }

    synchronized void attach(BrowserContext context) {
        String contextId = "context:" + UUID.randomUUID();
        currentContext = contextId;
        pages.clear();
        emit(event(InteractionSurfaceEvent.Kind.CONTEXT_OPENED, contextId, null));
        context.onPage(page -> register(page, contextId));
        context.onClose(ignored -> {
            synchronized (this) {
                emit(event(InteractionSurfaceEvent.Kind.CONTEXT_CLOSED, contextId, null));
                if (currentContext.equals(contextId)) currentContext = "";
            }
        });
        for (Page page : context.pages()) register(page, contextId);
    }

    private synchronized void register(Page page, String contextId) {
        if (pages.containsKey(page)) return;
        Identity identity = new Identity(contextId);
        pages.put(page, identity);
        identity.url = page.url();
        emit(event(InteractionSurfaceEvent.Kind.PAGE_OPENED, contextId, identity));
        page.onResponse(response -> {
            try {
                if (!response.request().isNavigationRequest() || response.request().frame() != page.mainFrame()) return;
                synchronized (this) {
                    identity.pendingResponseUrl = response.url();
                    identity.pendingResponseStatus = response.status();
                    if (identity.url.equals(response.url())) identity.httpStatus = response.status();
                }
            } catch (RuntimeException ignored) {
                // A detached request cannot establish the status of the selected document.
            }
        });
        page.onPopup(popup -> {
            synchronized (this) {
                register(popup, contextId);
                Identity child = pages.get(popup);
                if (child == null) return;
                child.openerId = identity.pageId;
                emit(event(InteractionSurfaceEvent.Kind.SURFACE_OBSERVED, contextId, child));
            }
        });
        page.onFrameNavigated(frame -> {
            if (frame != page.mainFrame()) return;
            synchronized (this) {
                identity.documentId = "document:" + UUID.randomUUID();
                identity.generation++;
                identity.url = frame.url();
                identity.httpStatus = identity.url.equals(identity.pendingResponseUrl) ? identity.pendingResponseStatus : 0;
                identity.pendingResponseUrl = "";
                identity.pendingResponseStatus = 0;
                emit(event(InteractionSurfaceEvent.Kind.PAGE_NAVIGATED, contextId, identity));
            }
        });
        page.onClose(ignored -> {
            synchronized (this) {
                identity.closed = true;
                emit(event(InteractionSurfaceEvent.Kind.PAGE_CLOSED, contextId, identity));
                pages.remove(page);
            }
        });
    }

    synchronized List<InteractionSurfaceEvent> snapshots() {
        return pages.values().stream().filter(value -> !value.closed && value.contextId.equals(currentContext))
                .sorted(Comparator.comparing(value -> value.pageId))
                .limit(32).map(value -> event(InteractionSurfaceEvent.Kind.SURFACE_CHECKPOINT, value.contextId, value))
                .toList();
    }

    synchronized String pageId(Page page) {
        Identity identity = pages.get(page);
        return identity == null || identity.closed ? "" : identity.pageId;
    }

    synchronized int mainDocumentStatus(Page page) {
        Identity identity = pages.get(page);
        return identity == null || identity.closed ? 0 : identity.httpStatus;
    }

    synchronized void directlyCreated(Page page, String previousPageId) {
        Identity identity = pages.get(page);
        if (identity == null || !"web_tab_new".equals(InteractionInvocation.currentTool())
                || InteractionInvocation.currentRun() == null || InteractionInvocation.current().isBlank()) return;
        emit(event(InteractionSurfaceEvent.Kind.PAGE_CREATED, identity.contextId, identity)
                .withDirectCreation(InteractionInvocation.current(), previousPageId));
    }

    synchronized boolean expectedPopup(Page source, Page popup) {
        Identity parent = pages.get(source);
        Identity child = pages.get(popup);
        if (parent == null || child == null || child.closed || !child.openerId.equals(parent.pageId)
                || !"web_click".equals(InteractionInvocation.currentTool())
                || InteractionInvocation.currentRun() == null || InteractionInvocation.current().isBlank()) return false;
        emit(event(InteractionSurfaceEvent.Kind.PAGE_POPUP_MATCHED, child.contextId, child)
                .withExpectedPopup(InteractionInvocation.current(), parent.pageId));
        return true;
    }

    synchronized java.util.Optional<InteractionSurfaceEvent> snapshot(Page page) {
        Identity identity = pages.get(page);
        return identity == null || identity.closed ? java.util.Optional.empty()
                : java.util.Optional.of(event(InteractionSurfaceEvent.Kind.SURFACE_CHECKPOINT, identity.contextId, identity));
    }

    private InteractionSurfaceEvent event(InteractionSurfaceEvent.Kind kind, String contextId, Identity identity) {
        String url = identity == null ? "" : identity.url;
        String opener = identity == null ? "" : identity.openerId;
        return new InteractionSurfaceEvent(UUID.randomUUID().toString(), Instant.now(),
                InteractionSurfaceEvent.Mode.BROWSER, kind, runtimeId, contextId,
                identity == null ? "" : identity.pageId, identity == null ? "" : identity.documentId,
                "", "", identity == null ? 0 : identity.generation, 0, opener,
                opener.isBlank() ? InteractionSurfaceEvent.Relation.UNKNOWN : InteractionSurfaceEvent.Relation.OPENER,
                opener.isBlank() ? InteractionSurfaceEvent.RelationProof.UNKNOWN : InteractionSurfaceEvent.RelationProof.HOST_PROVEN,
                InteractionInvocation.current(), origin(url), hash(url));
    }

    private void emit(InteractionSurfaceEvent event) {
        try { observer.accept(event); }
        catch (RuntimeException failure) {
            // Supplemental audit must not turn an already dispatched browser action into a retry.
            log.warn("Browser surface journal unavailable ({})", failure.getClass().getSimpleName());
        }
    }

    private static String origin(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null) return "";
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (Exception invalid) { return ""; }
    }

    private static String hash(String value) {
        if (value.isBlank()) return "";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Identity {
        final String contextId;
        final String pageId = "page:" + UUID.randomUUID();
        String documentId = "document:" + UUID.randomUUID();
        String openerId = "";
        String url = "";
        String pendingResponseUrl = "";
        int pendingResponseStatus;
        int httpStatus;
        long generation = 1;
        boolean closed;
        Identity(String contextId) { this.contextId = contextId; }
    }
}
