package com.javaclaw.plugin;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.JavaClawPlugin;
import com.javaclaw.plugin.api.PluginContext;
import com.javaclaw.plugin.api.PluginDescriptor;
import com.javaclaw.plugin.api.PluginTool;
import com.javaclaw.plugin.api.ToolProvider;
import com.javaclaw.plugin.api.SkillProvider;
import com.javaclaw.plugin.api.capability.ChatAccess;
import com.javaclaw.plugin.api.capability.MemoryAccess;
import com.javaclaw.plugin.api.capability.ScheduleAccess;
import com.javaclaw.plugin.api.capability.StorageAccess;
import com.javaclaw.plugin.capability.ChatAccessImpl;
import com.javaclaw.plugin.capability.MemoryAccessImpl;
import com.javaclaw.plugin.capability.ScheduleAccessImpl;
import com.javaclaw.plugin.capability.StorageAccessImpl;
import com.javaclaw.skill.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 单个插件的宿主托管容器 —— 持有该插件的类加载器、入口实例、虚拟线程执行器与能力句柄，
 * 是"系统托管插件全部线程与资源"的落点。
 *
 * <p>生命周期由 {@link PluginManager} 驱动：{@link #load()} → {@link #start(Set, Map)} →
 * {@link #stop()} → {@link #unload()}。停用/卸载时三步兜底回收（取消句柄 → 清理定时任务 → 关执行器 →
 * 丢类加载器），插件 {@code stop()} 写得好不好都不影响回收彻底性。</p>
 *
 * @author JavaClaw
 */
final class PluginRuntime {

    private static final Logger log = LoggerFactory.getLogger(PluginRuntime.class);

    /** 单插件并发任务上限（信号量配额） */
    private static final int MAX_CONCURRENT_TASKS = 32;
    /** 调用插件 stop() 的最长等待时间 */
    private static final long STOP_TIMEOUT_MS = 10_000;

    private final PluginDescriptor descriptor;
    private final Path jarPath;
    private final AgentRuntime agentRuntime;
    private final ClassLoader appClassLoader;
    /** 工作区数据根（用于 STORAGE 能力的插件数据目录） */
    private final Path dataRoot;

    private volatile PluginState state = PluginState.DISCOVERED;
    private volatile String errorMessage = "";
    private volatile Set<Capability> grantedCapabilities = Set.of();

    // —— 启用后填充 ——
    private PluginClassLoader classLoader;
    private JavaClawPlugin instance;
    private PluginScheduler scheduler;
    private PluginScope.PluginIdentity identity;
    // 需在停用时特殊清理的能力实现
    private ChatAccessImpl chatImpl;
    private ScheduleAccessImpl scheduleImpl;
    // 插件对编排器贡献的工具（host → plugin 方向），name → 工具
    private final Map<String, PluginTool> providedTools = new LinkedHashMap<>();
    // 插件对技能系统贡献的技能（仅留名称/描述用于展示）
    private final List<com.javaclaw.plugin.api.PluginSkill> providedSkills = new ArrayList<>();

    PluginRuntime(PluginDescriptor descriptor, Path jarPath, AgentRuntime agentRuntime,
                  ClassLoader appClassLoader, Path dataRoot) {
        this.descriptor = descriptor;
        this.jarPath = jarPath;
        this.agentRuntime = agentRuntime;
        this.appClassLoader = appClassLoader;
        this.dataRoot = dataRoot;
    }

    // ==================== 生命周期 ====================

    /** 建立类加载器（含 lib/ 三方 jar）并实例化入口类（不调用 start）。 */
    synchronized void load() throws Exception {
        if (instance != null) {
            return;
        }
        URL[] urls = resolveClasspath();
        classLoader = new PluginClassLoader(descriptor.id(), urls, appClassLoader);

        Class<?> clazz = Class.forName(descriptor.mainClass(), true, classLoader);
        Object obj = clazz.getDeclaredConstructor().newInstance();
        if (!(obj instanceof JavaClawPlugin plugin)) {
            throw new IllegalStateException("入口类 " + descriptor.mainClass() + " 未实现 JavaClawPlugin");
        }
        this.instance = plugin;
        this.state = PluginState.LOADED;
        log.info("插件[{}]已加载（入口类 {}，classpath 条目 {}）", descriptor.id(), descriptor.mainClass(), urls.length);
    }

    /**
     * 启用插件：按授权能力装配句柄与能力网关，在插件身份作用域内调用 {@code start(ctx)}。
     *
     * @param granted 已授权能力集合（决定能力句柄与插件身份）
     * @param config  插件配置键值
     */
    synchronized void start(Set<Capability> granted, Map<String, String> config) throws Exception {
        if (state == PluginState.ACTIVE) {
            return;
        }
        if (instance == null) {
            load();
        }
        this.grantedCapabilities = Set.copyOf(granted);
        this.identity = new PluginScope.PluginIdentity(descriptor.id(), grantedCapabilities);
        this.scheduler = new PluginScheduler(descriptor.id(), identity, MAX_CONCURRENT_TASKS);

        // 仅装配已授权能力的句柄；未授权能力在网关里为 null → 调用即抛未授权
        ChatAccess chat = null;
        if (granted.contains(Capability.CHAT)) {
            chatImpl = new ChatAccessImpl(descriptor.id(), agentRuntime);
            chat = chatImpl;
        }
        ScheduleAccess schedule = null;
        if (granted.contains(Capability.SCHEDULE)) {
            scheduleImpl = new ScheduleAccessImpl(descriptor.id());
            schedule = scheduleImpl;
        }
        MemoryAccess memory = granted.contains(Capability.MEMORY)
                ? new MemoryAccessImpl(descriptor.id(), agentRuntime.getMemoryManager()) : null;
        StorageAccess storage = granted.contains(Capability.STORAGE)
                ? new StorageAccessImpl(descriptor.id(), dataRoot) : null;

        PluginContext ctx = new PluginContextImpl(descriptor.id(), scheduler,
                new PluginConfigImpl(config), chat, schedule, memory, storage);

        // 在插件身份作用域内调用 start()，使 start() 内直接发起的能力调用也能被鉴权/审计
        ScopedValue.where(PluginScope.CURRENT, identity).call(() -> {
            instance.start(ctx);
            return null;
        });

        // 捕获插件对编排器贡献的工具（可选实现 ToolProvider）
        providedTools.clear();
        if (instance instanceof ToolProvider provider) {
            List<PluginTool> tools = provider.tools();
            if (tools != null) {
                for (PluginTool t : tools) {
                    if (t != null && t.name() != null && !t.name().isBlank()) {
                        providedTools.put(t.name(), t);
                    }
                }
            }
            if (!providedTools.isEmpty()) {
                log.info("插件[{}]贡献 {} 个编排器工具：{}", descriptor.id(),
                        providedTools.size(), providedTools.keySet());
            }
        }

        // 动态注册插件提供的技能（可选实现 SkillProvider）：并入渐进式暴露，不落盘
        providedSkills.clear();
        if (instance instanceof SkillProvider provider) {
            List<com.javaclaw.plugin.api.PluginSkill> ps = provider.skills();
            if (ps != null && !ps.isEmpty()) {
                SkillManager sm = SkillManager.getInstance();
                List<com.javaclaw.skill.Skill> dyn = new ArrayList<>();
                for (var s : ps) {
                    if (s != null && s.name() != null && !s.name().isBlank()) {
                        dyn.add(sm.buildDynamicSkill(descriptor.id(), s.name(), s.description(), s.content()));
                        providedSkills.add(s);
                    }
                }
                sm.registerDynamicSkills(descriptor.id(), dyn);
            }
        }

        this.state = PluginState.ACTIVE;
        this.errorMessage = "";
        log.info("插件[{}]已启用（授权能力：{}）", descriptor.id(), grantedCapabilities);
    }

    /**
     * 调用本插件贡献的某个工具 —— 在该插件的<b>托管虚拟线程 + 身份作用域</b>内执行 handler。
     *
     * @param toolName      工具名
     * @param argumentsJson JSON 参数字符串
     * @return 工具结果文本
     * @throws Exception 工具不存在、插件未启用或 handler 抛出异常
     */
    String invokeTool(String toolName, String argumentsJson) throws Exception {
        PluginTool tool;
        synchronized (this) {
            if (state != PluginState.ACTIVE || scheduler == null) {
                throw new IllegalStateException("插件[" + descriptor.id() + "]未启用");
            }
            tool = providedTools.get(toolName);
        }
        if (tool == null) {
            throw new IllegalArgumentException("插件[" + descriptor.id() + "]无工具：" + toolName);
        }
        // scheduler.call 内部已绑定 ScopedValue 身份并跑在托管虚拟线程上
        return scheduler.call(() -> tool.handler().call(argumentsJson));
    }

    /** 本插件当前贡献的工具快照（停用后为空）。 */
    synchronized Collection<PluginTool> providedTools() {
        return List.copyOf(providedTools.values());
    }

    /** 停用插件：四步兜底回收。可重复调用。 */
    synchronized void stop() {
        if (state != PluginState.ACTIVE) {
            return;
        }
        log.info("插件[{}]开始停用...", descriptor.id());
        // 第 1 步：调插件 stop()（限时，避免卡死回收流程）
        callStopWithTimeout();
        // 第 2 步：取消该插件全部活跃句柄
        if (scheduler != null) {
            scheduler.cancelAllHandles();
        }
        // 第 3 步：清理插件创建的定时任务、释放 CHAT 编排器
        if (scheduleImpl != null) {
            scheduleImpl.cleanup();
        }
        if (chatImpl != null) {
            chatImpl.shutdown();
        }
        // 第 4 步：关执行器（中断全部虚拟线程 + 定时线程）
        if (scheduler != null) {
            scheduler.shutdown();
        }
        providedTools.clear();   // 工具随插件下线
        providedSkills.clear();
        SkillManager.getInstance().unregisterDynamicSkills(descriptor.id());   // 动态技能同步移除
        this.state = PluginState.STOPPED;
        log.info("插件[{}]已停用并回收资源", descriptor.id());
    }

    /** 卸载插件：先停用，再关闭类加载器（释放 jar 句柄）。 */
    synchronized void unload() {
        if (state == PluginState.ACTIVE) {
            stop();
        }
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (Exception e) {
                log.debug("插件[{}]关闭类加载器忽略异常：{}", descriptor.id(), e.toString());
            }
        }
        this.instance = null;
        this.classLoader = null;
        this.scheduler = null;
        this.chatImpl = null;
        this.scheduleImpl = null;
        log.info("插件[{}]已卸载", descriptor.id());
    }

    /** 标记为失败状态（加载/启动出错时由管理器调用）。 */
    synchronized void markFailed(String message) {
        this.state = PluginState.FAILED;
        this.errorMessage = message == null ? "" : message;
    }

    // ==================== 内部 ====================

    /** 解析插件 classpath：插件 jar + 同子目录下 {@code lib/} 内的三方依赖 jar。 */
    private URL[] resolveClasspath() throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(jarPath.toUri().toURL());

        Path libDir = jarPath.getParent().resolve("lib");   // plugins/{名称}/lib/
        if (Files.isDirectory(libDir)) {
            try (Stream<Path> stream = Files.list(libDir)) {
                List<Path> libs = stream
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .sorted()
                        .toList();
                for (Path lib : libs) {
                    urls.add(lib.toUri().toURL());
                    log.info("插件[{}]加载三方依赖：{}", descriptor.id(), lib.getFileName());
                }
            }
        }
        return urls.toArray(new URL[0]);
    }

    private void callStopWithTimeout() {
        if (instance == null) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                ScopedValue.where(PluginScope.CURRENT, identity).run(instance::stop);
            } catch (Throwable e) {
                log.warn("插件[{}]stop() 抛异常（忽略，继续回收）：{}", descriptor.id(), e.toString(), e);
            }
        }, "plugin-" + descriptor.id() + "-stop");
        t.setDaemon(true);
        t.start();
        try {
            t.join(STOP_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            log.warn("插件[{}]stop() 超时（>{}ms），继续强制回收资源", descriptor.id(), STOP_TIMEOUT_MS);
        }
    }

    // ==================== 只读视图 ====================

    String id() {
        return descriptor.id();
    }

    Path jarPath() {
        return jarPath;
    }

    PluginDescriptor descriptor() {
        return descriptor;
    }

    PluginState state() {
        return state;
    }

    String errorMessage() {
        return errorMessage;
    }

    /** 生成对外只读信息快照（供 UI/管理器列举）。技能/工具仅 ACTIVE 时可知（需实例化后读取）。 */
    synchronized PluginInfo toInfo() {
        boolean active = state == PluginState.ACTIVE;
        Set<Capability> grantedView = active ? grantedCapabilities : Set.of();
        List<PluginInfo.NamedItem> skillItems = active
                ? providedSkills.stream().map(s -> new PluginInfo.NamedItem(s.name(), s.description())).toList()
                : List.of();
        List<PluginInfo.NamedItem> toolItems = active
                ? providedTools.values().stream().map(t -> new PluginInfo.NamedItem(t.name(), t.description())).toList()
                : List.of();
        return new PluginInfo(
                descriptor.id(), descriptor.name(), descriptor.version(),
                descriptor.description(), descriptor.capabilities(), grantedView,
                descriptor.config(), skillItems, toolItems, state, errorMessage);
    }
}
