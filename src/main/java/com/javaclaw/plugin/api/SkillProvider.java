package com.javaclaw.plugin.api;

import java.util.List;

/**
 * 技能提供者 —— 插件入口类<b>可选</b>实现此接口，向宿主技能系统<b>动态注册</b>技能（声明式，无须能力）。
 *
 * <p>与"创建持久化技能"不同：这里的技能仅在插件启用期间存活于内存、并入渐进式暴露（L0 目录 / L1 全文），
 * 插件停用/卸载时由宿主同步移除，<b>不写入磁盘、不污染全局技能库</b>。</p>
 *
 * <p>宿主在插件 {@code start()} 之后读取 {@link #skills()} 快照并注册。</p>
 *
 * @author JavaClaw
 */
public interface SkillProvider {

    /**
     * @return 本插件提供的技能列表（空列表表示不提供）
     */
    List<PluginSkill> skills();
}
