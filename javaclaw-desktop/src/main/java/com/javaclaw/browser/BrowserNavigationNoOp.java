package com.javaclaw.browser;

import com.javaclaw.util.ProjectAccessPolicy;
import com.javaclaw.framework.spi.BrowserNavigationNoOpProvider.Prepared;
import com.javaclaw.framework.spi.BrowserNavigationNoOpProvider.Completion;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.BrowserContext;

/** 对已到达目标的受控页面作有界复验，不派发或重放导航。 */
final class BrowserNavigationNoOp {
    private final PlaywrightBrowserManager browserManager;
    private final BrowserOperationGate gate;

    BrowserNavigationNoOp(PlaywrightBrowserManager browserManager, BrowserOperationGate gate) {
        this.browserManager = browserManager;
        this.gate = gate;
    }

    java.util.Optional<Prepared> prepare(
            com.fasterxml.jackson.databind.JsonNode arguments,
            com.javaclaw.framework.spi.ToolExecutionContext execution,
            com.fasterxml.jackson.databind.JsonNode observedIdentity) {
        String destination = com.javaclaw.framework.spi.BrowserReceiptProof.canonicalUrl(
                arguments.path("url").asText(""));
        if (destination.isBlank()) return java.util.Optional.empty();
        NavigationState admitted;
        if (!gate.tryEnter()) return java.util.Optional.empty();
        try {
            execution.cancellation().throwIfCancelled();
            ProjectAccessPolicy.requireSafeBrowserUrl(destination);
            admitted = navigationState(destination, observedIdentity, false);
        } catch (RuntimeException unavailable) {
            execution.cancellation().throwIfCancelled();
            return java.util.Optional.empty();
        } finally { gate.exit(); }
        if (admitted == null) return java.util.Optional.empty();
        var used = new java.util.concurrent.atomic.AtomicBoolean();
        return java.util.Optional.of((context, clock) -> {
            try {
                if (!used.compareAndSet(false, true) || !context.runId().equals(execution.runId())
                        || !context.invocationId().equals(execution.invocationId()))
                    return navigationNoOpResult(context, clock.instant(), null);
                context.cancellation().throwIfCancelled();
                gate.enter();
                try {
                    context.cancellation().throwIfCancelled();
                    NavigationState current = navigationState(destination, observedIdentity, true);
                    // 再次核验实际页面、上下文、文档与 URL，不降级派发导航。
                    if (current == null || current.page() != admitted.page()
                            || current.context() != admitted.context()
                            || !sameNavigationIdentity(current.identity(), admitted.identity()))
                        return navigationNoOpResult(context, clock.instant(), null);
                    return navigationNoOpResult(context, clock.instant(), current);
                } finally { gate.exit(); }
            } catch (RuntimeException unavailable) {
                return navigationNoOpResult(context, clock.instant(), null);
            }
        });
    }

    private NavigationState navigationState(String destination,
            com.fasterxml.jackson.databind.JsonNode observedIdentity, boolean liveRead) {
        Page selected = browserManager.existingActivePage();
        if (selected == null) return null;
        BrowserContext context = selected.context();
        var before = browserManager.interactionSurface(selected).orElse(null);
        if (!matchesObservedIdentity(before, observedIdentity)) return null;
        String url = selected.url();
        if (liveRead) {
            // 仅在有界工具载体内执行固定宿主读取，绝不导航。
            Object value = selected.evaluate("() => ({url: window.location.href, ready: document.readyState})");
            if (!(value instanceof java.util.Map<?, ?> state) || !(state.get("url") instanceof String actual)
                    || !(state.get("ready") instanceof String ready)
                    || !java.util.Set.of("interactive", "complete").contains(ready)) return null;
            url = actual;
        }
        var after = browserManager.interactionSurface(selected).orElse(null);
        if (browserManager.existingActivePage() != selected || selected.context() != context
                || !sameNavigationIdentity(before, after) || !matchesObservedIdentity(after, observedIdentity)
                || !after.urlHash().equals(com.javaclaw.framework.spi.InteractionStageContext.sha256(url))
                || !destination.equals(com.javaclaw.framework.spi.BrowserReceiptProof.canonicalUrl(url))) return null;
        return new NavigationState(selected, context, after, url);
    }

