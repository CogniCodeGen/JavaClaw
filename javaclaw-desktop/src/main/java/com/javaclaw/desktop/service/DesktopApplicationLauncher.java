package com.javaclaw.desktop.service;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.desktop.service.DefaultDesktopSessionService.ScopeKey;
import com.javaclaw.desktop.service.DefaultDesktopSessionService.Selection;

/** 持有进程级启动准入栅栏；新目录与会话关闭均不能授权重放未知启动。 */
final class DesktopApplicationLauncher {
    private static final long LAUNCH_DISCOVERY_MILLIS = 3_000;
    private static final long LAUNCH_DISCOVERY_INTERVAL_MILLIS = 200;
    private final DefaultDesktopSessionService service;
    private final Map<LaunchKey, LaunchAdmission> launchAdmissions = new ConcurrentHashMap<>();

    DesktopApplicationLauncher(DefaultDesktopSessionService service) { this.service = service; }

    void close() { launchAdmissions.clear(); }

    CompletionStage<DesktopApplicationLaunchResult> launchApplication(
            DesktopSessionOwner owner, String application) {
        if (owner == null) throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.INVALID_ARGUMENTS,
                "启动应用需要有效的工作区与运行来源");
        String name = validApplicationName(application);
        ScopeKey scope = new ScopeKey(owner.workspaceId(), owner.scopeId());
        long workspaceEpoch;
        long scopeEpoch;
        synchronized (service) {
            service.requireLaunchReady();
            workspaceEpoch = service.workspaceEpochs.getOrDefault(owner.workspaceId(), 0L);
            scopeEpoch = service.scopeEpochs.getOrDefault(scope, 0L);
        }
        return submitLaunch(() -> {
            Selection selected = service.admitLaunch(owner, scope, workspaceEpoch, scopeEpoch);
            LaunchIdentity identity = resolveLaunchIdentity(selected.provider(), name);
            String canonicalId = identity.applicationId();
            LaunchKey key = new LaunchKey(selected.provider().id(), canonicalId.isBlank()
                    ? "unresolved-provider"
                    : "application:" + normalizeApplicationIdentity(canonicalId));
            // 不支持或截断的目录无法将不同别名确认为安全启动身份；
            // 它们共用一个保守的提供者栅栏。
            if (!canonicalId.isBlank()) {
                LaunchAdmission unresolved = launchAdmissions.get(
                        new LaunchKey(selected.provider().id(), "unresolved-provider"));
                if (unresolved != null) synchronized (unresolved) {
                    String oldIdentity = unresolved.applicationId;
                    boolean pending = unresolved.preparing || unresolved.uncertain || unresolved.launched != null;
                    // 只有旧原生准入时捕获的身份才能收窄该次准入；
                    // 后来的目录不能证明此前未绑定请求的真实应用。
                    // 不能以新目录追认旧派发。
                    if (pending && !oldIdentity.isBlank()
                            && normalizeApplicationIdentity(canonicalId).equals(normalizeApplicationIdentity(oldIdentity)))
                        launchAdmissions.putIfAbsent(key, unresolved);
                }
            }
            LaunchAdmission admission = launchAdmissions.computeIfAbsent(key,
                    ignored -> new LaunchAdmission(name, canonicalId));
            synchronized (admission) {
                admission.preparing = false;
                service.requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                List<DesktopTarget> current;
                try { current = List.copyOf(selected.provider().discoverTargets()); }
                catch (RuntimeException beforeDispatch) {
                    throw new DesktopApplicationLaunchRejectedException(
                            DesktopApplicationLaunchRejectedException.Reason.PRE_DISPATCH_FAILURE,
                            0, "应用窗口发现失败，本次启动请求尚未派发", beforeDispatch);
                }
                List<DesktopTarget> running = canonicalId.isBlank() ? List.of()
                        : applicationWindows(selected.provider().id(), canonicalId, 0, current);
                if (!running.isEmpty()) {
                    DesktopTarget first = running.getFirst();
                    admission.launched = new DesktopApplicationLaunch(first.processId(), canonicalId,
                            "已发现该应用的现有进程与窗口，未再次启动；不可见或最小化窗口需要恢复后重新发现");
                    admission.applicationId = canonicalId;
                    admission.requestedName = name;
                    admission.uncertain = false;
                    service.requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                    return new DesktopApplicationLaunchResult(first.processId(), canonicalId,
                            applicationTargets(selected.provider().id(), canonicalId, 0, running),
                            admission.launched.detail(), false);
                }
                if (admission.uncertain) {
                    DesktopApplicationLaunch prior = admission.launched;
                    throw new DesktopApplicationLaunchUncertainException(
                            "之前的启动派发尚未确认；未再次发送，请先发现应用窗口",
                            prior == null ? 0 : prior.processId(),
                            prior == null ? admission.applicationId : prior.applicationId(), null);
                }
                if (admission.launched != null) {
                    DesktopApplicationLaunch prior = admission.launched;
                    List<DesktopTarget> knownWindows = applicationWindows(selected.provider().id(),
                            prior.applicationId(), prior.processId(), current);
                    List<DesktopTarget> windows = applicationTargets(selected.provider().id(),
                            prior.applicationId(), prior.processId(), knownWindows);
                    Optional<Boolean> alive;
                    try { alive = selected.provider().isLaunchedApplicationRunning(prior); }
                    catch (RuntimeException unavailable) { alive = Optional.empty(); }
                    // 窗口缺失不能证明启动失败；只有明确的应用终止证明
                    // 才允许另一次请求；
                    // 进程交接后，启动器 PID 消失不足以授权重放。
                    if (!knownWindows.isEmpty() || !alive.equals(Optional.of(false))) {
                        if (canonicalId.isBlank()
                                && !normalizeApplicationIdentity(name).equals(normalizeApplicationIdentity(admission.requestedName))
                                && !normalizeApplicationIdentity(name).equals(normalizeApplicationIdentity(admission.applicationId)))
                            throw new DesktopApplicationLaunchRejectedException(
                                    DesktopApplicationLaunchRejectedException.Reason.AMBIGUOUS_APPLICATION,
                                    "应用目录不能唯一绑定当前请求；先前启动进程仍需发现，请使用已确认的 applicationId");
                        service.requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                        return new DesktopApplicationLaunchResult(prior.processId(),
                                prior.applicationId(), windows,
                                windows.isEmpty() ? "先前的启动已接受，窗口尚未发现；未再次启动"
                                        : "已复用先前启动的应用窗口，未再次启动", false);
                    }
                    admission.launched = null;
                }
                service.requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                requireNoUnboundLaunchReplay(selected.provider().id(), canonicalId, admission);
                // 进入平台调用前先安装未知派发栅栏。
                admission.requestedName = name;
                admission.applicationId = canonicalId;
                admission.uncertain = true;
                DesktopApplicationLaunch launched;
                try { launched = Objects.requireNonNull(selected.provider().launchApplication(
                        identity.launchName().isBlank() ? name : identity.launchName()),
                        "platform returned no launch admission"); }
                catch (DesktopApplicationLaunchRejectedException rejected) {
                    admission.uncertain = false;
                    throw rejected;
                } catch (DesktopApplicationLaunchUncertainException uncertain) {
                    if (!uncertain.applicationId().isBlank()) admission.applicationId = uncertain.applicationId();
                    if (uncertain.processId() > 0) admission.launched = new DesktopApplicationLaunch(
                            uncertain.processId(), uncertain.applicationId(), "启动派发尚未确认");
                    throw uncertain;
                }
                admission.launched = launched;
                if (!launched.applicationId().isBlank()) admission.applicationId = launched.applicationId();
                admission.uncertain = false;
                if (!launched.applicationId().isBlank()) launchAdmissions.putIfAbsent(
                        new LaunchKey(selected.provider().id(),
                                "application:" + normalizeApplicationIdentity(launched.applicationId())), admission);
                return discoverLaunchedApplication(owner, scope, workspaceEpoch, scopeEpoch, selected, launched);
            }
        });
    }

    private DesktopApplicationLaunchResult discoverLaunchedApplication(DesktopSessionOwner owner,
            ScopeKey scope, long workspaceEpoch, long scopeEpoch, Selection selected,
            DesktopApplicationLaunch launched) {
            try {
                long deadline = System.nanoTime() +
                        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(LAUNCH_DISCOVERY_MILLIS);
                while (true) {
                    service.requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                    List<DesktopTarget> targets = applicationTargets(selected.provider().id(),
                            launched.applicationId(), launched.processId(), selected.provider().discoverTargets());
                    if (!targets.isEmpty()) {
                        service.requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                        return new DesktopApplicationLaunchResult(
                                launched.processId(), launched.applicationId(), targets,
                                launched.detail());
                    }
                    if (System.nanoTime() >= deadline) {
                        service.requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                        return new DesktopApplicationLaunchResult(
                                launched.processId(), launched.applicationId(), List.of(),
                                launched.detail());
                    }
                    try { Thread.sleep(LAUNCH_DISCOVERY_INTERVAL_MILLIS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new DesktopApplicationLaunchUncertainException(
                                "等待应用窗口时被中断；请先重新发现窗口", launched.processId(),
                                launched.applicationId(), interrupted);
                    }
                }
            } catch (RuntimeException afterLaunch) {
                if (afterLaunch instanceof DesktopApplicationLaunchUncertainException) throw afterLaunch;
                throw new DesktopApplicationLaunchUncertainException(
                        "启动请求可能已生效；窗口发现未确认，请先重新发现窗口",
                        launched.processId(), launched.applicationId(), afterLaunch);
            }
    }

    private static LaunchIdentity resolveLaunchIdentity(DesktopPlatformProvider provider, String requested) {
        DesktopApplicationCatalog catalog;
        try { catalog = provider.discoverApplications(); }
        catch (UnsupportedOperationException legacyProvider) { return new LaunchIdentity("", "", null); }
        catch (RuntimeException beforeDispatch) {
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.PRE_DISPATCH_FAILURE,
                    0, "已安装应用身份发现失败，本次启动请求尚未派发", beforeDispatch);
        }
        return resolveLaunchIdentity(catalog, requested);
    }

    private static LaunchIdentity resolveLaunchIdentity(DesktopApplicationCatalog catalog, String requested) {
        String needle = normalizeApplicationIdentity(requested);
        List<DesktopApplicationInfo> matches = catalog.applications().stream()
                .filter(app -> matchesLaunchName(app, needle, !catalog.truncated()))
                .collect(java.util.stream.Collectors.toMap(
                        app -> normalizeApplicationIdentity(app.applicationId()),
                        app -> app, (first, duplicate) -> first)).values().stream().toList();
        if (matches.size() > 1) throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.AMBIGUOUS_APPLICATION,
                "已安装应用名称对应多个身份，请使用目录中的准确 applicationId 或 launchName");
        return matches.isEmpty() ? new LaunchIdentity("", "", catalog)
                : new LaunchIdentity(matches.getFirst().applicationId(), matches.getFirst().launchName(), catalog);
    }

    private static boolean matchesLaunchName(DesktopApplicationInfo app, String normalizedName, boolean aliases) {
        return normalizeApplicationIdentity(app.applicationId()).equals(normalizedName)
                || aliases && (normalizeApplicationIdentity(app.name()).equals(normalizedName)
                    || normalizeApplicationIdentity(app.displayName()).equals(normalizedName)
                    || normalizeApplicationIdentity(app.launchName()).equals(normalizedName)
                    || app.aliases().stream().anyMatch(alias -> normalizeApplicationIdentity(alias).equals(normalizedName)));
    }

    private static String normalizeApplicationIdentity(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .strip().toLowerCase(java.util.Locale.ROOT);
    }

    private void requireNoUnboundLaunchReplay(String providerId, String currentIdentity,
            LaunchAdmission currentAdmission) {
        LaunchAdmission unresolved = launchAdmissions.get(new LaunchKey(providerId, "unresolved-provider"));
        if (unresolved == null || unresolved == currentAdmission) return;
        synchronized (unresolved) {
            if ((unresolved.preparing || unresolved.uncertain)
                    && (unresolved.applicationId.isBlank()
                        || normalizeApplicationIdentity(currentIdentity)
                            .equals(normalizeApplicationIdentity(unresolved.applicationId)))) {
                DesktopApplicationLaunch prior = unresolved.launched;
                throw new DesktopApplicationLaunchUncertainException(
                        "先前启动的真实应用身份或派发尚未确认；本次未再次启动，请先发现现有窗口",
                        prior == null ? 0 : prior.processId(), unresolved.applicationId, null);
            }
        }
    }

    private static List<DesktopTarget> applicationTargets(String providerId, String applicationId,
            long processId, List<DesktopTarget> candidates) {
        return applicationWindows(providerId, applicationId, processId, candidates).stream()
                .filter(target -> target.visible() && !target.minimized()).toList();
    }

    private static List<DesktopTarget> applicationWindows(String providerId, String applicationId,
            long processId, List<DesktopTarget> candidates) {
        return candidates.stream().filter(target -> providerId.equals(target.providerId())
                && target.processId() > 0 && !target.systemSurface()
                && (!applicationId.isBlank()
                    ? normalizeApplicationIdentity(applicationId).equals(normalizeApplicationIdentity(target.applicationId()))
                    : processId > 0 && target.processId() == processId)).toList();
    }

    private record LaunchKey(String providerId, String identity) { }
    private record LaunchIdentity(String applicationId, String launchName, DesktopApplicationCatalog catalog) { }
    private static final class LaunchAdmission {
        DesktopApplicationLaunch launched;
        String requestedName = "";
        String applicationId = "";
        boolean preparing = true;
        boolean uncertain;

        LaunchAdmission(String requestedName, String applicationId) {
            this.requestedName = requestedName;
            this.applicationId = applicationId;
        }
    }

    private CompletionStage<DesktopApplicationLaunchResult> submitLaunch(
            java.util.function.Supplier<DesktopApplicationLaunchResult> task) {
        try { return CompletableFuture.supplyAsync(task, service.workers); }
        catch (java.util.concurrent.RejectedExecutionException unavailable) {
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.SERVICE_UNAVAILABLE,
                    0, "桌面启动服务不可用，启动请求尚未派发", unavailable);
        }
    }

    private static String validApplicationName(String application) {
        if (application == null) throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.INVALID_ARGUMENTS,
                "应用名称或 bundle ID 不能为空");
        String name = application.strip();
        if (name.isEmpty() || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256
                || name.contains("..") || name.indexOf(':') >= 0
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.chars().anyMatch(Character::isISOControl))
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.INVALID_ARGUMENTS,
                    "请输入已安装应用的准确名称或 bundle ID，不能使用路径或命令");
        return name;
    }

}
