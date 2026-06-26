package com.javaclaw.plugin.api.capability;

import java.util.List;

/**
 * MEMORY 能力（<b>只读</b>）—— 插件经此读取宿主编排器/子智能体的记忆快照。
 *
 * <p>需在 {@code plugin.json} 声明 {@link com.javaclaw.plugin.api.Capability#MEMORY}。
 * 仅提供读取，不暴露任何写/清空接口，防止插件污染编排器记忆。</p>
 *
 * @author JavaClaw
 */
public interface MemoryAccess {

    /**
     * @return 当前有记忆快照的智能体名称列表
     */
    List<String> listAgents();

    /**
     * 读取某智能体的记忆快照（只读副本）。
     *
     * @param agentName 智能体名（取自 {@link #listAgents()}）
     * @return 该智能体的消息快照；无则空列表
     */
    List<MemoryMessage> snapshot(String agentName);

    /**
     * 一条记忆消息的只读视图（可序列化，不暴露宿主内部 Msg 类型）。
     *
     * @param role 角色（user / assistant / system / tool）
     * @param name 消息来源名
     * @param text 文本内容
     */
    record MemoryMessage(String role, String name, String text) {
    }
}
