package com.javaclaw.api.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模式注册表。
 *
 * <p>管理所有可用的 {@link Mode}。未来新增模式只需实现 {@link ConversationMode} / {@link ActionMode}
 * 并调用 {@link #register(Mode)} 即可，UI 层完全不需要修改。</p>
 *
 * <p>支持按配置禁用：构造时传入 {@code disabledIds} 集合，命中其中的模式会被 skip。
 * 保持注册顺序：按 {@link #register} 调用顺序遍历，UI 按此顺序渲染。</p>
 *
 * <p>线程安全说明：注册发生在应用启动阶段（单线程），后续 list/getById 读取可跨线程；
 * 生产使用中 UI 一般不再变动注册表。</p>
 */
public final class ModeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModeRegistry.class);

    private final Map<String, Mode> modes = new LinkedHashMap<>();
    private final Set<String> disabledIds;

    /**
     * @param disabledIds 禁用的模式 id 集合；{@code null} 视为不禁用
     */
    public ModeRegistry(Set<String> disabledIds) {
        this.disabledIds = disabledIds == null ? Set.of() : Set.copyOf(disabledIds);
    }

    /**
     * 注册一个模式。重复 id 会抛出 {@link IllegalStateException}；命中禁用列表会被忽略。
     */
    public void register(Mode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        String id = mode.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("mode.id() 不能为空");
        }
        if (disabledIds.contains(id)) {
            log.info("模式 [{}] 已在禁用列表中，跳过注册", id);
            return;
        }
        if (modes.containsKey(id)) {
            throw new IllegalStateException("模式 id 冲突：" + id);
        }
        modes.put(id, mode);
        log.info("已注册模式: {} ({})", mode.displayName(), id);
    }

    /** 取消注册；不存在或已禁用返回 false */
    public boolean unregister(String id) {
        Mode removed = modes.remove(id);
        if (removed != null) {
            log.info("已取消注册模式: {} ({})", removed.displayName(), id);
            return true;
        }
        return false;
    }

    public Optional<Mode> getById(String id) {
        return Optional.ofNullable(modes.get(id));
    }

    /** 所有已注册模式的不可变快照，按注册顺序 */
    public List<Mode> list() {
        return Collections.unmodifiableList(new ArrayList<>(modes.values()));
    }

    public List<Mode> listByPlacement(Placement placement) {
        List<Mode> result = new ArrayList<>();
        for (Mode m : modes.values()) {
            if (m.placement() == placement) {
                result.add(m);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 广播"工作区切换"事件：依次调用每个模式的 {@link Mode#reload()}。
     * 单个模式 reload 异常不影响其他模式。
     */
    public void reloadAll() {
        log.info("工作区切换：依次重载 {} 个模式", modes.size());
        for (Mode m : modes.values()) {
            try {
                m.reload();
            } catch (Exception e) {
                log.error("模式 [{}] reload 失败", m.id(), e);
            }
        }
    }

    /**
     * 广播"应用关闭"事件：按注册顺序的反向执行（后注册的先关）。
     */
    public void shutdownAll() {
        log.info("应用关闭：依次关闭 {} 个模式", modes.size());
        List<Mode> list = new ArrayList<>(modes.values());
        Collections.reverse(list);
        for (Mode m : list) {
            try {
                m.shutdown();
            } catch (Exception e) {
                log.error("模式 [{}] shutdown 失败", m.id(), e);
            }
        }
    }
}
