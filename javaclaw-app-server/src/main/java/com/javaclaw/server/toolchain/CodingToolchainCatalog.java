package com.javaclaw.server.toolchain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Catalog;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.EnvironmentSpec;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;
import com.javaclaw.protocol.CanonicalJson;

/** 发行资源中的精确制品目录；管理请求只能选择目录条目，不能提交下载地址或可执行路径。 */
public final class CodingToolchainCatalog {
    private final Catalog catalog;

    /**
     * 创建指定宿主的可信制品目录。
     *
     * @param artifacts 发行或测试组合根提供的可信条目
     * @param platform 宿主 macos、linux 或 windows
     * @param architecture 宿主 arm64 或 x64
     */
    public CodingToolchainCatalog(List<ToolchainArtifact> artifacts, String platform, String architecture) {
        catalog = new Catalog(artifacts.stream()
                .filter(artifact -> artifact.platform().equals(platform)
                        && artifact.architecture().equals(architecture))
                .toList());
        if (catalog.artifacts().stream()
                        .map(ToolchainArtifact::reference)
                        .distinct()
                        .count()
                != catalog.artifacts().size()) {
            throw new IllegalArgumentException("发行目录包含重复工具链引用");
        }
    }

    /**
     * 从随程序发布的不可变资源读取本机目录；运行期间不解析 latest。
     *
     * @return 当前宿主目录
     */
    public static CodingToolchainCatalog bundled() {
        try (InputStream input = CodingToolchainCatalog.class.getResourceAsStream("/coding/toolchains-v1.json")) {
            if (input == null) {
                throw new IllegalStateException("缺少 Coding 工具链发行目录");
            }
            CanonicalPayload payload = new CanonicalPayload(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            Catalog source = new CanonicalJson().decode(payload, Catalog.class);
            return new CodingToolchainCatalog(source.artifacts(), platform(), architecture());
        } catch (IOException failure) {
            throw new IllegalStateException("读取 Coding 工具链目录失败", failure);
        }
    }

    /** @return 当前宿主的只读制品目录 */
    public Catalog list() {
        return catalog;
    }

    /**
     * 解析精确制品，拒绝未知版本与跨平台摘要。
     *
     * @param reference 用户选择的精确引用
     * @return 可信制品描述
     */
    public ToolchainArtifact require(ToolchainRef reference) {
        Objects.requireNonNull(reference, "reference");
        return catalog.artifacts().stream()
                .filter(artifact -> artifact.reference().equals(reference))
                .findFirst()
                .orElseThrow(() -> new SecurityException("工具链不属于当前宿主的可信发行目录"));
    }

    /**
     * 返回发行顺序中每一种工具链的默认版本；不自动安装也不授予网络权限。
     *
     * @return 可在 Turn 创建时原子冻结的缺省环境
     */
    public EnvironmentSpec defaultEnvironment() {
        var selected = new java.util.LinkedHashMap<
                com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind, ToolchainRef>();
        catalog.artifacts()
                .forEach(artifact -> selected.putIfAbsent(artifact.reference().kind(), artifact.reference()));
        return new EnvironmentSpec("默认开发环境", List.copyOf(selected.values()), Set.of(), true);
    }

    /** @return 规范宿主操作系统标识；未知系统明确返回 unsupported */
    public static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "macos";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return os.contains("windows") ? "windows" : "unsupported";
    }

    /** @return 规范宿主架构；不把未知架构静默当 x64 */
    public static String architecture() {
        String architecture = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return switch (architecture) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64", "x64" -> "x64";
            default -> "unsupported";
        };
    }
}
