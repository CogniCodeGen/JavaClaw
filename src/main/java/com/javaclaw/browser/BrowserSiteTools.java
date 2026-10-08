package com.javaclaw.browser;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.site.SiteCredential;
import com.javaclaw.site.SiteCredentialManager;
import com.javaclaw.util.ProjectAccessPolicy;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Site navigation, account selection and authenticated-session tools. */
@com.javaclaw.framework.spi.ToolContract(group = "web", permissions = {"tool.execute"}, idempotent = false)
final class BrowserSiteTools implements com.javaclaw.framework.spi.EffectTargetProvider,
        com.javaclaw.framework.spi.InteractionSurfaceProvider, com.javaclaw.framework.spi.ToolRuntimeContextProvider,
        com.javaclaw.framework.spi.ToolUserInputProvider,
        com.javaclaw.framework.spi.BrowserNavigationNoOpProvider {

    @Override public java.util.List<com.fasterxml.jackson.databind.JsonNode> currentContext() {
        return BrowserInteractionContext.current(browserManager);
    }

    @Override public void bindInteractionObserver(java.util.function.Consumer<com.javaclaw.framework.api.InteractionSurfaceEvent> observer) {
        browserManager.bindInteractionObserver(observer);
    }
    @Override public java.util.List<com.javaclaw.framework.api.InteractionSurfaceEvent> currentInteractionSurfaces() {
        return browserManager.interactionSurfaces();
    }

    private static final Logger log = LoggerFactory.getLogger(BrowserSiteTools.class);

    private final PlaywrightBrowserManager browserManager;
    private final SiteCredentialManager siteCredentials;
    private final SnapshotManager snapshotManager;
    private final ToolCallOrigin origin;
    private final BrowserOperationGate gate;
    private final BrowserTargetResolver targets;
    private final SiteSessionRestorer sessionRestorer;
    private final JsonCodec json;
    private final BrowserInteractionState interaction;
    private final boolean eventDrivenInteraction;
    private final ThreadLocal<StagedInput> stagedInput = new ThreadLocal<>();

    BrowserSiteTools(
            PlaywrightBrowserManager browserManager,
            SiteCredentialManager siteCredentials,
            SnapshotManager snapshotManager,
            ToolCallOrigin origin,
            BrowserOperationGate gate,
            JsonCodec json) {
        this(browserManager, siteCredentials, snapshotManager, origin, gate, json,
                new BrowserInteractionState(), false);
    }

    BrowserSiteTools(PlaywrightBrowserManager browserManager, SiteCredentialManager siteCredentials,
            SnapshotManager snapshotManager, ToolCallOrigin origin, BrowserOperationGate gate,
            JsonCodec json, BrowserInteractionState interaction, boolean eventDrivenInteraction) {
        this.browserManager = java.util.Objects.requireNonNull(browserManager, "browserManager");
        this.siteCredentials = java.util.Objects.requireNonNull(siteCredentials, "siteCredentials");
        this.snapshotManager = java.util.Objects.requireNonNull(snapshotManager, "snapshotManager");
        this.origin = java.util.Objects.requireNonNull(origin, "origin");
        this.gate = java.util.Objects.requireNonNull(gate, "gate");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.targets = new BrowserTargetResolver(snapshotManager);
        this.sessionRestorer = new SiteSessionRestorer(json);
        this.interaction = java.util.Objects.requireNonNull(interaction, "interaction");
        this.eventDrivenInteraction = eventDrivenInteraction;
    }

    @Override public String effectTarget() {
        gate.enter();
        try {
            Page page = browserManager.getActivePage();
            return page == null ? "" : page.url();
        } finally { gate.exit(); }
    }

    @Override public java.util.Optional<Prepared> prepareNavigationNoOp(
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
                    // Recheck the actual objects, document and URL. There is no fallback dispatch.
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
            // Only the bounded tool carrier performs this fixed host read, never a navigation.
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

    @Tool(
            name = "web_navigate",
            description =
                    "导航到指定 URL 地址。自动补全 https:// 前缀。导航后建议使用 web_snapshot 获取页面元素。"
                            + "若站点已保存会话会自动恢复；交互任务检测到登录页时，会打开可见浏览器让用户本人登录，并在成功后询问是否保存站点。")
    public String navigate(
            @ToolParam(
                            description = "目标 URL 地址，例如 www.baidu.com 或 https://github.com")
                    String url) {
        gate.enter();
        try {
            log.debug("工具调用: web_navigate({})", url);
            try {
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_navigate", "导航到: " + url)) {
                    return ToolResponse.error("web_navigate", "用户取消了操作");
                }

                String normalizedUrl =
                        ProjectAccessPolicy.requireSafeBrowserUrl(
                                PlaywrightBrowserManager.normalizeUrl(url));
                SiteResolution siteResolution = resolveSiteForNavigation(normalizedUrl);
                if (siteResolution.error() != null) {
                    return ToolResponse.error("web_navigate", siteResolution.error());
                }
                SiteCredential site = siteResolution.credential();
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_navigate", "浏览器未启动");

                // 站点匹配：导航前若有已存储会话 → 注入到 BrowserContext，避免再次登录
                boolean sessionRestored = false;
                if (site != null) {
                    String storage = siteCredentials.readSession(site.getId());
                    if (storage != null && !storage.isBlank()) {
                        sessionRestored = sessionRestorer.restore(page, storage, normalizedUrl);
                    }
                }

                Response response =
                        page.navigate(
                                normalizedUrl,
                                new Page.NavigateOptions()
                                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                int status = (response != null) ? response.status() : 0;
                String title = page.title();

                // 导航后自动清除旧引用
                snapshotManager.clearRefs();

                SiteLoginSupport.LoginAssessment login = assessLoginPage(page, status);
                if (login.loginRequired()) {
                    if (eventDrivenInteraction) {
                        com.javaclaw.framework.spi.ToolEffectCapture.noteTarget("web_navigate", page.url());
                        return beginAuthentication("web_navigate", normalizedUrl, page.url(),
                                login.reason(), true, page.context().storageState());
                    }
                    if (origin.kind() == ToolCallOrigin.Kind.INTERACTIVE) {
                        return performInteractiveLogin(
                                "web_navigate", normalizedUrl, page.url(), login.reason(), true);
                    }
                    String modeHint =
                            origin.kind() == ToolCallOrigin.Kind.SCHEDULED
                                    ? "定时任务无法等待用户登录，请先在交互聊天中登录并保存站点后重试。"
                                    : "当前为托管任务，请先在交互聊天中登录并保存站点，再继续任务。";
                    return ToolResponse.pending(
                            "web_navigate",
                            String.format(
                                    "已导航到: %s%n标题: %s%nHTTP状态: %d%n" + "[登录] 检测到页面需要身份验证（%s）。%s",
                                    normalizedUrl, title, status, login.reason(), modeHint));
                }

                String sessionRestoreNote = "";
                if (sessionRestored && site != null) {
                    siteCredentials.touchUsage(site.getId());
                    sessionRestoreNote = "\n[站点] 已恢复 " + site.getName() + " 的已保存会话";
                }

                // 若站点已登记但没有可恢复会话，按是否保存账号密码给出对应路径
                String credentialHint = "";
                if (site != null && !sessionRestored) {
                    if (hasStoredPassword(site)) {
                        credentialHint =
                                String.format(
                                        "\n[站点] 已登记账号（用户名: %s），但无可用会话；需要时可调用 site_login_now。",
                                        safeUsername(site.getUsername()));
                    } else {
                        credentialHint = "\n[站点] 已登记为浏览器会话登录；会话不可用时请调用 site_login_interactive。";
                    }
                }

                return ToolResponse.success(
                        "web_navigate",
                        String.format(
                                "已导航到: %s\n标题: %s\nHTTP状态: %d%s%s\n提示: 使用 web_snapshot 获取页面可交互元素",
                                normalizedUrl, title, status, sessionRestoreNote, credentialHint));
            } catch (PlaywrightException e) {
                log.error("web_navigate 执行异常", e);
                return ToolResponse.error("web_navigate", "导航失败: " + e.getMessage());
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== 站点管理工具 ====================

    @Override public void bindUserInputRun(String runId) {
        if (eventDrivenInteraction) interaction.bindAuthorizationRun(runId);
    }

    @Override public void prepareUserInputAnswer(com.fasterxml.jackson.databind.JsonNode question,
            com.fasterxml.jackson.databind.JsonNode answer, long questionSequence, long answerSequence) {
        if (!eventDrivenInteraction || questionSequence <= 0 || answerSequence <= questionSequence) return;
        String challenge = question.path("challengeId").asText("");
        String text = answer.path("text").asText("").strip();
        if (challenge.isBlank() || text.isBlank()) return;
        if (question.path("kind").asText().equals("browser.authentication_required")) {
            var pending = interaction.authentication();
            if (pending != null && pending.challengeId().equals(challenge)) interaction.answerAuthentication(challenge);
        } else if (question.path("kind").asText().equals("browser.account_selection_required")) {
            String questionOrigin = question.path("origin").asText("");
            var options = question.path("options");
            if (questionOrigin.isBlank() || !options.isArray() || options.size() > 21) return;
            List<String> allowed = new ArrayList<>();
            options.forEach(option -> {
                String id = option.path("accountId").asText("");
                if (!id.isBlank() && id.length() <= 256) allowed.add(id);
            });
            var pending = interaction.accountChoice();
            if (pending == null) {
                // A host question survives process recovery; no cookie/password baseline is reconstructed.
                pending = new BrowserInteractionState.PendingAccountChoice(challenge, questionOrigin, allowed);
                interaction.accountChoice(pending);
            }
            if (pending.challengeId().equals(challenge) && pending.origin().equals(questionOrigin)
                    && pending.accountIds().contains(text) && allowed.contains(text)) interaction.approveAccount(text);
        }
    }

    @Override public com.javaclaw.framework.spi.ToolUserInputCheckpoint consumeUserInputCheckpoint(String tool) {
        StagedInput staged = stagedInput.get();
        stagedInput.remove();
        return staged != null && staged.tool().equals(tool) ? staged.checkpoint() : null;
    }

    private void stageInput(String tool, com.javaclaw.framework.spi.ToolUserInputCheckpoint.Phase phase,
            com.fasterxml.jackson.databind.node.ObjectNode context, String reason) {
        stagedInput.set(new StagedInput(tool, new com.javaclaw.framework.spi.ToolUserInputCheckpoint(phase, context, reason)));
        com.javaclaw.framework.spi.ToolEffectCapture.noteData(tool, context);
    }

    private void stageAccountChoice(String tool, String url, List<SiteCredential> matches) {
        var context = json.mapper().createObjectNode();
        String challenge = java.util.UUID.randomUUID().toString();
        String siteOrigin = siteOrigin(url);
        context.put("kind", "browser.account_selection_required");
        context.put("challengeId", challenge);
        context.put("origin", siteOrigin);
        context.put("question", "请选择本次使用的账号：回复下列准确 accountId，或回复 __new__ 使用全新账号。选择后将显式调用 site_select_account；尚未切换账号或导航。");
        context.put("nextTool", "site_select_account");
        var options = context.putArray("options");
        List<String> allowed = new ArrayList<>();
        for (SiteCredential candidate : matches.stream().limit(20).toList()) {
            String label = nullToEmpty(candidate.getName()) + "（" + safeUsername(candidate.getUsername()) + "）";
            options.addObject().put("accountId", candidate.getId())
                    .put("label", label.substring(0, Math.min(160, label.length())))
                    .put("hasSession", candidate.isHasSession());
            allowed.add(candidate.getId());
        }
        options.addObject().put("accountId", "__new__").put("label", "全新空白账号").put("hasSession", false);
        allowed.add("__new__");
        interaction.accountChoice(new BrowserInteractionState.PendingAccountChoice(challenge, siteOrigin, allowed));
        com.javaclaw.framework.spi.ToolEffectCapture.noteTarget(tool, url);
        stageInput(tool, com.javaclaw.framework.spi.ToolUserInputCheckpoint.Phase.NOT_SENT,
                context, "BROWSER_ACCOUNT_REQUIRED");
    }

    private String beginAuthentication(String tool, String targetUrl, String loginUrl, String reason,
            boolean protectedTargetObserved, String baseline) {
        var pending = interaction.authentication();
        if (pending == null || !pending.targetUrl().equals(targetUrl)) {
            browserManager.keepSessionTransientUntilTaskReset(baseline);
            pending = new BrowserInteractionState.PendingAuthentication(java.util.UUID.randomUUID().toString(),
                    targetUrl, loginUrl, baseline, protectedTargetObserved);
            interaction.authentication(pending);
            // This is part of the established host login action, never replayed by site_auth_check.
            try { browserManager.showPageForUser(loginUrl); }
            catch (PlaywrightException unavailable) {
                log.warn("Visible authentication page unavailable ({})", unavailable.getClass().getSimpleName());
                return authenticationRequired(tool, pending, "导航/登录动作已执行；可见登录窗口未确认打开，请检查浏览器后回复", false);
            } finally { snapshotManager.clearRefs(); }
        }
        return authenticationRequired(tool, pending, reason, false);
    }

    private String authenticationRequired(String tool, BrowserInteractionState.PendingAuthentication pending,
            String reason, boolean renewChallenge) {
        if (renewChallenge) {
            pending = new BrowserInteractionState.PendingAuthentication(java.util.UUID.randomUUID().toString(),
                    pending.targetUrl(), pending.loginUrl(), pending.baselineState(), pending.protectedTargetObserved());
            interaction.authentication(pending);
        }
        var context = json.mapper().createObjectNode();
        context.put("kind", "browser.authentication_required");
        context.put("challengeId", pending.challengeId());
        context.put("origin", siteOrigin(pending.targetUrl()));
        context.put("authRequired", true);
        context.put("authVerified", false);
        context.put("sessionSaved", false);
        context.put("reason", reason == null ? "" : reason);
        context.put("question", "请在当前可见浏览器完成登录、验证码或双因素认证，并停留在目标站点的受保护页面，然后回复。回复只触发 site_auth_check 读取核验，不代表登录成功或同意保存会话；不会重放原导航。若当前页已关闭，先显式列出并选择目标页面。");
        context.put("nextTool", "site_auth_check");
        stageInput(tool, com.javaclaw.framework.spi.ToolUserInputCheckpoint.Phase.RESULT_ESTABLISHED,
                context, "BROWSER_AUTHENTICATION_REQUIRED");
        return ToolResponse.success(tool, "本次导航/工具动作已结束，登录尚未验证；等待用户完成登录后读取核验，本次未保存会话");
    }

    @Tool(name = "site_auth_check", description = "用户完成手动登录并回复宿主后，只读核验当前页面的登录挑战、真实主文档响应及目标站点会话变化；不导航、不提交、不保存、不读取原始凭据。成功后仍须 web_snapshot 获取新页面证据。")
    @com.javaclaw.framework.spi.ToolContract(group = "web", permissions = {"tool.read"}, idempotent = true)
    public String siteAuthCheck() {
        gate.enter();
        try {
            var pending = interaction.authentication();
            if (!eventDrivenInteraction || pending == null) {
                return ToolResponse.error("site_auth_check", "当前没有可核验的宿主登录基线；请先观察页面，必要时显式发起交互登录。不会重放原导航");
            }
            if (!interaction.authenticationAnswered(pending.challengeId())) {
                return authenticationRequired("site_auth_check", pending, "尚未收到当前登录问题的用户回复", false);
            }
            Page page = browserManager.getActivePage();
            if (page == null) return authenticationRequired("site_auth_check", pending,
                    "当前未选择页面；请显式 web_tab_list、web_tab_switch 后观察，不自动选择弹窗", true);
            // The DOM reads pump Playwright events before inspecting the matching document response.
            SiteLoginSupport.LoginSignals observed = loginSignals(page, 0);
            SiteLoginSupport.LoginSignals signals = new SiteLoginSupport.LoginSignals(
                    browserManager.mainDocumentStatus(page), observed.credentialForm(), observed.challengeForm());
            String before = SiteLoginSupport.filterStorageStateForUrl(pending.baselineState(), pending.targetUrl(), json);
            String after = SiteLoginSupport.filterStorageStateForUrl(page.context().storageState(), pending.targetUrl(), json);
            boolean protectedObserved = pending.protectedTargetObserved();
            if (!protectedObserved && java.util.Objects.equals(SiteLoginSupport.hostOf(pending.targetUrl()), SiteLoginSupport.hostOf(page.url()))
                    && SiteLoginSupport.assess(signals).loginRequired()) {
                pending = new BrowserInteractionState.PendingAuthentication(pending.challengeId(), pending.targetUrl(),
                        pending.loginUrl(), pending.baselineState(), true);
                interaction.authentication(pending);
                protectedObserved = true;
            }
            if (!sameAuthenticationTarget(pending.targetUrl(), page.url())
                    || SiteLoginSupport.verifyLogin(signals, !java.util.Objects.equals(before, after),
                    pending.targetUrl(), page.url(), protectedObserved) != SiteLoginSupport.VerificationStatus.AUTHENTICATED) {
                return authenticationRequired("site_auth_check", pending,
                        "当前页面/目标站点会话变化尚不足以验证登录，请完成登录并回到原受挑战的目标页面后回复；不会自动重访", true);
            }
            browserManager.keepSessionTransientUntilTaskReset(pending.baselineState());
            interaction.clearAuthentication();
            snapshotManager.clearRefs();
            var data = json.mapper().createObjectNode().put("kind", "browser.authentication_check")
                    .put("authVerified", true).put("sessionSaved", false).put("nextTool", "web_snapshot");
            com.javaclaw.framework.spi.ToolEffectCapture.noteData("site_auth_check", data);
            return ToolResponse.success("site_auth_check", "当前目标站点登录态已核验，本次未保存会话；请用 web_snapshot 读取当前页面");
        } catch (PlaywrightException unavailable) {
            var pending = interaction.authentication();
            if (pending != null) return authenticationRequired("site_auth_check", pending, "当前页面暂不可读；请检查后回复", true);
            return ToolResponse.error("site_auth_check", "当前页面暂不可读，登录未验证");
        } finally { gate.exit(); }
    }

    private static String siteOrigin(String url) {
        try {
            var value = java.net.URI.create(url);
            return new java.net.URI(value.getScheme(), null, value.getHost(), value.getPort(), null, null, null).toString();
        } catch (Exception invalid) { return ""; }
    }

    private static boolean sameAuthenticationTarget(String expected, String actual) {
        try {
            var left = java.net.URI.create(expected).normalize();
            var right = java.net.URI.create(actual).normalize();
            if (left.getHost() == null || right.getHost() == null) return false;
            int leftPort = left.getPort() >= 0 ? left.getPort() : "https".equalsIgnoreCase(left.getScheme()) ? 443 : 80;
            int rightPort = right.getPort() >= 0 ? right.getPort() : "https".equalsIgnoreCase(right.getScheme()) ? 443 : 80;
            String leftPath = left.getRawPath() == null || left.getRawPath().isEmpty() ? "/" : left.getRawPath();
            String rightPath = right.getRawPath() == null || right.getRawPath().isEmpty() ? "/" : right.getRawPath();
            return left.getScheme().equalsIgnoreCase(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost())
                    && leftPort == rightPort && leftPath.equals(rightPath)
                    && java.util.Objects.equals(left.getRawQuery(), right.getRawQuery())
                    && java.util.Objects.equals(left.getRawFragment(), right.getRawFragment());
        } catch (RuntimeException invalid) { return false; }
    }

    private record StagedInput(String tool, com.javaclaw.framework.spi.ToolUserInputCheckpoint checkpoint) { }

    @Tool(
            name = "site_select_account",
            description =
                    "为当前聊天/任务选择访问某站点时使用的账号身份。"
                            + "同一站点保存多个账号或用户明确要求换号时，必须先调用本工具；"
                            + "使用 newAccount=true 选择全新空白账号，或 newAccount=false 并传准确 accountId。"
                            + "切换会创建干净 BrowserContext，旧账号数据不会混入。")
    public String siteSelectAccount(
            @ToolParam( description = "目标站点 URL，例如 https://github.com") String url,
            @ToolParam(required = false, description = "true 表示使用全新空白账号；否则传 accountId")
                    Boolean newAccount,
            @ToolParam(required = false, description = "已保存账号的准确配置 ID；newAccount=true 时留空")
                    String accountId) {
        gate.enter();
        try {
            log.debug("工具调用: site_select_account({}, newAccount={}, accountId={})",
                    url, newAccount, accountId);
            try {
                String normalizedUrl = PlaywrightBrowserManager.normalizeUrl(url);
                if (eventDrivenInteraction) normalizedUrl = ProjectAccessPolicy.requireSafeBrowserUrl(normalizedUrl);
                boolean createNew = Boolean.TRUE.equals(newAccount);
                String requested = accountId == null ? "" : accountId.strip();
                if (createNew == !requested.isBlank()) {
                    return ToolResponse.error("site_select_account",
                            "必须二选一：newAccount=true 且 accountId 为空，或 newAccount=false 且传准确账号 ID");
                }
                if (eventDrivenInteraction && (interaction.accountChoice() != null
                        || siteCredentials.findAllByUrl(normalizedUrl).size() > 1)) {
                    var pending = interaction.accountChoice();
                    String selection = createNew ? "__new__" : requested;
                    if (pending == null || !pending.origin().equals(siteOrigin(normalizedUrl))
                            || !interaction.accountApproved(selection)) {
                        stageAccountChoice("site_select_account", normalizedUrl,
                                siteCredentials.findAllByUrl(normalizedUrl));
                        return ToolResponse.error("site_select_account", "尚未收到本次账号问题的准确用户选择；未切换账号");
                    }
                }
                if (!eventDrivenInteraction && !ToolConfirmationManager.requestConfirmation(
                        origin, "site_select_account", "切换站点账号将清空当前浏览器会话状态: " + normalizedUrl)) {
                    return ToolResponse.error("site_select_account", "用户取消了账号切换");
                }

                SiteCredentialManager manager = siteCredentials;
                String scopeId = browserManager.getActiveScopeId();
                if (createNew) {
                    if (!manager.bindNewAccount(scopeId, normalizedUrl)) {
                        return ToolResponse.error("site_select_account", "保存新账号选择失败");
                    }
                    browserManager.replaceActiveContextWithBlank();
                    snapshotManager.clearRefs();
                    interaction.clearAccountChoice();
                    interaction.clearAuthentication();
                    return ToolResponse.success(
                            "site_select_account", "已为当前会话切换到全新空白账号；访问站点后可登录并另存为新账号");
                }

                List<SiteCredential> matches = manager.findAllByUrl(normalizedUrl);
                List<SiteCredential> selected = matches.stream()
                        .filter(candidate -> requested.equals(candidate.getId())).toList();
                if (selected.size() != 1) {
                    return ToolResponse.error(
                            "site_select_account",
                            "未找到账号 ID「" + requested + "」。可用账号: " + accountSummary(matches));
                }

                SiteCredential credential = selected.getFirst();
                if (!manager.bindAccount(scopeId, normalizedUrl, credential.getId())) {
                    return ToolResponse.error("site_select_account", "保存账号选择失败");
                }
                browserManager.replaceActiveContextWithBlank();
                snapshotManager.clearRefs();
                interaction.clearAccountChoice();
                interaction.clearAuthentication();
                return ToolResponse.success(
                        "site_select_account",
                        "当前会话已切换到账号「" + accountLabel(credential) + "」，下次导航将恢复该账号会话");
            } catch (Exception e) {
                log.error("切换站点账号失败", e);
                return ToolResponse.error("site_select_account", "切换账号失败: " + e.getMessage());
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "site_login_interactive",
            description =
                    "当页面要求登录时，临时打开可见浏览器让用户本人完成登录（支持 SSO、验证码和双因素认证）。"
                            + "用户确认登录完成后会校验页面，并询问是否保存站点会话；保存后下次访问自动登录。"
                            + "仅用于有用户在场的交互聊天。")
    public String siteLoginInteractive() {
        gate.enter();
        try {
            log.debug("工具调用: site_login_interactive");
            Page page = browserManager.getActivePage();
            if (page == null) {
                return ToolResponse.error("site_login_interactive", "浏览器未启动");
            }
            if (SiteLoginSupport.hostOf(page.url()) == null) {
                return ToolResponse.error(
                        "site_login_interactive", "当前页面不是可登录的网站，请先用 web_navigate 打开目标站点");
            }
            if (eventDrivenInteraction) {
                var pending = interaction.authentication();
                if (pending != null) return authenticationRequired("site_login_interactive", pending,
                        "请完成当前登录；回复后调用 site_auth_check，不重复导航", false);
                return beginAuthentication("site_login_interactive", page.url(), page.url(),
                        "用户请求交互式登录", SiteLoginSupport.assess(loginSignals(page,
                                browserManager.mainDocumentStatus(page))).loginRequired(), page.context().storageState());
            }
            if (origin.kind() != ToolCallOrigin.Kind.INTERACTIVE) {
                return ToolResponse.error(
                        "site_login_interactive", "当前任务无法等待用户操作；请在交互聊天中完成登录并保存站点后重试");
            }
            return performInteractiveLogin(
                    "site_login_interactive", page.url(), page.url(), "用户请求交互式登录",
                    SiteLoginSupport.assess(loginSignals(page, 0)).loginRequired());

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "site_login_now",
            description =
                    "在当前页面用「站点管理」中已登记的凭据自动填充并提交登录表单。"
                            + "工具内部根据当前页面 URL 匹配站点条目并填入已登记凭据，无需在参数中提供账号或密码。"
                            + "支持可选选择器覆盖默认表单语义定位（用户名/密码/提交按钮）。验证登录后会询问是否保存会话。")
    public String siteLoginNow(
            @ToolParam( description = "用户名输入框的 CSS 选择器；留空则按 autocomplete、类型及表单结构定位")
                    String usernameSelector,
            @ToolParam(
                            description = "密码输入框的 CSS 选择器；留空则按 input[type=password] 自动定位")
                    String passwordSelector,
            @ToolParam(
                            description = "提交按钮的 CSS 选择器；留空则定位表单提交控件")
                    String submitSelector) {
        gate.enter();
        try {
            log.debug("工具调用: site_login_now");
            String stateBeforeLogin = null;
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("site_login_now", "浏览器未启动");
                if (!eventDrivenInteraction && !ToolConfirmationManager.requestConfirmation(
                        origin, "site_login_now", "在当前页面 [" + page.url() + "] 用已登记凭据自动登录")) {
                    return ToolResponse.error("site_login_now", "用户取消了操作");
                }

                String currentUrl = page.url();
                SiteCredential site = selectedSiteForCurrentScope(currentUrl);
                if (site == null) {
                    List<SiteCredential> matches = siteCredentials.findAllByUrl(currentUrl);
                    if (matches.size() > 1) {
                        return ToolResponse.error(
                                "site_login_now",
                                "当前站点有多个账号，请先调用 site_select_account。可用账号: "
                                        + accountSummary(matches));
                    }
                    return ToolResponse.error(
                            "site_login_now",
                            "当前 URL " + currentUrl + " 未匹配任何站点凭据。请在「设置 → 站点管理」中登记。");
                }
                if (!hasStoredPassword(site)) {
                    return ToolResponse.error(
                            "site_login_now",
                            "该站点只保存了浏览器会话，没有账号密码；请调用 site_login_interactive 让用户本人登录");
                }

                boolean challengeBeforeSubmit = SiteLoginSupport.assess(
                        loginSignals(page, 0)).loginRequired();
                stateBeforeLogin = page.context().storageState();

                // 1) 用户名
                String userSel =
                        (usernameSelector != null && !usernameSelector.isBlank())
                                ? usernameSelector
                                : SiteLoginFormLocator.usernameSelector(page);
                if (userSel == null) {
                    return ToolResponse.error(
                            "site_login_now",
                            "未找到用户名输入框。请通过 web_snapshot 查看后用 username_selector 参数指定。");
                }
                browserManager.noteInteractionInput(page);
                page.fill(userSel, site.getUsername());

                // 2) 密码（直接由本工具读取，不进入 LLM 上下文）
                String passSel =
                        (passwordSelector != null && !passwordSelector.isBlank())
                                ? passwordSelector
                                : "input[type='password']";
                page.fill(passSel, site.getPassword());

                // 3) 提交
                String submitSel =
                        (submitSelector != null && !submitSelector.isBlank())
                                ? submitSelector
                                : SiteLoginFormLocator.submitSelector(page);
                String beforeUrl = page.url();
                if (submitSel != null) {
                    page.click(submitSel);
                } else {
                    page.locator(passSel).press("Enter");
                }

                // 4) 等待导航或表单消失（最多 10s）
                try {
                    page.waitForURL(
                            u -> !u.equals(beforeUrl),
                            new Page.WaitForURLOptions().setTimeout(10_000));
                } catch (PlaywrightException ignored) {
                    // 有些 SPA 不变更 URL，仍可能登录成功；继续走会话校验
                }

                // Revisit the original challenged page. A public destination with a new
                // tracking cookie cannot establish that the login succeeded.
                Response verificationResponse = page.navigate(currentUrl,
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                int verificationStatus = verificationResponse == null ? 0
                        : verificationResponse.status();
                SiteLoginSupport.LoginSignals signals = loginSignals(page, verificationStatus);
                SiteLoginSupport.LoginAssessment login = SiteLoginSupport.assess(signals);
                if (login.loginRequired()) {
                    browserManager.keepSessionTransientUntilTaskReset(stateBeforeLogin);
                    if (eventDrivenInteraction) return beginAuthentication("site_login_now", currentUrl, page.url(),
                            login.reason(), challengeBeforeSubmit, stateBeforeLogin);
                    return ToolResponse.error(
                            "site_login_now",
                            "提交后页面仍要求登录（"
                                    + login.reason()
                                    + "），可能需要验证码或双因素认证；请改用 site_login_interactive");
                }
                String beforeSiteState = SiteLoginSupport.filterStorageStateForUrl(
                        stateBeforeLogin, currentUrl, json);
                String afterSiteState = SiteLoginSupport.filterStorageStateForUrl(
                        page.context().storageState(), currentUrl, json);
                if (SiteLoginSupport.verifyLogin(signals,
                        !java.util.Objects.equals(beforeSiteState, afterSiteState),
                        currentUrl, page.url(), challengeBeforeSubmit)
                        != SiteLoginSupport.VerificationStatus.AUTHENTICATED) {
                    browserManager.keepSessionTransientUntilTaskReset(stateBeforeLogin);
                    if (eventDrivenInteraction) return beginAuthentication("site_login_now", currentUrl, page.url(),
                            "目标站点登录态尚未验证", challengeBeforeSubmit, stateBeforeLogin);
                    return ToolResponse.uncertain("site_login_now",
                            "登录提交已执行，但目标站点的持久登录态尚未验证；请重新访问受保护页面检查");
                }
                if (eventDrivenInteraction) {
                    browserManager.keepSessionTransientUntilTaskReset(stateBeforeLogin);
                    interaction.clearAuthentication();
                    snapshotManager.clearRefs();
                    return ToolResponse.success("site_login_now", "登录已验证，本次会话未保存；继续观察页面。若需保存，须另行批准 site_save_session");
                }
                boolean saved =
                        ToolConfirmationManager.requestExplicitUserConfirmation(
                                "保存站点",
                                "检测到「"
                                        + site.getName()
                                        + "」已登录。是否保存本次浏览器会话？\n"
                                        + "保存后，下次访问该站点会自动登录。",
                                120);
                if (saved) {
                    String storageState =
                            SiteLoginSupport.filterStorageStateForUrl(
                                    page.context().storageState(), currentUrl, json);
                    if (!siteCredentials.tryWriteSession(site.getId(), storageState)) {
                        browserManager.keepSessionTransientUntilTaskReset(stateBeforeLogin);
                        return ToolResponse.error(
                                "site_login_now", "登录已完成，但站点会话保存失败；本次仍可使用，重启后不会自动登录");
                    }
                } else {
                    browserManager.keepSessionTransientUntilTaskReset(stateBeforeLogin);
                }

                snapshotManager.clearRefs();
                return ToolResponse.success(
                        "site_login_now",
                        String.format(
                                "已使用 %s 的凭据登录，%s。当前 URL: %s",
                                site.getName(), saved ? "会话已保存，下次将自动登录" : "本次未保存站点会话", page.url()));
            } catch (PlaywrightException e) {
                browserManager.keepSessionTransientUntilTaskReset(stateBeforeLogin);
                log.error("site_login_now 执行异常", e);
                return ToolResponse.error("site_login_now", "自动登录失败: " + e.getMessage());
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "site_fill_password",
            description =
                    "把已登记的密码填入指定输入框。用于 site_login_now 启发式无法覆盖的非常规登录表单。"
                            + "站点管理器在内部读取已登记凭据，本工具不向 LLM 暴露密码。")
    public String siteFillPassword(
            @ToolParam(description = BrowserTargetResolver.TOOL_FORMAT)
                    String targetSelector) {
        gate.enter();
        try {
            log.debug("工具调用: site_fill_password({})", targetSelector);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("site_fill_password", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "site_fill_password", "填入已登记密码到: " + targetSelector)) {
                    return ToolResponse.error("site_fill_password", "用户取消了操作");
                }
                if (targetSelector == null || targetSelector.isBlank()) {
                    return ToolResponse.error("site_fill_password", "target_selector 不能为空");
                }

                SiteCredential site = selectedSiteForCurrentScope(page.url());
                if (site == null) {
                    return ToolResponse.error(
                            "site_fill_password", "当前 URL 未选择明确账号；同站点多账号时请先调用 site_select_account");
                }
                if (!hasStoredPassword(site)) {
                    return ToolResponse.error(
                            "site_fill_password", "该站点没有保存账号密码，请改用 site_login_interactive");
                }

                Locator locator = targets.resolve(page, targetSelector);
                if (locator == null) {
                    return ToolResponse.error("site_fill_password", "无法解析元素: " + targetSelector);
                }
                browserManager.noteInteractionInput(page);
                locator.fill(site.getPassword());
                return ToolResponse.success(
                        "site_fill_password", "已将 " + site.getName() + " 的密码填入 " + targetSelector);
            } catch (PlaywrightException e) {
                log.error("site_fill_password 执行异常", e);
                return ToolResponse.error("site_fill_password", "填充失败: " + e.getMessage());
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "site_save_session",
            description =
                    "把当前浏览器会话（cookies + localStorage）保存到站点，" + "站点尚未登记时会按当前域名自动创建；下次访问会自动恢复登录。")
    public String siteSaveSession() {
        gate.enter();
        try {
            log.debug("工具调用: site_save_session");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("site_save_session", "浏览器未启动");
                if (!eventDrivenInteraction && !ToolConfirmationManager.requestConfirmation(
                        origin, "site_save_session", "保存当前站点及登录会话: " + page.url())) {
                    return ToolResponse.error("site_save_session", "用户取消了操作");
                }

                SiteCredentialManager manager = siteCredentials;
                String scopeId = browserManager.getActiveScopeId();
                SiteCredential site = selectedSiteForCurrentScope(page.url());
                if (site == null) {
                    if (!manager.isNewAccountBound(scopeId, page.url())
                            && manager.findAllByUrl(page.url()).size() > 1) {
                        return ToolResponse.error(
                                "site_save_session", "当前站点有多个账号且尚未选择，请先调用 site_select_account");
                    }
                    site = newUniqueSessionSite(page.url(), page.url());
                }
                String storageState =
                        SiteLoginSupport.filterStorageStateForUrl(
                                page.context().storageState(), page.url(), json);
                site = manager.saveSessionChecked(site, storageState, scopeId, page.url());
                return ToolResponse.success(
                        "site_save_session", "已保存 " + site.getName() + " 的会话，下次访问该站点会自动恢复");
            } catch (Exception e) {
                log.error("site_save_session 执行异常", e);
                return ToolResponse.error("site_save_session", "站点会话未能确认写入数据库，请重试");
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "site_clear_session",
            description = "清除匹配的站点条目的已保存会话（cookies/storage），下次访问需重新登录。" + "凭据条目本身保留，仅删除其会话快照。")
    public String siteClearSession() {
        gate.enter();
        try {
            log.debug("工具调用: site_clear_session");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("site_clear_session", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "site_clear_session", "清除站点会话: " + page.url())) {
                    return ToolResponse.error("site_clear_session", "用户取消了操作");
                }

                SiteCredential site = selectedSiteForCurrentScope(page.url());
                if (site == null) {
                    return ToolResponse.error(
                            "site_clear_session", "当前 URL 未选择明确的站点账号；请先调用 site_select_account");
                }
                siteCredentials.clearSession(site.getId());
                return ToolResponse.success("site_clear_session", "已清除 " + site.getName() + " 的会话");
            } catch (Exception e) {
                log.error("site_clear_session 执行异常", e);
                return ToolResponse.error("site_clear_session", "清除失败: " + e.getMessage());
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== 站点工具内部辅助 ====================

    /** 打开可见浏览器等待用户本人登录，验证完成后询问是否持久化站点会话。 */
    private String performInteractiveLogin(
            String responseToolName, String targetUrl, String loginUrl, String detectionReason,
            boolean protectedTargetObserved) {
        boolean interactionAttempted = false;
        boolean persistSession = false;
        try {
            interactionAttempted = true;
            Page openedPage = browserManager.showPageForUser(loginUrl);
            String stateBeforeLogin = openedPage.context().storageState();

            boolean loginFinished =
                    ToolConfirmationManager.requestExplicitUserConfirmation(
                            "完成站点登录",
                            "已打开可见浏览器："
                                    + loginUrl
                                    + "\n"
                                    + "请在浏览器中完成登录、验证码或双因素认证。\n"
                                    + "确认页面已经登录成功后，回到此处点击「同意」；取消则不保存。",
                            600);
            if (!loginFinished) {
                return ToolResponse.error(responseToolName, "用户取消了交互式登录或等待超时");
            }

            Page page = browserManager.getActivePage();
            if (page == null) {
                return ToolResponse.error(responseToolName, "登录窗口已关闭，未取得浏览器会话");
            }

            // SSO 可能把用户留在身份提供方的完成页或弹窗；回到原目标页才是对“已登录”的有效验证。
            int verificationStatus = 0;
            if (SiteLoginSupport.hostOf(targetUrl) != null) {
                Response verificationResponse = page.navigate(
                        targetUrl,
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                verificationStatus = verificationResponse == null ? 0 : verificationResponse.status();
            }

            SiteLoginSupport.LoginSignals signals = loginSignals(page, verificationStatus);
            SiteLoginSupport.LoginAssessment assessment = SiteLoginSupport.assess(signals);
            if (assessment.loginRequired()) {
                return ToolResponse.error(
                        responseToolName,
                        "页面仍显示登录状态（"
                                + assessment.reason()
                                + "）。请确认登录完成后重新调用 site_login_interactive");
            }

            String finalUrl = page.url();
            String saveUrl = chooseSiteUrl(targetUrl, finalUrl);
            String storageState =
                    SiteLoginSupport.filterStorageStateForUrl(
                            page.context().storageState(), saveUrl, json);
            String comparableBeforeLogin =
                    SiteLoginSupport.filterStorageStateForUrl(stateBeforeLogin, saveUrl, json);
            if (SiteLoginSupport.verifyLogin(signals,
                    !java.util.Objects.equals(comparableBeforeLogin, storageState),
                    targetUrl, finalUrl, protectedTargetObserved)
                    != SiteLoginSupport.VerificationStatus.AUTHENTICATED) {
                return ToolResponse.uncertain(responseToolName,
                        "目标站点的持久登录态尚未验证；请完成登录并重新访问受保护页面后再保存会话");
            }
            SiteCredentialManager manager = siteCredentials;
            String scopeId = browserManager.getActiveScopeId();
            SiteCredential site = selectedSiteForCurrentScope(targetUrl);
            if (site == null) {
                site = selectedSiteForCurrentScope(finalUrl);
            }

            String siteName = site == null ? SiteLoginSupport.hostOf(saveUrl) : site.getName();
            if (siteName == null || siteName.isBlank()) siteName = "当前站点";

            boolean save =
                    ToolConfirmationManager.requestExplicitUserConfirmation(
                            "保存站点",
                            "检测到「"
                                    + siteName
                                    + "」已登录。是否保存该站点的浏览器会话？\n"
                                    + "仅保存 Cookie 与站点存储，不保存本次手动输入的账号密码；"
                                    + "下次访问时会自动登录。",
                            120);
            if (save) {
                if (site == null) {
                    site = newUniqueSessionSite(saveUrl, loginUrl);
                }
                try {
                    site = manager.saveSessionChecked(site, storageState, scopeId, saveUrl);
                } catch (RuntimeException persistenceFailure) {
                    log.error("交互式登录后的站点会话事务保存失败", persistenceFailure);
                    return ToolResponse.error(responseToolName, "登录已完成，但站点会话保存失败；本次仍可使用，重启后不会自动登录");
                }
                persistSession = true;
            }

            return ToolResponse.success(
                    responseToolName,
                    "用户登录已完成，"
                            + (save ? "站点会话已保存，下次访问将自动登录" : "本次会话已用于当前任务，但未保存站点")
                            + "。当前 URL: "
                            + finalUrl
                            + (detectionReason == null || detectionReason.isBlank()
                                    ? ""
                                    : "\n原登录判定: " + detectionReason));
        } catch (Exception e) {
            log.error("交互式站点登录失败", e);
            return ToolResponse.error(responseToolName, "交互式登录失败，请检查页面状态后重试");
        } finally {
            if (interactionAttempted) {
                try {
                    browserManager.resumeAfterUserInteraction(persistSession);
                } catch (Exception e) {
                    log.warn("恢复无头浏览器失败: {}", e.getMessage());
                }
            }
            snapshotManager.clearRefs();
        }
    }

    private String chooseSiteUrl(String targetUrl, String finalUrl) {
        String finalHost = SiteLoginSupport.hostOf(finalUrl);
        String targetHost = SiteLoginSupport.hostOf(targetUrl);
        if (finalHost != null && targetHost != null
                && !java.util.Objects.equals(targetHost, finalHost)) {
            return finalUrl;
        }
        return targetHost == null ? finalUrl : targetUrl;
    }

    private SiteResolution resolveSiteForNavigation(String url) {
        SiteCredentialManager manager = siteCredentials;
        String scopeId = browserManager.getActiveScopeId();
        if (manager.isNewAccountBound(scopeId, url)) {
            return SiteResolution.selected(null);
        }

        SiteCredential bound = manager.findBoundByUrl(scopeId, url);
        if (bound != null) return SiteResolution.selected(bound);

        List<SiteCredential> matches = manager.findAllByUrl(url);
        if (matches.isEmpty()) return SiteResolution.selected(null);
        if (matches.size() == 1) {
            SiteCredential only = matches.getFirst();
            manager.bindAccount(scopeId, url, only.getId());
            return SiteResolution.selected(only);
        }

        if (eventDrivenInteraction) {
            stageAccountChoice("web_navigate", url, matches);
            return SiteResolution.failed("需要用户明确选择站点账号；导航尚未发出");
        }

        if (origin.kind() != ToolCallOrigin.Kind.INTERACTIVE) {
            return SiteResolution.failed(
                    "站点存在多个已保存账号，当前任务不能猜测账号。请先调用 "
                            + "site_select_account(newAccount=false, accountId=准确 ID) 明确选择。可用账号: "
                            + accountSummary(matches));
        }

        List<ChoiceOption> options = new ArrayList<>();
        for (SiteCredential candidate : matches) {
            options.add(
                    new ChoiceOption(
                            candidate.getId(),
                            accountLabel(candidate),
                            candidate.isHasSession() ? "已保存登录会话" : "仅保存账号配置"));
        }
        options.add(new ChoiceOption("__new__", "使用新账号", "不恢复任何已保存登录状态"));
        String choice =
                ToolConfirmationManager.requestExplicitUserChoice(
                        "选择站点账号",
                        "「"
                                + SiteLoginSupport.hostOf(url)
                                + "」保存了多个账号。"
                                + "请选择本会话要使用的身份；选择只绑定当前会话。",
                        options,
                        120);
        if (choice == null) {
            return SiteResolution.failed("用户取消了站点账号选择");
        }

        boolean boundSuccessfully;
        SiteCredential selected;
        if ("__new__".equals(choice)) {
            boundSuccessfully = manager.bindNewAccount(scopeId, url);
            selected = null;
        } else {
            selected =
                    matches.stream()
                            .filter(candidate -> choice.equals(candidate.getId()))
                            .findFirst()
                            .orElse(null);
            boundSuccessfully =
                    selected != null && manager.bindAccount(scopeId, url, selected.getId());
        }
        if (!boundSuccessfully) {
            return SiteResolution.failed("保存当前会话的账号选择失败");
        }

        // 账号选择发生变化时必须换干净 Context，不能在旧账号 Cookie 上叠加新账号。
        browserManager.replaceActiveContextWithBlank();
        snapshotManager.clearRefs();
        return SiteResolution.selected(selected);
    }

    private SiteCredential selectedSiteForCurrentScope(String url) {
        SiteCredentialManager manager = siteCredentials;
        String scopeId = browserManager.getActiveScopeId();
        if (manager.isNewAccountBound(scopeId, url)) return null;
        SiteCredential bound = manager.findBoundByUrl(scopeId, url);
        if (bound != null) return bound;
        List<SiteCredential> matches = manager.findAllByUrl(url);
        if (matches.size() != 1) return null;
        SiteCredential only = matches.getFirst();
        manager.bindAccount(scopeId, url, only.getId());
        return only;
    }

    private SiteCredential newUniqueSessionSite(String targetUrl, String loginUrl) {
        SiteCredential site = SiteLoginSupport.newSessionSite(targetUrl, loginUrl);
        int existing = siteCredentials.findAllByUrl(targetUrl).size();
        if (existing > 0) {
            site.setName(site.getName() + "（账号 " + (existing + 1) + "）");
        }
        return site;
    }

    private static String accountSummary(List<SiteCredential> credentials) {
        if (credentials == null || credentials.isEmpty()) return "无";
        return credentials.stream()
                .map(site -> accountLabel(site) + " [id=" + site.getId() + "]")
                .collect(Collectors.joining("；"));
    }

    private static String accountLabel(SiteCredential site) {
        if (site == null) return "新账号";
        String name = nullToEmpty(site.getName());
        String username = nullToEmpty(site.getUsername());
        if (name.isBlank()) name = site.getHostPattern();
        return username.isBlank() ? name : name + "（" + username + "）";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record SiteResolution(SiteCredential credential, String error) {
        static SiteResolution selected(SiteCredential credential) {
            return new SiteResolution(credential, null);
        }

        static SiteResolution failed(String error) {
            return new SiteResolution(null, error);
        }
    }

    private SiteLoginSupport.LoginAssessment assessLoginPage(Page page, int status) {
        return SiteLoginSupport.assess(loginSignals(page, status));
    }

    private SiteLoginSupport.LoginSignals loginSignals(Page page, int status) {
        return new SiteLoginSupport.LoginSignals(
                        status,
                        hasVisible(
                                page,
                                "form:has(input[type='password']):has(input[autocomplete='username'],input[type='email']):has(button[type='submit'],input[type='submit'])",
                                "form:has(input[autocomplete='current-password']):has(button[type='submit'],input[type='submit'])"),
                        hasVisible(page,
                                "form:has(input[autocomplete='one-time-code']):has(button[type='submit'],input[type='submit'])"));
    }

    private boolean hasVisible(Page page, String... selectors) {
        if (page == null || selectors == null) return false;
        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector);
                int count = locator.count();
                for (int i = 0; i < count; i++) {
                    if (locator.nth(i).isVisible()) return true;
                }
            } catch (PlaywrightException ignored) {
                // 单个非标准页面选择器失败不应中断登录判定
            }
        }
        return false;
    }

    private static boolean hasStoredPassword(SiteCredential site) {
        return site != null
                && site.getUsername() != null
                && !site.getUsername().isBlank()
                && site.getPassword() != null
                && !site.getPassword().isBlank();
    }

    /** 用户名脱敏展示用（避免响应里完整泄漏） */
    private static String safeUsername(String u) {
        if (u == null || u.isBlank()) return "(未设置)";
        if (u.length() <= 3) return u.charAt(0) + "***";
        return u.substring(0, 2) + "***" + u.substring(Math.max(2, u.length() - 2));
    }
}
