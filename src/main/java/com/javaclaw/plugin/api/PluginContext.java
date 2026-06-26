package com.javaclaw.plugin.api;

import com.javaclaw.plugin.api.capability.ChatAccess;
import com.javaclaw.plugin.api.capability.MemoryAccess;
import com.javaclaw.plugin.api.capability.ScheduleAccess;
import com.javaclaw.plugin.api.capability.StorageAccess;
import com.javaclaw.plugin.api.exec.PluginExecutor;

/**
 * 能力网关 —— 插件获取一切宿主资源的<b>唯一入口</b>。宿主在启用插件时按其声明的能力装配本对象。
 *
 * <p>核心约束：<b>插件不自行获取任何资源</b>（线程、AI、配置等），全部经此对象拿托管句柄。
 * 未声明/未授权的能力，对应 getter 抛
 * {@link com.javaclaw.plugin.api.PluginException.CapabilityNotGrantedException}。</p>
 *
 * <p>{@link #exec()} 与 {@link #config()} 无须声明能力；{@link #chat()}/{@link #schedule()}/
 * {@link #memory()}/{@link #storage()} 需声明并被授予对应能力，否则抛
 * {@link PluginException.CapabilityNotGrantedException}。</p>
 *
 * <p>注：向技能系统贡献技能不走能力，改为可选实现 {@link SkillProvider}（动态注册、卸载即移除）；
 * 向编排器贡献工具同理走 {@link ToolProvider}。</p>
 *
 * @author JavaClaw
 */
public interface PluginContext {

    /**
     * 执行器：插件并发能力的唯一来源（同步/异步/后台监听/定时）。无须声明能力，但受配额约束。
     *
     * @return 宿主托管的虚拟线程执行面
     */
    PluginExecutor exec();

    /**
     * 插件自有配置（只读）。
     *
     * @return 配置视图
     */
    PluginConfig config();

    /**
     * CHAT 能力句柄。需声明并授予 {@link Capability#CHAT}。
     *
     * @return AI 对话能力
     * @throws PluginException.CapabilityNotGrantedException 未授权时
     */
    ChatAccess chat();

    /**
     * SCHEDULE 能力句柄。需声明并授予 {@link Capability#SCHEDULE}。
     *
     * @return 定时任务能力
     * @throws PluginException.CapabilityNotGrantedException 未授权时
     */
    ScheduleAccess schedule();

    /**
     * MEMORY 能力句柄（只读）。需声明并授予 {@link Capability#MEMORY}。
     *
     * @return 记忆读取能力
     * @throws PluginException.CapabilityNotGrantedException 未授权时
     */
    MemoryAccess memory();

    /**
     * STORAGE 能力句柄。需声明并授予 {@link Capability#STORAGE}。
     *
     * @return 托管数据存储能力
     * @throws PluginException.CapabilityNotGrantedException 未授权时
     */
    StorageAccess storage();
}
