package com.javaclaw.server.discovery;

import java.util.List;

import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.server.model.CloudModelDescriptor;

/** Process-level discovery snapshot exposed through protocol list methods. */
public interface ServerDiscovery {
    ServerDiscovery EMPTY = new ServerDiscovery() {};

    /** 返回云模型公开描述及配置状态，不返回 API Key。 */
    default List<CloudModelDescriptor> models() {
        return List.of();
    }

    /** 返回可发现工具的描述符；实际 Turn 可见性与执行权限仍由工具会话决定。 */
    default List<ToolDescriptor> tools() {
        return List.of();
    }

    /** 返回 MCP 进程声明的公开视图，不启动进程。 */
    default List<ProcessView> mcpServers() {
        return List.of();
    }

    /** 返回插件与贡献项声明，签名信息仅表示来源。 */
    default List<PluginView> plugins() {
        return List.of();
    }

    /**
     * 插件能力发现视图，不包含本地执行实现。
     *
     * @param id 资源或声明的稳定标识
     * @param name 展示名称
     * @param version 插件发布版本
     * @param apiVersion Plugin API 版本
     * @param signatureVerified 来源签名是否有效，不授予权限
     * @param processes 进程贡献列表，构造时复制
     * @param skills Skill 贡献列表，构造时复制
     */
    record PluginView(
            String id,
            String name,
            String version,
            int apiVersion,
            boolean signatureVerified,
            List<ProcessView> processes,
            List<SkillView> skills) {
        /** 固定进程和 Skill 发现集合，不改变声明顺序。 */
        public PluginView {
            processes = List.copyOf(processes);
            skills = List.copyOf(skills);
        }
    }

    /**
     * 进程贡献的权限声明，不是 OS 沙箱授权结果。
     *
     * @param pluginId 所属插件标识
     * @param id 资源或声明的稳定标识
     * @param kind 进程贡献种类
     * @param workspaceRead 是否声明工作区读需求
     * @param workspaceWrite 是否声明工作区写需求
     * @param networkAllowlist 声明的 Broker 主机列表，构造时复制
     */
    record ProcessView(
            String pluginId,
            String id,
            String kind,
            boolean workspaceRead,
            boolean workspaceWrite,
            List<String> networkAllowlist) {
        /** 固定网络声明列表；发现结果不会直接向进程开放网络。 */
        public ProcessView {
            networkAllowlist = List.copyOf(networkAllowlist);
        }
    }

    /**
     * 可供客户端展示的 Skill 贡献标识和包内路径。
     *
     * @param id 资源或声明的稳定标识
     * @param path 插件包内 Skill 相对路径
     */
    record SkillView(String id, String path) {}
}
