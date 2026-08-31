package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 已验证插件包及其解析路径快照；声明代码仍只能在沙箱进程中执行。
 *
 * @param manifest 非空 Plugin 4.0 声明
 * @param bundleRoot 非空、规范化插件包根目录
 * @param signatureVerified 签名验证结果；只证明来源，不授予执行权限
 * @param processes 非空已解析进程列表，构造时复制
 * @param skills 非空已解析 Skill 列表，构造时复制
 */
public record LoadedPlugin(
        PluginManifest manifest,
        Path bundleRoot,
        boolean signatureVerified,
        List<ResolvedProcess> processes,
        List<ResolvedSkill> skills) {

    /** 规范化包目录并复制贡献列表；路径安全验证由 Loader 在创建此快照前完成。 */
    public LoadedPlugin {
        manifest = Objects.requireNonNull(manifest, "manifest");
        bundleRoot = Objects.requireNonNull(bundleRoot, "bundleRoot")
                .toAbsolutePath()
                .normalize();
        processes = List.copyOf(processes);
        skills = List.copyOf(skills);
    }

    /** 以贡献 id 建立不可变进程索引；重复 id 将拒绝，不能静默覆盖入口。 */
    public Map<String, ResolvedProcess> processMap() {
        return processes.stream()
                .collect(Collectors.toUnmodifiableMap(
                        value -> value.declaration().id(), Function.identity()));
    }

    /**
     * 进程声明与已验证平台入口的配对，不是正在运行的 Process。
     *
     * @param declaration 非空进程声明
     * @param entrypoint 非空绝对入口路径，Loader 已验证其处于包内
     */
    public record ResolvedProcess(PluginProcessContribution declaration, Path entrypoint) {
        /** 要求声明和入口存在并规范化路径；不启动进程。 */
        public ResolvedProcess {
            declaration = Objects.requireNonNull(declaration, "declaration");
            entrypoint = Objects.requireNonNull(entrypoint, "entrypoint")
                    .toAbsolutePath()
                    .normalize();
        }
    }

    /**
     * Skill 声明与包内内容路径的配对。
     *
     * @param declaration 非空 Skill 声明
     * @param path 非空绝对内容路径，Loader 已验证其处于包内
     */
    public record ResolvedSkill(PluginSkillContribution declaration, Path path) {
        /** 固定声明和规范化内容路径；不加载第三方 JVM 代码。 */
        public ResolvedSkill {
            declaration = Objects.requireNonNull(declaration, "declaration");
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        }
    }
}
