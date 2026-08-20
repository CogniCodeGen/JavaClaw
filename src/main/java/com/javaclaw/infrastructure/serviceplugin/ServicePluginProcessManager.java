package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.serviceplugin.ServicePluginInvocationPort;
import com.javaclaw.application.serviceplugin.ServicePluginHostServiceRouter;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ApiKeyRotation;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ServicePluginInfo;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.State;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.execution.TriggerHandle;
import com.javaclaw.util.ProcessTerminator;
import com.javaclaw.config.CredentialCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Root-owned manager: exactly one process and one authenticated Socket per service plugin. */
public final class ServicePluginProcessManager implements
        ServicePluginManagementApplicationService, ServicePluginInvocationPort, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ServicePluginProcessManager.class);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration HEARTBEAT_PERIOD = Duration.ofSeconds(3);
    private static final Duration DEGRADED_AFTER = Duration.ofSeconds(10);
    private static final Duration LOST_AFTER = Duration.ofSeconds(18);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CRASH_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_CRASHES = 3;
    private static final long[] RESTART_DELAYS_SECONDS = {1, 5, 30};

    private final Object lifecycle = new Object();
    private final ManagedTaskExecutor tasks;
    private final ObjectMapper json;
    private final ServicePluginResourceBudget budget;
    private final ServicePluginDefinitionSettings settings;
    private final ServicePluginArtifactVerifier artifacts = new ServicePluginArtifactVerifier();
    private final ServicePluginHostServiceRouter hostServices;
    private final ServicePluginProcessLauncher launcher;
    private final ServicePluginProcessLeaseStore leases;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long desktopGeneration = random.nextLong() & Long.MAX_VALUE;
    private final AtomicLong heartbeatSequence = new AtomicLong();
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private TriggerHandle heartbeat;

    public ServicePluginProcessManager(
            DataRoot dataRoot, ManagedTaskExecutor tasks, ObjectMapper json) {
        this(dataRoot, tasks, json, new ServicePluginResourceBudget(),
                ServicePluginConfigurationStore.transientStore(),
                new ServicePluginHostServiceRegistry());
    }

    public ServicePluginProcessManager(
            DataRoot dataRoot, ManagedTaskExecutor tasks, ObjectMapper json,
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            CredentialCipher credentials) {
        this(dataRoot, tasks, json, jdbc, transactionManager, credentials,
                new ServicePluginHostServiceRegistry());
    }

    public ServicePluginProcessManager(
            DataRoot dataRoot, ManagedTaskExecutor tasks, ObjectMapper json,
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            CredentialCipher credentials, ServicePluginHostServiceRouter hostServices) {
        this(dataRoot, tasks, json, new ServicePluginResourceBudget(),
                new JdbcServicePluginConfigurationStore(
                        jdbc, transactionManager, json, credentials), hostServices);
    }

    ServicePluginProcessManager(DataRoot dataRoot, ManagedTaskExecutor tasks, ObjectMapper json,
                                ServicePluginResourceBudget budget) {
        this(dataRoot, tasks, json, budget, ServicePluginConfigurationStore.transientStore(),
                new ServicePluginHostServiceRegistry());
    }

    ServicePluginProcessManager(DataRoot dataRoot, ManagedTaskExecutor tasks, ObjectMapper json,
                                ServicePluginResourceBudget budget,
                                ServicePluginConfigurationStore store) {
        this(dataRoot, tasks, json, budget, store, new ServicePluginHostServiceRegistry());
    }

    ServicePluginProcessManager(DataRoot dataRoot, ManagedTaskExecutor tasks, ObjectMapper json,
                                ServicePluginResourceBudget budget,
                                ServicePluginConfigurationStore store,
                                ServicePluginHostServiceRouter hostServices) {
        this.tasks = java.util.Objects.requireNonNull(tasks, "tasks");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.budget = java.util.Objects.requireNonNull(budget, "budget");
        settings = new ServicePluginDefinitionSettings(this.json,
                java.util.Objects.requireNonNull(store, "store"));
        this.hostServices = java.util.Objects.requireNonNull(hostServices, "hostServices");
        ServicePluginHostRuntime hostRuntime = ServicePluginHostRuntime.capture();
        launcher = new ServicePluginProcessLauncher(
                this.tasks, this.json, this.hostServices, desktopGeneration, hostRuntime);
        leases = new ServicePluginProcessLeaseStore(
                dataRoot.path(), this.json, hostRuntime.mainClass());
    }

    public void init() {
        if (!initialized.compareAndSet(false, true)) return;
        try {
            leases.init();
        } catch (IOException failure) {
            throw new IllegalStateException("无法初始化服务插件运行目录", failure);
        }
        heartbeat = tasks.scheduleTriggerAtFixedRate(HEARTBEAT_PERIOD, HEARTBEAT_PERIOD,
                this::heartbeatTick);
        List<String> auto;
        synchronized (lifecycle) {
            auto = entries.values().stream()
                    .filter(entry -> entry.definition.startupPolicy() == StartupPolicy.AUTO_START)
                    .map(entry -> entry.definition.id()).toList();
        }
        auto.forEach(this::startAsync);
    }

    /** Register after descriptor and artifact signature verification, before any class is loaded. */
    public void register(ServicePluginDefinition definition) {
        ensureOpen();
        if (!artifacts.trusted(definition)) {
            throw new SecurityException("服务插件必须具有可信签名: " + definition.id());
        }
        synchronized (lifecycle) {
            Entry existing = entries.get(definition.id());
            if (existing != null && existing.session != null && existing.session.alive()
                    && (!existing.definition.version().equals(definition.version())
                    || !existing.definition.artifactSha256().equals(definition.artifactSha256()))) {
                throw new IllegalStateException("运行中的服务插件不能原位替换版本");
            }
            if (existing == null) {
                Entry created = new Entry(settings.restore(definition));
                settings.load(definition.id()).ifPresent(saved -> {
                    created.crashes.addAll(saved.crashes());
                    if (saved.quarantined()) created.state = State.QUARANTINED;
                });
                persist(created);
                entries.put(definition.id(), created);
            } else {
                ServicePluginDefinition updated = settings.restore(definition);
                persist(existing, updated, existing.state, List.copyOf(existing.crashes));
                existing.definition = updated;
            }
        }
    }

    /** Called only after descriptor registration and every static contribution have succeeded. */
    public void activateAutoStart(String pluginId) {
        boolean start;
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            start = initialized.get()
                    && entry.definition.startupPolicy() == StartupPolicy.AUTO_START
                    && entry.state != State.QUARANTINED
                    && (entry.session == null || !entry.session.alive());
        }
        if (start) startAsync(pluginId);
    }

    public void unregister(String pluginId) {
        stop(pluginId);
        synchronized (lifecycle) {
            settings.delete(pluginId);
            entries.remove(pluginId);
        }
    }

    @Override
    public List<ServicePluginInfo> list() {
        synchronized (lifecycle) {
            return entries.values().stream()
                    .sorted(Comparator.comparing(entry -> entry.definition.name()))
                    .map(this::snapshot).toList();
        }
    }

    @Override
    public void start(String pluginId) {
        ensureOpen();
        Entry entry;
        synchronized (lifecycle) {
            entry = require(pluginId);
            if (entry.state == State.QUARANTINED) {
                throw new IllegalStateException("服务插件已隔离，必须先由用户解除隔离");
            }
            if (entry.session != null && entry.session.alive()) return;
            if (entry.state == State.STARTING) throw new IllegalStateException("服务插件正在启动");
            entry.restartGeneration++;
            entry.state = State.STARTING;
            entry.explicitlyStopping = false;
            entry.lastError = "";
        }
        ServicePluginResourceBudget.Lease lease = null;
        ServicePluginSession session = null;
        try {
            artifacts.verify(entry.definition);
            lease = budget.reserve(pluginId, entry.definition.resources(), entry.definition.endpoints());
            session = launch(entry, lease);
            lease = null;
            synchronized (lifecycle) {
                if (closed.get() || entry.explicitlyStopping) {
                    session.shutdownGracefully(Duration.ZERO, EXIT_TIMEOUT);
                    throw new IllegalStateException("服务插件启动期间应用已关闭");
                }
                entry.session = session;
                session.startMonitors();
                entry.startedAt = ServicePluginProcessLauncher.processStart(session.process())
                        .orElse(Instant.now());
                entry.state = State.HEALTHY;
                leases.write(session, entry.definition, entry.startedAt, desktopGeneration);
            }
            log.info("服务插件已启动: id={}, version={}, pid={}",
                    pluginId, entry.definition.version(), session.pid());
        } catch (Throwable failure) {
            if (lease != null) lease.close();
            if (session != null) {
                synchronized (lifecycle) {
                    if (entry.session == session) entry.session = null;
                }
                session.shutdownGracefully(Duration.ZERO, EXIT_TIMEOUT);
            }
            synchronized (lifecycle) {
                if (entry.explicitlyStopping || closed.get()) {
                    entry.state = State.STOPPED;
                    entry.lastError = "";
                } else {
                    entry.state = State.FAILED;
                    entry.lastError = safeMessage(failure);
                }
                persistBestEffort(entry);
            }
            throw failure instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("服务插件启动失败: " + safeMessage(failure), failure);
        }
    }

    @Override
    public void stop(String pluginId) {
        Entry entry;
        ServicePluginSession session;
        Process startingProcess;
        ServerSocket startingListener;
        synchronized (lifecycle) {
            entry = require(pluginId);
            entry.restartGeneration++;
            entry.explicitlyStopping = true;
            session = entry.session;
            startingProcess = entry.startingProcess;
            startingListener = entry.startingListener;
            entry.session = null;
            if (session == null) {
                entry.state = State.STOPPED;
                leases.delete(pluginId);
            } else {
                entry.state = State.STOPPING;
            }
        }
        ServicePluginResourceCloser.close(startingListener);
        if (startingProcess != null) ProcessTerminator.destroyTreeForcibly(startingProcess);
        if (session == null) return;
        session.shutdownGracefully(DRAIN_TIMEOUT, EXIT_TIMEOUT);
        synchronized (lifecycle) {
            entry.lastLogs = session.recentLogs(200);
            entry.state = State.STOPPED;
            entry.startedAt = null;
            leases.delete(pluginId);
            persistBestEffort(entry);
        }
    }

    @Override public void restart(String pluginId) { stop(pluginId); start(pluginId); }

    @Override
    public void setStartupPolicy(String pluginId, StartupPolicy policy) {
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            if (policy == StartupPolicy.AUTO_START && entry.definition.developmentUnsigned()) {
                throw new SecurityException("未签名开发插件禁止自动启动");
            }
            ServicePluginDefinition updated = settings.copy(entry.definition, policy, entry.definition.resources(),
                    entry.definition.endpoints());
            persist(entry, updated, entry.state, List.copyOf(entry.crashes));
            entry.definition = updated;
            entry.restartGeneration++;
        }
    }

    @Override
    public void updateResources(String pluginId, ResourceConfiguration resources) {
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            if (entry.session != null) throw new IllegalStateException("资源配置需在停止后修改");
            ServicePluginDefinition updated = settings.copy(entry.definition,
                    entry.definition.startupPolicy(), resources,
                    entry.definition.endpoints());
            persist(entry, updated, entry.state, List.copyOf(entry.crashes));
            entry.definition = updated;
        }
    }

    @Override
    public void updateEndpoint(String pluginId, EndpointConfiguration endpoint) {
        java.util.Objects.requireNonNull(endpoint, "endpoint");
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            if (entry.session != null) throw new IllegalStateException("端点配置需在停止后修改");
            if (!entry.definition.endpointConfigurationManaged()) {
                throw new IllegalStateException("该服务插件的端点由专用设置页管理");
            }
            List<EndpointConfiguration> endpoints = new ArrayList<>(entry.definition.endpoints());
            int index = settings.endpointIndex(endpoints, endpoint.id());
            endpoints.set(index, settings.mergeEndpointSecrets(endpoints.get(index), endpoint));
            ServicePluginDefinition updated = settings.copy(entry.definition, entry.definition.startupPolicy(),
                    entry.definition.resources(), endpoints);
            persist(entry, updated, entry.state, List.copyOf(entry.crashes));
            entry.definition = updated;
        }
    }

    @Override
    public void updatePluginConfiguration(String pluginId, Map<String, String> values) {
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            if (entry.session != null) {
                throw new IllegalStateException("插件自有配置需在停止后修改");
            }
            Map<String, String> validated = settings.submittedConfiguration(
                    entry.definition, values);
            ServicePluginDefinition updated = settings.copy(entry.definition,
                    entry.definition.startupPolicy(), entry.definition.resources(),
                    entry.definition.endpoints(), validated);
            persist(entry, updated, entry.state, List.copyOf(entry.crashes));
            entry.definition = updated;
        }
    }

    @Override
    public void updateConfiguration(String pluginId, ResourceConfiguration resources,
                                    List<EndpointConfiguration> endpoints) {
        java.util.Objects.requireNonNull(resources, "resources");
        java.util.Objects.requireNonNull(endpoints, "endpoints");
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            if (entry.session != null) throw new IllegalStateException("服务插件配置需在停止后修改");
            List<EndpointConfiguration> current = entry.definition.endpoints();
            if (!entry.definition.endpointConfigurationManaged()) {
                ServicePluginDefinition updated = settings.copy(entry.definition,
                        entry.definition.startupPolicy(), resources, current);
                persist(entry, updated, entry.state, List.copyOf(entry.crashes));
                entry.definition = updated;
                return;
            }
            if (endpoints.size() != current.size()) {
                throw new IllegalArgumentException("服务插件端点集合不能通过配置界面增删");
            }
            List<EndpointConfiguration> merged = new ArrayList<>(current.size());
            Set<String> seen = new java.util.HashSet<>();
            for (EndpointConfiguration endpoint : endpoints) {
                if (!seen.add(endpoint.id())) throw new IllegalArgumentException("服务插件端点 id 重复");
                int index = settings.endpointIndex(current, endpoint.id());
                merged.add(settings.mergeEndpointSecrets(current.get(index), endpoint));
            }
            ServicePluginDefinition updated = settings.copy(entry.definition,
                    entry.definition.startupPolicy(), resources, List.copyOf(merged));
            persist(entry, updated, entry.state, List.copyOf(entry.crashes));
            entry.definition = updated;
        }
    }

    @Override
    public void updateConfigurationAndRestart(String pluginId, ResourceConfiguration resources,
                                              List<EndpointConfiguration> endpoints) {
        mutateAndRestart(pluginId, () -> updateConfiguration(pluginId, resources, endpoints));
    }

    @Override
    public void updateConfigurationAndRestart(
            String pluginId, ResourceConfiguration resources,
            List<EndpointConfiguration> endpoints, Map<String, String> pluginConfiguration) {
        mutateAndRestart(pluginId, () -> {
            updateConfiguration(pluginId, resources, endpoints);
            updatePluginConfiguration(pluginId, pluginConfiguration);
        });
    }

    @Override
    public void updateResourcesAndRestart(String pluginId, ResourceConfiguration resources) {
        java.util.Objects.requireNonNull(resources, "resources");
        mutateAndRestart(pluginId, () -> updateResources(pluginId, resources));
    }

    @Override
    public void updateEndpointsAndRestart(
            String pluginId, List<EndpointConfiguration> endpoints) {
        java.util.Objects.requireNonNull(endpoints, "endpoints");
        ResourceConfiguration resources;
        synchronized (lifecycle) {
            resources = require(pluginId).definition.resources();
        }
        mutateAndRestart(pluginId, () -> updateConfiguration(pluginId, resources, endpoints));
    }

    @Override
    public void patchPluginConfigurationAndRestart(
            String pluginId, Map<String, String> patch) {
        Map<String, String> merged;
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            merged = settings.patchedConfiguration(entry.definition, patch);
        }
        mutateAndRestart(pluginId, () -> updatePluginConfiguration(pluginId, merged));
    }

    private void mutateAndRestart(String pluginId, Runnable mutation) {
        ServicePluginDefinition previous;
        State previousState;
        String previousError;
        boolean running;
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            if (entry.state == State.STARTING || entry.state == State.STOPPING) {
                throw new IllegalStateException("服务插件切换状态期间不能修改配置");
            }
            previous = entry.definition;
            previousState = entry.state;
            previousError = entry.lastError;
            running = entry.session != null && entry.session.alive();
        }
        if (running) stop(pluginId);
        try {
            mutation.run();
            if (running) start(pluginId);
        } catch (Throwable failure) {
            restoreConfiguration(pluginId, previous, previousState, previousError, failure);
            if (running) {
                try { start(pluginId); }
                catch (Throwable rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            }
            throw failure instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("保存服务插件配置失败", failure);
        }
    }

    @Override
    public ApiKeyRotation rotateEndpointApiKey(String pluginId, String endpointId) {
        ResourceConfiguration resources;
        List<EndpointConfiguration> endpoints;
        String secret;
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            if (!entry.definition.endpointConfigurationManaged()) {
                throw new IllegalStateException("该服务插件的 API Key 由专用设置页管理");
            }
            resources = entry.definition.resources();
            endpoints = new ArrayList<>(entry.definition.endpoints());
            int index = settings.endpointIndex(endpoints, endpointId);
            EndpointConfiguration current = endpoints.get(index);
            secret = "jcsp_" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(randomBytes(32));
            endpoints.set(index, new EndpointConfiguration(current.id(), current.protocol(),
                    current.bindAddress(), current.port(), current.tlsEnabled(),
                    current.allowInsecureLan(), current.keyStorePath(), current.keyStorePassword(),
                    secret, current.requestsPerMinute(), current.tokensPerMinute(),
                    current.maxConcurrent(), current.maxConnections(), current.maxRequestBytes(),
                    current.requestTimeoutSeconds()));
        }
        updateConfigurationAndRestart(pluginId, resources, endpoints);
        return new ApiKeyRotation(endpointId, secret);
    }

    @Override
    public void unquarantine(String pluginId) {
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            if (entry.state != State.QUARANTINED) return;
            persist(entry, entry.definition, State.STOPPED, List.of());
            entry.crashes.clear();
            entry.restartCount = 0;
            entry.lastError = "";
            entry.state = State.STOPPED;
        }
    }

    @Override
    public Invocation invoke(String pluginId, String serviceId, String operation,
                             String contentType, byte[] payload, Duration timeout,
                             Consumer<Event> events) {
        ServicePluginSession session;
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            session = entry.session;
            if (session == null || !session.alive()) {
                throw new ServicePluginException("service_unavailable",
                        "服务插件尚未启动: " + pluginId, true);
            }
        }
        return session.invoke(serviceId, operation, contentType, payload, timeout, events);
    }

    /** Updates runner-owned endpoint authorization and plugin config without replacing the process. */
    public void hotConfigure(String pluginId, ServicePluginDefinition candidate, Duration timeout)
            throws Exception {
        ServicePluginSession session;
        synchronized (lifecycle) {
            Entry entry = require(pluginId);
            session = entry.session;
            if (session == null || !session.alive()) {
                throw new ServicePluginException("service_unavailable",
                        "服务插件尚未启动: " + pluginId, true);
            }
            if (!entry.definition.artifactSha256().equals(candidate.artifactSha256())
                    || !entry.definition.mainClass().equals(candidate.mainClass())
                    || !entry.definition.resources().equals(candidate.resources())) {
                throw new IllegalArgumentException("热配置不能替换插件工件、入口或进程资源");
            }
        }
        session.hotConfigure(candidate, timeout);
    }

    @Override
    public boolean cancel(String pluginId, String requestId) {
        synchronized (lifecycle) {
            Entry entry = entries.get(pluginId);
            return entry != null && entry.session != null && entry.session.cancel(requestId);
        }
    }

    public Optional<ServicePluginDefinition> definition(String pluginId) {
        synchronized (lifecycle) {
            Entry entry = entries.get(pluginId);
            return entry == null ? Optional.empty() : Optional.of(entry.definition);
        }
    }

    private ServicePluginSession launch(
            Entry entry, ServicePluginResourceBudget.Lease lease) throws Exception {
        return launcher.launch(entry.definition, lease, new ServicePluginProcessLauncher.LaunchObserver() {
            @Override public boolean stopping() {
                synchronized (lifecycle) { return entry.explicitlyStopping || closed.get(); }
            }

            @Override public void startingListener(ServerSocket listener) {
                synchronized (lifecycle) { entry.startingListener = listener; }
            }

            @Override public void startingProcess(Process process) {
                synchronized (lifecycle) { entry.startingProcess = process; }
            }

            @Override public void disconnected(ServicePluginSession session) {
                onDisconnected(entry.definition.id(), session);
            }
        });
    }

    private void heartbeatTick() {
        if (closed.get()) return;
        long sequence = heartbeatSequence.incrementAndGet();
        List<Entry> snapshot;
        synchronized (lifecycle) { snapshot = List.copyOf(entries.values()); }
        for (Entry entry : snapshot) {
            ServicePluginSession session = entry.session;
            if (session == null || !session.alive()) continue;
            session.ping(sequence);
            synchronized (lifecycle) {
                if (session.heartbeatExpired(LOST_AFTER)) {
                    entry.state = State.FAILED;
                    entry.lastError = "服务插件心跳超时";
                    ProcessTerminator.destroyTreeForcibly(session.process());
                } else if (session.heartbeatExpired(DEGRADED_AFTER)) {
                    entry.state = State.DEGRADED;
                } else if (entry.state == State.DEGRADED) {
                    entry.state = State.HEALTHY;
                }
            }
        }
    }

    private void onDisconnected(String pluginId, ServicePluginSession session) {
        if (session == null) return;
        Entry entry;
        boolean restart = false;
        long restartDelay = 0;
        long restartGeneration = 0;
        synchronized (lifecycle) {
            entry = entries.get(pluginId);
            if (entry == null || entry.session != session) return;
            entry.session = null;
            entry.startedAt = null;
            entry.lastLogs = session.recentLogs(200);
            leases.delete(pluginId);
            boolean abnormal = !closed.get() && !entry.explicitlyStopping && !session.expectedStop();
            if (!abnormal) {
                entry.state = State.STOPPED;
                return;
            }
            Instant now = Instant.now();
            Instant cutoff = now.minus(CRASH_WINDOW);
            while (!entry.crashes.isEmpty() && entry.crashes.peekFirst().isBefore(cutoff)) {
                entry.crashes.removeFirst();
            }
            if (entry.crashes.isEmpty()) entry.restartCount = 0;
            entry.crashes.addLast(now);
            entry.lastError = "服务插件进程异常退出";
            if (entry.crashes.size() >= MAX_CRASHES) {
                entry.state = State.QUARANTINED;
                persistBestEffort(entry);
                return;
            }
            entry.state = State.FAILED;
            if (entry.definition.startupPolicy() == StartupPolicy.AUTO_START) {
                restartDelay = RESTART_DELAYS_SECONDS[Math.min(
                        entry.restartCount, RESTART_DELAYS_SECONDS.length - 1)];
                entry.restartCount++;
                restartGeneration = ++entry.restartGeneration;
                restart = true;
            }
            persistBestEffort(entry);
        }
        if (restart) scheduleRestart(pluginId, restartDelay, restartGeneration);
    }

    private void scheduleRestart(String pluginId, long seconds, long generation) {
        tasks.scheduleTrigger(Duration.ofSeconds(seconds), () -> {
            synchronized (lifecycle) {
                Entry entry = entries.get(pluginId);
                if (closed.get() || entry == null || entry.explicitlyStopping
                        || entry.restartGeneration != generation
                        || entry.definition.startupPolicy() != StartupPolicy.AUTO_START) return;
            }
            startAsync(pluginId);
        });
    }

    private void startAsync(String pluginId) {
        if (closed.get()) return;
        tasks.submit(TaskSpec.process("启动服务插件 " + pluginId), context -> {
            try { start(pluginId); }
            catch (Throwable failure) {
                log.warn("服务插件自动启动失败: id={}, error={}", pluginId, safeMessage(failure));
            }
            return null;
        });
    }

    private ServicePluginInfo snapshot(Entry entry) {
        ServicePluginSession session = entry.session;
        long reservedMemory = entry.definition.resources().reservedMemoryMiB();
        return new ServicePluginInfo(entry.definition.id(), entry.definition.name(),
                entry.definition.version(), entry.definition.publisher(),
                entry.definition.signatureVerified(), entry.definition.pluginJar(),
                entry.definition.builtIn(), entry.definition.startupPolicy(), entry.state,
                session == null ? 0 : session.pid(), entry.startedAt,
                entry.definition.resources(), entry.definition.endpoints(),
                entry.definition.endpointConfigurationManaged(),
                session == null ? Set.of() : session.services(),
                session == null ? 0 : session.activeRequests(),
                session == null ? 0 : session.queuedRequests(),
                reservedMemory, entry.definition.resources().computeThreads(), entry.restartCount,
                List.copyOf(entry.crashes), entry.lastError,
                session == null ? entry.lastLogs : session.recentLogs(200),
                session == null ? Map.of() : session.health(),
                entry.definition.description(), entry.definition.configurationSchema(),
                settings.redactedConfiguration(entry.definition),
                entry.definition.configurationUi(), entry.definition.inference(),
                entry.definition.endpointCapabilities());
    }

    private Entry require(String pluginId) {
        Entry entry = entries.get(pluginId);
        if (entry == null) throw new IllegalArgumentException("未找到服务插件: " + pluginId);
        return entry;
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        random.nextBytes(value);
        return value;
    }

    private void restoreConfiguration(
            String pluginId, ServicePluginDefinition previous, State previousState,
            String previousError, Throwable originalFailure) {
        try {
            stop(pluginId);
            synchronized (lifecycle) {
                Entry entry = require(pluginId);
                persist(entry, previous, previousState, List.copyOf(entry.crashes));
                entry.definition = previous;
                entry.state = previousState;
                entry.lastError = previousError;
            }
        } catch (Throwable rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }

    private void persist(Entry entry) {
        persist(entry, entry.definition, entry.state, List.copyOf(entry.crashes));
    }

    private void persist(Entry entry, ServicePluginDefinition definition,
                         State state, List<Instant> crashes) {
        settings.persist(definition, state, crashes);
    }

    private void persistBestEffort(Entry entry) {
        try {
            persist(entry);
        } catch (RuntimeException failure) {
            log.warn("持久化服务插件配置失败: id={}, error={}",
                    entry.definition.id(), safeMessage(failure));
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("服务插件进程管理器已关闭");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (heartbeat != null) heartbeat.cancel();
        List<Map.Entry<String, ServicePluginSession>> sessions;
        List<Process> startingProcesses;
        List<ServerSocket> startingListeners;
        synchronized (lifecycle) {
            sessions = entries.entrySet().stream()
                    .filter(value -> value.getValue().session != null)
                    .map(value -> Map.entry(value.getKey(), value.getValue().session)).toList();
            sessions.forEach(value -> {
                Entry entry = entries.get(value.getKey());
                entry.explicitlyStopping = true;
                entry.state = State.STOPPING;
            });
            entries.values().forEach(entry -> entry.explicitlyStopping = true);
            startingProcesses = entries.values().stream()
                    .map(entry -> entry.startingProcess).filter(java.util.Objects::nonNull).toList();
            startingListeners = entries.values().stream()
                    .map(entry -> entry.startingListener).filter(java.util.Objects::nonNull).toList();
        }
        startingListeners.forEach(ServicePluginResourceCloser::close);
        startingProcesses.forEach(ProcessTerminator::destroyTreeForcibly);
        sessions.forEach(value -> value.getValue().drain());
        sessions.forEach(value -> value.getValue().shutdownGracefully(DRAIN_TIMEOUT, EXIT_TIMEOUT));
        synchronized (lifecycle) {
            sessions.forEach(value -> {
                Entry entry = entries.get(value.getKey());
                if (entry != null) {
                    entry.session = null;
                    entry.state = State.STOPPED;
                    entry.startedAt = null;
                }
                leases.delete(value.getKey());
            });
        }
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static final class Entry {
        private ServicePluginDefinition definition;
        private State state = State.INSTALLED;
        private ServicePluginSession session;
        private Process startingProcess;
        private ServerSocket startingListener;
        private Instant startedAt;
        private final Deque<Instant> crashes = new ArrayDeque<>();
        private int restartCount;
        private long restartGeneration;
        private boolean explicitlyStopping;
        private String lastError = "";
        private List<String> lastLogs = List.of();

        private Entry(ServicePluginDefinition definition) { this.definition = definition; }
    }

}
