package com.javaclaw.server.extension;

import java.util.HashSet;
import java.util.List;

/**
 * Strict Plugin API 4.0 declaration. No Java class or ClassLoader entry point exists.
 *
 * @param apiVersion 固定为 Plugin API 4
 * @param minimumProtocolVersion 要求的 JavaClaw 协议下限；0 归一为 1，当前仅支持 1
 * @param id 资源或声明的稳定标识
 * @param version 插件发布版本字符串
 * @param name 展示名称
 * @param processes 进程贡献声明；null 归一为空列表并复制
 * @param skills Skill 贡献声明；null 归一为空列表并复制
 * @param signature 可选 Ed25519 签名；null 表示未签名，需用户显式确认来源
 */
public record PluginManifest(
        int apiVersion,
        int minimumProtocolVersion,
        String id,
        String version,
        String name,
        List<PluginProcessContribution> processes,
        List<PluginSkillContribution> skills,
        PluginSignature signature) {

    public static final int API_VERSION = 4;

    /** 校验 API/协议版本、标识及跨进程/Skill 的贡献 id 唯一性，拒绝未知 Plugin API 和重复声明。 */
    public PluginManifest {
        if (apiVersion != API_VERSION) {
            throw new IllegalArgumentException("unsupported plugin apiVersion: " + apiVersion);
        }
        if (minimumProtocolVersion == 0) {
            minimumProtocolVersion = 1;
        }
        if (minimumProtocolVersion < 1 || minimumProtocolVersion > 1) {
            throw new IllegalArgumentException("unsupported minimumProtocolVersion: " + minimumProtocolVersion);
        }
        id = PluginValidation.id(id, "plugin id");
        version = PluginValidation.version(version);
        name = name == null ? id : name.strip();
        if (name.isEmpty() || name.length() > 200) {
            throw new IllegalArgumentException("plugin name is invalid");
        }
        processes = processes == null ? List.of() : List.copyOf(processes);
        skills = skills == null ? List.of() : List.copyOf(skills);
        HashSet<String> contributionIds = new HashSet<>();
        processes.forEach(value -> {
            if (!contributionIds.add(value.id())) {
                throw new IllegalArgumentException("duplicate contribution id: " + value.id());
            }
        });
        skills.forEach(value -> {
            if (!contributionIds.add(value.id())) {
                throw new IllegalArgumentException("duplicate contribution id: " + value.id());
            }
        });
    }
}