    private static boolean matchesObservedIdentity(com.javaclaw.framework.api.InteractionSurfaceEvent identity,
            com.fasterxml.jackson.databind.JsonNode observed) {
        return identity != null && observed != null
                && identity.runtimeId().equals(observed.path("runtimeId").asText())
                && identity.contextId().equals(observed.path("contextId").asText())
                && identity.surfaceId().equals(observed.path("surfaceId").asText())
                && identity.documentId().equals(observed.path("documentId").asText())
                && identity.generation() == observed.path("generation").asLong(-1);
    }

    private static boolean sameNavigationIdentity(com.javaclaw.framework.api.InteractionSurfaceEvent first,
            com.javaclaw.framework.api.InteractionSurfaceEvent second) {
        return first != null && second != null && first.runtimeId().equals(second.runtimeId())
                && first.contextId().equals(second.contextId()) && first.surfaceId().equals(second.surfaceId())
                && first.documentId().equals(second.documentId()) && first.generation() == second.generation();
    }

    private static Completion navigationNoOpResult(com.javaclaw.framework.spi.ToolExecutionContext context,
            java.time.Instant at, NavigationState current) {
        boolean accepted = current != null;
        var data = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        data.put("reusedExisting", accepted);
        data.put("dispatchAttempted", false);
        String message = accepted ? "当前受控页面已处于目标 URL；已重新确认，未执行跳转。请重新观察页面正文。"
                : "受控页面或文档身份已变化；未派发导航，请重新观察。";
        var metadata = new java.util.LinkedHashMap<String, String>();
        metadata.put("delivery", "NOT_SENT");
        metadata.put("effect", "NONE");
        metadata.put("dispatchAttempted", "false");
        metadata.put("reusedExisting", Boolean.toString(accepted));
        String target = "";
        if (accepted) {
            metadata.putAll(com.javaclaw.framework.spi.BrowserReceiptProof.urlMetadata(current.url()));
            var identity = current.identity();
            metadata.put("runtimeId", identity.runtimeId());
            metadata.put("contextId", identity.contextId());
            metadata.put("surfaceId", identity.surfaceId());
            metadata.put("documentId", identity.documentId());
            metadata.put("generation", Long.toString(identity.generation()));
            metadata.put("capturedAtMillis", Long.toString(at.toEpochMilli()));
            try {
                var uri = java.net.URI.create(current.url());
                target = new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                        uri.getPath(), null, null).toString();
                if (target.length() > 512) target = target.substring(0, 512);
            } catch (java.net.URISyntaxException invalid) { throw new IllegalStateException(invalid); }
            data.put("url", target);
        }
        return new Completion(new com.javaclaw.framework.spi.ToolExecutionResultV1(
                accepted ? com.javaclaw.framework.api.ToolExecutionStatus.SUCCEEDED
                        : com.javaclaw.framework.api.ToolExecutionStatus.FAILED,
                data, accepted ? "" : "BROWSER_NAVIGATION_STATE_CHANGED", message),
                new com.javaclaw.framework.spi.EffectReceiptV1(context.invocationId(), "web_navigate", "navigate",
                        target, accepted ? com.javaclaw.framework.spi.EffectReceiptV1.Status.ACCEPTED
                                : com.javaclaw.framework.spi.EffectReceiptV1.Status.FAILED,
                        at, "core.tool.completed:" + context.runId().value() + ":" + context.invocationId(),
                        accepted ? "host rechecked the current managed page; navigation was not dispatched"
                                : "managed page state changed; navigation was not dispatched", "", metadata));
    }

    private record NavigationState(Page page, BrowserContext context,
            com.javaclaw.framework.api.InteractionSurfaceEvent identity, String url) { }

}
