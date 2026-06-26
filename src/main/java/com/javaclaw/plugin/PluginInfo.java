package com.javaclaw.plugin;

import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.PluginDescriptor;

import java.util.List;
import java.util.Set;

/**
 * 插件只读信息快照 —— 供 UI（PluginCenterView）与外部调用方列举展示，不暴露内部容器。
 *
 * @param id           插件 id
 * @param name         显示名称
 * @param version      版本
 * @param description  简述
 * @param capabilities 声明的能力集合
 * @param granted      已授权能力集合（仅 ACTIVE 时非空）
 * @param config       插件自有配置项声明（供 UI 渲染表单）
 * @param skills       对外暴露的技能（名称+描述；仅 ACTIVE 时可知）
 * @param tools        对外暴露的编排器工具（名称+描述；仅 ACTIVE 时可知）
 * @param state        当前生命周期状态
 * @param error        失败原因（非 FAILED 时为空串）
 * @author JavaClaw
 */
public record PluginInfo(
        String id,
        String name,
        String version,
        String description,
        Set<Capability> capabilities,
        Set<Capability> granted,
        List<PluginDescriptor.ConfigField> config,
        List<NamedItem> skills,
        List<NamedItem> tools,
        PluginState state,
        String error) {

    /** 一个具名条目（技能或工具）的展示摘要。 */
    public record NamedItem(String name, String description) {
    }
}
