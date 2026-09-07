package com.javaclaw.server.toolchain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.EnvironmentSpec;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;

/** 在 H2 事务之外读取固定声明，并只在发行目录内选择兼容版本；显式 Workspace 版本保持不变。 */
public final class ProjectToolchainSelector {
    private final CodingToolchainCatalog catalog;
    private final DeclarationReader reader;

    /**
     * 使用受限原生文件 Worker 创建选择器。
     *
     * @param catalog 当前宿主可信目录
     */
    public ProjectToolchainSelector(CodingToolchainCatalog catalog) {
        this(catalog, ProjectToolchainSelector::readNative);
    }

    /**
     * 注入受控读取边界，用于平台组合与离线验证。
     *
     * @param catalog 可信目录
     * @param reader 仅返回固定声明的受限读取器
     */
    public ProjectToolchainSelector(CodingToolchainCatalog catalog, DeclarationReader reader) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.reader = java.util.Objects.requireNonNull(reader, "reader");
    }

    /**
     * 准备可持久冻结的选择；读取失败保存诊断，不把未检查的项目声明当成兼容证据。
     *
     * @param root 权威项目根
     * @param permission 冻结的文件权限上限
     * @param environment 当前环境；revision 为 0 时允许从目录选择默认兼容版本
     * @return 精确版本及声明证据
     */
    public CodingEnvironmentSelection select(Path root, PermissionProfile permission, Environment environment) {
        try {
            Map<String, WorkspaceFileAccess.Snapshot> snapshots = reader.read(root, permission);
            Map<String, String> content = new LinkedHashMap<>();
            Map<String, String> hashes = new LinkedHashMap<>();
            snapshots.forEach((path, snapshot) -> {
                if (!ProjectToolchainDeclarations.paths().contains(path)
                        || !snapshot.path().equals(path)) {
                    throw new SecurityException("读取器返回非固定项目声明");
                }
                if (snapshot.exists()) {
                    content.put(path, new String(snapshot.content(), StandardCharsets.UTF_8));
                    hashes.put(path, snapshot.sha256());
                }
            });
            if (!snapshots.keySet().containsAll(ProjectToolchainDeclarations.paths())) {
                throw new SecurityException("读取器缺少固定声明的存在性证据");
            }
            return choose(environment, ProjectToolchainDeclarations.parse(content), hashes);
        } catch (Exception failure) {
            if (com.javaclaw.nativehost.sandbox.SandboxIsolationFailure.evidence(failure)
                    .isPresent()) {
                throw new IllegalStateException("WORKSPACE_SECURITY_LOCKED: 原生项目声明读取未能恢复权限", failure);
            }
            return new CodingEnvironmentSelection(environment, Map.of(), Map.of(), false);
        }
    }

    private CodingEnvironmentSelection choose(
            Environment environment, ProjectToolchainDeclarations declarations, Map<String, String> hashes) {
        Map<ToolchainKind, List<String>> issues = new EnumMap<>(ToolchainKind.class);
        declarations.errors().forEach((kind, errors) -> issues.put(kind, new ArrayList<>(errors)));
        Map<ToolchainKind, ToolchainRef> selected = new EnumMap<>(ToolchainKind.class);
        environment.spec().toolchains().forEach(reference -> selected.put(reference.kind(), reference));
        declarations.constraints().forEach((kind, constraints) -> {
            if (environment.revision() == 0) {
                catalog.list().artifacts().stream()
                        .map(artifact -> artifact.reference())
                        .filter(reference -> reference.kind() == kind && matches(reference, constraints))
                        .findFirst()
                        .ifPresent(reference -> selected.put(kind, reference));
            }
            ToolchainRef reference = selected.get(kind);
            if (reference == null || !matches(reference, constraints)) {
                issue(issues, kind, "当前精确版本不满足项目声明 " + String.join(", ", constraints));
            }
        });
        javaCompatibility(environment.revision() == 0, selected, declarations.constraints(), issues);
        pair(selected, ToolchainKind.NODE, ToolchainKind.NPM, issues);
        pair(selected, ToolchainKind.PYTHON, ToolchainKind.PIP, issues);
        var spec = environment.revision() > 0
                ? environment.spec()
                : new EnvironmentSpec(
                        environment.spec().name(),
                        List.copyOf(selected.values()),
                        environment.spec().repositoryHosts(),
                        environment.spec().allowLifecycleScripts());
        return new CodingEnvironmentSelection(new Environment(environment.revision(), spec), hashes, issues, true);
    }

    private void javaCompatibility(
            boolean defaults,
            Map<ToolchainKind, ToolchainRef> selected,
            Map<ToolchainKind, List<String>> constraints,
            Map<ToolchainKind, List<String>> issues) {
        ToolchainRef gradle = selected.get(ToolchainKind.GRADLE);
        ToolchainRef java = selected.get(ToolchainKind.JDK);
        if (gradle == null || java == null) {
            return;
        }
        if (!gradle.version().startsWith("8.14") && !gradle.version().startsWith("9.1.")) {
            issue(issues, ToolchainKind.GRADLE, "发行目录缺少此 Gradle 版本的 JDK 兼容规则");
            return;
        }
        String range = gradle.version().startsWith("8.14") ? ">=8 <25" : ">=17 <26";
        if (!ToolchainVersionConstraint.matches(java.version(), range) && defaults) {
            catalog.list().artifacts().stream()
                    .map(artifact -> artifact.reference())
                    .filter(reference -> reference.kind() == ToolchainKind.JDK)
                    .filter(reference -> ToolchainVersionConstraint.matches(reference.version(), range))
                    .filter(reference -> matches(reference, constraints.getOrDefault(ToolchainKind.JDK, List.of())))
                    .findFirst()
                    .ifPresent(reference -> selected.put(ToolchainKind.JDK, reference));
        }
        if (!ToolchainVersionConstraint.matches(selected.get(ToolchainKind.JDK).version(), range)) {
            issue(issues, ToolchainKind.GRADLE, "冻结 JDK 版本不能运行所选 Gradle 发行版");
        }
    }

    private static void pair(
            Map<ToolchainKind, ToolchainRef> selected,
            ToolchainKind runtime,
            ToolchainKind companion,
            Map<ToolchainKind, List<String>> issues) {
        if (selected.containsKey(runtime)
                && selected.containsKey(companion)
                && !selected.get(runtime)
                        .artifactSha256()
                        .equals(selected.get(companion).artifactSha256())) {
            issue(issues, companion, "伴随工具必须与运行时选择同一个发行归档");
        }
    }

    private static boolean matches(ToolchainRef reference, List<String> constraints) {
        return constraints.stream()
                .allMatch(value -> ToolchainVersionConstraint.matches(
                        reference.version(), value, reference.kind() == ToolchainKind.PYTHON));
    }

    private static void issue(Map<ToolchainKind, List<String>> issues, ToolchainKind kind, String message) {
        issues.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(message);
    }

    private static Map<String, WorkspaceFileAccess.Snapshot> readNative(Path root, PermissionProfile permission)
            throws Exception {
        WorkspaceFileAccess files = new WorkspaceFileAccess(root, permission);
        Map<String, WorkspaceFileAccess.Snapshot> result = new LinkedHashMap<>();
        var cancellation = new CancellationSource();
        for (String path : ProjectToolchainDeclarations.paths()) {
            result.put(path, files.read(path, 256 * 1024, cancellation));
        }
        return Map.copyOf(result);
    }

    /** 只读取固定声明，不执行项目脚本或跟随声明内的路径。 */
    @FunctionalInterface
    public interface DeclarationReader {
        /**
         * 返回所有固定文件的存在性和有界正文。
         *
         * @param root 权威项目根
         * @param permission 文件权限上限
         * @return 包含不存在文件的不可变快照集合
         * @throws Exception 原生隔离或读取失败
         */
        Map<String, WorkspaceFileAccess.Snapshot> read(Path root, PermissionProfile permission) throws Exception;
    }
}
