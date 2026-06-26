package com.javaclaw.plugin;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.config.CredentialEncryptor;
import com.javaclaw.config.DataManager;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.PluginDescriptor;
import com.javaclaw.plugin.api.PluginTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 插件管理器（单例）—— 插件系统的总控：发现、授权、启停、卸载、持久化、生命周期管理。
 *
 * <p>插件 jar 放在运行目录下的全局 {@code plugins/}（与 {@code skills/} 同级、同为全局约定）；
 * 启用态与能力授权按工作区持久化到 {@code {workspace}/data/plugins.json}。每个插件由一个
 * {@link PluginRuntime} 容器托管，所有线程与资源经容器统一管理与回收。</p>
 *
 * <p>能力授权门控：插件 {@code plugin.json} 声明的能力 = 权限申请；首次启用时经
 * {@link UserInteractionPort} 弹确认授权，授权结果持久化，下次启用不再重复询问。</p>
 *
 * @author JavaClaw
 */
public final class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private static PluginManager instance;

    /** 插件根目录（全局，{user.dir}/plugins） */
    private final Path pluginsDir = Path.of(System.getProperty("user.dir"), "plugins");

    /** id → 容器，按发现顺序保序 */
    private final Map<String, PluginRuntime> plugins = new LinkedHashMap<>();

    /** 启用态 + 授权持久化（工作区维度） */
    private final PluginStore store = new PluginStore();

    private volatile AgentRuntime agentRuntime;
    private volatile UserInteractionPort interactionPort;
    private ClassLoader appClassLoader;

    /** 目录热感知 */
    private PluginWatcher watcher;
    /** UI 注册的变化监听（热感知/重扫后回调，UI 实现内部自行切回 FX 线程） */
    private volatile Runnable changeListener;

    private PluginManager() {
    }

    public static synchronized PluginManager getInstance() {
        if (instance == null) {
            instance = new PluginManager();
        }
        return instance;
    }

    // ==================== 生命周期接线 ====================

    /**
     * 初始化插件系统（应用启动时调用）：发现插件并在后台自动恢复上次已启用的插件。
     *
     * @param runtime         智能体基础设施容器，供各能力使用
     * @param interactionPort 用户交互端口，供能力授权确认
     */
    public synchronized void init(AgentRuntime runtime, UserInteractionPort interactionPort) {
        this.agentRuntime = runtime;
        this.interactionPort = interactionPort;
        this.appClassLoader = PluginManager.class.getClassLoader();
        ensureDir();
        store.bind(DataManager.getInstance().getDataRoot());
        discover();
        log.info("插件系统已初始化：目录 {}，发现 {} 个插件", pluginsDir.toAbsolutePath(), plugins.size());
        startWatcher();
        autoEnablePersistedAsync();
    }

    /**
     * 工作区切换重载：停用并卸载全部插件，切换 runtime 与持久化文件后重新发现并自动恢复。
     *
     * @param newRuntime 新工作区的智能体基础设施容器
     */
    public synchronized void reload(AgentRuntime newRuntime) {
        log.info("插件系统随工作区切换重载...");
        unloadAll();
        this.agentRuntime = newRuntime;
        store.bind(DataManager.getInstance().getDataRoot());
        discover();
        log.info("插件系统重载完成，发现 {} 个插件", plugins.size());
        autoEnablePersistedAsync();
    }

    /** 关闭插件系统（应用退出时调用）：停止热感知、停用并卸载全部插件。 */
    public synchronized void shutdown() {
        log.info("插件系统关闭中...");
        if (watcher != null) {
            watcher.stop();
            watcher = null;
        }
        unloadAll();
        log.info("插件系统已关闭");
    }

    /**
     * 注册插件变化监听（热感知/重扫后触发）。UI 在打开时注册、关闭时清空；
     * 监听实现内部应自行切回 UI 线程刷新。
     *
     * @param listener 监听回调，传 null 清除
     */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    // ==================== 管理操作（供 UI 调用） ====================

    /** 重扫插件目录：发现新放入的 jar，并剔除已删除 jar 对应的非运行插件。 */
    public synchronized void refresh() {
        discover();
        pruneMissing();
    }

    /**
     * 启用插件：按需弹能力授权确认，授权后启动并持久化启用态。
     * 建议由 UI 在后台线程调用（授权确认与 {@code plugin.start()} 可能阻塞）。
     *
     * @param id 插件 id
     */
    public synchronized void enable(String id) {
        PluginRuntime rt = plugins.get(id);
        if (rt == null) {
            log.warn("启用失败：未找到插件 {}", id);
            return;
        }
        PluginDescriptor d = rt.descriptor();
        Set<Capability> declared = d.capabilities();
        Set<Capability> granted = store.granted(id);

        // 仍有未授权的声明能力 → 弹确认请求授权
        if (!granted.containsAll(declared)) {
            if (!requestGrant(d, declared)) {
                log.info("用户拒绝授权，插件[{}]未启用", id);
                return;
            }
            granted = declared;
            store.update(id, false, granted);   // 先落授权，启用态待启动成功再置
        }

        try {
            rt.start(granted, configFor(id));
            store.setEnabled(id, true);
        } catch (Exception e) {
            log.error("插件[{}]启用失败：{}", id, e.toString(), e);
            rt.markFailed(e.getMessage());
        }
    }

    /**
     * 停用插件（回收其全部线程与资源），并持久化停用态。
     *
     * @param id 插件 id
     */
    public synchronized void disable(String id) {
        PluginRuntime rt = plugins.get(id);
        if (rt == null) {
            return;
        }
        try {
            rt.stop();
            store.setEnabled(id, false);
        } catch (Exception e) {
            log.error("插件[{}]停用异常：{}", id, e.toString(), e);
        }
    }

    /**
     * @return 全部插件的只读信息快照（按发现顺序）
     */
    public synchronized List<PluginInfo> list() {
        return plugins.values().stream().map(PluginRuntime::toInfo).toList();
    }

    /** @return 插件根目录（全局 {user.dir}/plugins），供 UI「打开插件目录 / 从文件安装」使用。 */
    public Path pluginsDir() {
        return pluginsDir;
    }

    /**
     * 从一个 jar 文件安装插件：解析其 descriptor → 拷贝到 {@code plugins/{id}/} 子目录 → 重扫发现。
     *
     * @param jar 用户选择的插件 jar
     * @return 安装成功的插件 id；失败返回 null（descriptor 非法 / 不兼容 / IO 错误，已记日志）
     */
    public synchronized String installFromFile(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)
                || !jar.getFileName().toString().toLowerCase().endsWith(".jar")) {
            log.warn("从文件安装失败：非法 jar 路径 {}", jar);
            return null;
        }
        try {
            PluginDescriptor d = PluginDescriptorLoader.load(jar);
            if (!isApiCompatible(d.apiVersion())) {
                log.warn("从文件安装失败：插件[{}]apiVersion={} 与宿主不兼容", d.id(), d.apiVersion());
                return null;
            }
            Path destDir = pluginsDir.resolve(d.id());
            Files.createDirectories(destDir);
            Files.copy(jar, destDir.resolve(jar.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("已从文件安装插件[{}]到 {}", d.id(), destDir);
            refresh();
            return d.id();
        } catch (Exception e) {
            log.error("从文件安装插件失败：{}", e.toString(), e);
            return null;
        }
    }

    /**
     * 卸载插件：先停用回收资源，再删除其在 {@code plugins/} 下的子目录（含 jar 与 lib/）。
     *
     * @param id 插件 id
     * @return 是否成功删除
     */
    public synchronized boolean uninstall(String id) {
        PluginRuntime rt = plugins.get(id);
        if (rt == null) return false;
        disable(id);
        Path dir = rt.jarPath().getParent();
        plugins.remove(id);
        rt.unload();
        try {
            deleteRecursively(dir);
            log.info("已卸载插件[{}]，删除目录 {}", id, dir);
            return true;
        } catch (IOException e) {
            log.error("卸载插件[{}]删除目录失败：{}", id, e.toString(), e);
            return false;
        }
    }

    /** 递归删除目录（自底向上）。 */
    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignore) {}
            });
        }
    }

    /**
     * 读取插件配置（secret 项已解密），供 UI 表单回显。
     *
     * @param id 插件 id
     * @return 配置键值（明文）
     */
    public synchronized Map<String, String> getConfig(String id) {
        PluginRuntime rt = plugins.get(id);
        if (rt == null) {
            return Map.of();
        }
        Map<String, String> raw = store.config(id);
        Map<String, String> result = new LinkedHashMap<>();
        for (PluginDescriptor.ConfigField f : rt.descriptor().config()) {
            String v = raw.getOrDefault(f.key(), "");
            result.put(f.key(), f.secret() ? CredentialEncryptor.decrypt(v) : v);
        }
        return result;
    }

    /**
     * 写入插件配置（secret 项加密存储）。配置在插件下次启用时注入；当前已启用的插件需重新启用方生效。
     *
     * @param id        插件 id
     * @param rawValues 明文配置键值
     */
    public synchronized void setConfig(String id, Map<String, String> rawValues) {
        PluginRuntime rt = plugins.get(id);
        if (rt == null) {
            return;
        }
        Map<String, String> toStore = new LinkedHashMap<>();
        for (PluginDescriptor.ConfigField f : rt.descriptor().config()) {
            String v = rawValues.getOrDefault(f.key(), "");
            toStore.put(f.key(), f.secret() ? CredentialEncryptor.encrypt(v) : v);
        }
        store.setConfig(id, toStore);
        log.info("插件[{}]配置已保存（{} 项）", id, toStore.size());
    }

    // ==================== 插件工具（host → plugin 方向，供聊天编排器调用） ====================

    /**
     * 汇总所有已启用插件贡献的工具，生成注入编排器系统提示词的描述块（无则返回空串）。
     */
    public synchronized String buildToolsPrompt() {
        List<PluginRuntime> withTools = plugins.values().stream()
                .filter(rt -> rt.state() == PluginState.ACTIVE && !rt.providedTools().isEmpty())
                .toList();
        if (withTools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## 插件工具\n");
        sb.append("以下工具由已启用插件提供。需要时调用 plugin_call_tool(plugin_id, tool_name, arguments_json)：\n");
        for (PluginRuntime rt : withTools) {
            for (PluginTool t : rt.providedTools()) {
                sb.append("- plugin_id=").append(rt.id())
                        .append(", tool_name=").append(t.name())
                        .append("：").append(t.description());
                if (!t.params().isEmpty()) {
                    sb.append("（参数 ");
                    sb.append(t.params().stream()
                            .map(p -> p.name() + (p.required() ? "*" : "") + ":" + p.description())
                            .collect(Collectors.joining("，")));
                    sb.append("）");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 派发一次插件工具调用 —— 由编排器的 {@code plugin_call_tool} 转入。
     *
     * @param pluginId      插件 id
     * @param toolName      工具名
     * @param argumentsJson JSON 参数字符串
     * @return 工具结果文本
     * @throws Exception 插件未启用、工具不存在或 handler 抛出
     */
    public String invokeTool(String pluginId, String toolName, String argumentsJson) throws Exception {
        PluginRuntime rt;
        synchronized (this) {
            rt = plugins.get(pluginId);
        }
        if (rt == null) {
            throw new IllegalStateException("未找到插件：" + pluginId);
        }
        // 不持管理器锁执行：handler 可能阻塞（如内部调 CHAT）
        return rt.invokeTool(toolName, argumentsJson);
    }

    // ==================== 授权 ====================

    /** 弹确认请求用户授权一组能力。无交互端口时（如无头环境）记录告警后放行。 */
    private boolean requestGrant(PluginDescriptor d, Set<Capability> capabilities) {
        if (capabilities.isEmpty()) {
            return true;
        }
        UserInteractionPort port = this.interactionPort;
        if (port == null || !port.isAvailable()) {
            log.warn("无交互端口，插件[{}]能力授权自动放行：{}", d.id(), capabilities);
            return true;
        }
        String caps = capabilities.stream().map(Capability::displayName).collect(Collectors.joining("、"));
        String desc = "插件「" + d.name() + "」(" + d.id() + ") 申请以下能力：\n"
                + caps + "\n\n⚠ 插件为进程内第三方代码，请仅授权可信来源。是否授权并启用？";
        ConfirmRequest req = new ConfirmRequest(
                "插件授权：" + d.name(), "插件", desc, ConfirmKind.CONFIRM, 60, "", false);
        return port.confirm(req);
    }

    // ==================== 内部 ====================

    private void ensureDir() {
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            log.error("创建插件目录失败 {}：{}", pluginsDir, e.toString());
        }
    }

    /**
     * 扫描插件目录：每个插件占一个子目录 {@code plugins/{名称}/}，其下放插件 jar 与 {@code lib/} 依赖。
     * 逐子目录解析描述符，为新插件建立容器（DISCOVERED）。
     */
    private void discover() {
        if (!Files.isDirectory(pluginsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            List<Path> subdirs = stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
            for (Path dir : subdirs) {
                discoverOne(dir);
            }
        } catch (IOException e) {
            log.error("扫描插件目录失败：{}", e.toString());
        }
    }

    private void discoverOne(Path pluginDir) {
        Path jar = findPluginJar(pluginDir);
        if (jar == null) {
            log.debug("插件子目录无 jar，跳过：{}", pluginDir.getFileName());
            return;
        }
        try {
            PluginDescriptor d = PluginDescriptorLoader.load(jar);
            if (plugins.containsKey(d.id())) {
                return;   // 已发现/已启用，跳过
            }
            if (!isApiCompatible(d.apiVersion())) {
                log.warn("插件[{}]apiVersion={} 与宿主 {} 不兼容，跳过",
                        d.id(), d.apiVersion(), PluginDescriptor.HOST_API_VERSION);
                return;
            }
            plugins.put(d.id(), new PluginRuntime(d, jar, agentRuntime, appClassLoader,
                    DataManager.getInstance().getDataRoot()));
            log.info("发现插件：{}（{}），目录 {}", d.name(), d.id(), pluginDir.getFileName());
        } catch (Exception e) {
            log.warn("解析插件失败，跳过 {}：{}", pluginDir.getFileName(), e.toString());
        }
    }

    /** 取插件子目录下顶层的第一个 jar 作为插件 jar（lib/ 内的依赖 jar 不计）。 */
    private Path findPluginJar(Path pluginDir) {
        try (Stream<Path> s = Files.list(pluginDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("读取插件子目录失败 {}：{}", pluginDir.getFileName(), e.toString());
            return null;
        }
    }

    /** 后台线程自动恢复上次已启用、且授权充分的插件（不阻塞启动、不弹窗）。 */
    private void autoEnablePersistedAsync() {
        List<PluginRuntime> toEnable = plugins.values().stream()
                .filter(rt -> store.isEnabled(rt.id()))
                .toList();
        if (toEnable.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> {
            for (PluginRuntime rt : toEnable) {
                String id = rt.id();
                Set<Capability> declared = rt.descriptor().capabilities();
                Set<Capability> granted = store.granted(id);
                if (!granted.containsAll(declared)) {
                    log.warn("插件[{}]声明能力有变更，需重新授权，已跳过自动恢复（请在插件中心手动启用）", id);
                    continue;
                }
                try {
                    synchronized (PluginManager.this) {
                        rt.start(granted, configFor(id));
                    }
                    log.info("插件[{}]已自动恢复启用", id);
                } catch (Exception e) {
                    log.error("插件[{}]自动恢复失败：{}", id, e.toString(), e);
                    rt.markFailed(e.getMessage());
                }
            }
        }, "plugin-autoenable");
        t.setDaemon(true);
        t.start();
    }

    /** api 主版本一致即视为兼容。 */
    private boolean isApiCompatible(String pluginApiVersion) {
        return major(PluginDescriptor.HOST_API_VERSION).equals(major(pluginApiVersion));
    }

    private String major(String version) {
        if (version == null || version.isBlank()) {
            return "";
        }
        int dot = version.indexOf('.');
        return dot < 0 ? version.strip() : version.substring(0, dot).strip();
    }

    /** 读取注入给插件的配置（secret 项已解密）。 */
    private Map<String, String> configFor(String id) {
        return getConfig(id);
    }

    private void startWatcher() {
        if (watcher != null) {
            return;
        }
        watcher = new PluginWatcher(pluginsDir, () -> {
            refresh();
            fireChange();
        });
        watcher.start();
    }

    private void fireChange() {
        Runnable l = changeListener;
        if (l != null) {
            try {
                l.run();
            } catch (Exception e) {
                log.debug("插件变化监听回调异常：{}", e.toString());
            }
        }
    }

    /** 剔除 jar 已被删除、且当前非运行态的插件容器。 */
    private void pruneMissing() {
        plugins.values().removeIf(rt -> {
            boolean gone = !Files.exists(rt.jarPath());
            if (gone && rt.state() == PluginState.ACTIVE) {
                log.warn("插件[{}]的 jar 已删除但仍在运行，保留至停用", rt.id());
                return false;
            }
            if (gone) {
                log.info("插件[{}]的 jar 已删除，移出列表", rt.id());
            }
            return gone;
        });
    }

    private void unloadAll() {
        for (PluginRuntime rt : plugins.values()) {
            try {
                rt.unload();
            } catch (Exception e) {
                log.debug("卸载插件[{}]忽略异常：{}", rt.id(), e.toString());
            }
        }
        plugins.clear();
    }
}
