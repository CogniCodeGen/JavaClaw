package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Coding v1 托管工具链与 Workspace 环境；版本选择不授予执行或联网权限。 */
public final class CodingEnvironmentContracts {
    private CodingEnvironmentContracts() {}

    /** 无参数业务查询；与扩展调用信封分离。 */
    public record Empty() {}

    /** 应用管理的工具链种类。 */
    public enum ToolchainKind {
        /** Java Development Kit。 */
        JDK,
        /** Apache Maven。 */
        MAVEN,
        /** Gradle。 */
        GRADLE,
        /** Node.js。 */
        NODE,
        /** npm。 */
        NPM,
        /** pnpm。 */
        PNPM,
        /** Python。 */
        PYTHON,
        /** pip。 */
        PIP
    }

    /**
     * 精确工具链制品引用；同版本的不同平台制品具有不同摘要。
     *
     * @param kind 工具链种类
     * @param version 明确版本，最多 100 字符
     * @param artifactSha256 下载制品完整 SHA-256
     */
    public record ToolchainRef(ToolchainKind kind, String version, String artifactSha256) {
        /** 校验精确制品引用。 */
        public ToolchainRef {
            Objects.requireNonNull(kind, "kind");
            version = CodingContractValidation.text(version, 100, "version");
            artifactSha256 = CodingContractValidation.digest(artifactSha256);
        }
    }

    /**
     * 制品内可执行文件注册信息。
     *
     * @param name 模型命令使用的标识，不从宿主 PATH 解析
     * @param relativePath 制品安装根内的路径
     */
    public record Executable(String name, String relativePath) {
        /** 校验标识与相对路径。 */
        public Executable {
            name = CodingContractValidation.id(name);
            relativePath = CodingContractValidation.path(relativePath);
        }
    }

    /**
     * 服务端可信目录提供的下载清单；客户端不能通过 install 提交任意 URL。
     *
     * @param reference 精确制品引用
     * @param platform 目标操作系统标识
     * @param architecture 目标架构标识
     * @param downloadUri 公共 HTTPS 制品地址，不含凭据或片段
     * @param archiveFormat 平台支持的格式标识
     * @param executablePaths 可执行标识到制品内相对路径的注册表
     * @param downloadBytes 下载字节数硬上限，正数；不要求远端 Content-Length 与此值相等
     * @param license 制品许可证标识，纳入发行清单
     */
    public record ToolchainArtifact(
            ToolchainRef reference,
            String platform,
            String architecture,
            URI downloadUri,
            String archiveFormat,
            Map<String, String> executablePaths,
            long downloadBytes,
            String license) {
        /** 校验下载元数据并固定注册清单。 */
        public ToolchainArtifact {
            Objects.requireNonNull(reference, "reference");
            platform = CodingContractValidation.id(platform);
            architecture = CodingContractValidation.id(architecture);
            Objects.requireNonNull(downloadUri, "downloadUri");
            if (!"https".equalsIgnoreCase(downloadUri.getScheme())
                    || downloadUri.getHost() == null
                    || downloadUri.getUserInfo() != null
                    || downloadUri.getFragment() != null) {
                throw new IllegalArgumentException("toolchain artifact requires public HTTPS metadata");
            }
            archiveFormat = CodingContractValidation.text(archiveFormat, 20, "archiveFormat");
            if (!Set.of("zip", "tar.gz", "tar.xz").contains(archiveFormat)) {
                throw new IllegalArgumentException("unsupported archive format");
            }
            executablePaths = Map.copyOf(executablePaths);
            executablePaths.forEach((name, path) -> {
                CodingContractValidation.id(name);
                CodingContractValidation.path(path);
            });
            license = CodingContractValidation.text(license, 200, "license");
            if (executablePaths.isEmpty() || downloadBytes < 1) {
                throw new IllegalArgumentException("artifact requires executables and positive downloadBytes");
            }
        }
    }

    /**
     * 当前发行目录提供的工具链。
     *
     * @param artifacts 当前宿主适用的不可变制品列表
     */
    public record Catalog(List<ToolchainArtifact> artifacts) {
        /** 固定目录列表。 */
        public Catalog {
            artifacts = List.copyOf(artifacts);
        }
    }

    /** 制品安装状态；失败不等于已安装。 */
    public enum InstallationState {
        /** 有安装作业正在执行。 */
        INSTALLING,
        /** 完整制品已经校验并完成安装。 */
        READY,
        /** 安装失败，错误码保留供展示。 */
        FAILED
    }

    /**
     * 脱敏安装记录；不暴露内部宿主目录。
     *
     * @param reference 精确制品引用
     * @param installationId 应用拥有的安装标识
     * @param state 当前安装状态
     * @param installedAt 安装成功时间；未成功时为空
     * @param errorCode 失败码，成功时为空
     */
    public record InstalledToolchain(
            ToolchainRef reference,
            String installationId,
            InstallationState state,
            Optional<Instant> installedAt,
            Optional<String> errorCode) {
        /** 校验安装记录，保留真实就绪条件。 */
        public InstalledToolchain {
            Objects.requireNonNull(reference, "reference");
            installationId = CodingContractValidation.id(installationId);
            Objects.requireNonNull(state, "state");
            installedAt = Objects.requireNonNull(installedAt, "installedAt");
            errorCode = Objects.requireNonNull(errorCode, "errorCode").map(CodingContractValidation::id);
            if ((state == InstallationState.READY) != installedAt.isPresent()
                    || (state == InstallationState.FAILED) != errorCode.isPresent()) {
                throw new IllegalArgumentException("installation evidence does not match state");
            }
        }
    }

    /**
     * 应用托管的已知安装。
     *
     * @param toolchains 不可变安装列表
     */
    public record InstalledList(List<InstalledToolchain> toolchains) {
        /** 固定安装列表。 */
        public InstalledList {
            toolchains = List.copyOf(toolchains);
        }
    }

    /**
     * 安装一个可信目录中的精确制品；不执行项目脚本。
     *
     * @param reference 必须命中服务端可信目录的制品
     */
    public record InstallRequest(ToolchainRef reference) {
        /** 校验制品引用。 */
        public InstallRequest {
            Objects.requireNonNull(reference, "reference");
        }
    }

    /**
     * 安装作业受理结果；同摘要可复用正在进行的作业。
     *
     * @param jobId 受治理的 Extension Job 标识
     * @param artifactSha256 所安装制品摘要
     */
    public record InstallAccepted(String jobId, String artifactSha256) {
        /** 校验安装归属。 */
        public InstallAccepted {
            jobId = CodingContractValidation.id(jobId);
            artifactSha256 = CodingContractValidation.digest(artifactSha256);
        }
    }

    /**
     * Workspace Coding 环境；实际授权仍由当前 Turn 的权限控制。
     *
     * @param name 用户可见名称
     * @param toolchains 精确工具链版本，不允许同一种类重复
     * @param repositoryHosts 依赖阶段请求的公共 HTTPS 仓库主机；服务端另做 DNS 与代理检查
     * @param allowLifecycleScripts 是否允许在准备阶段执行包生命周期脚本，仍需 Turn 授权
     */
    public record EnvironmentSpec(
            String name, List<ToolchainRef> toolchains, Set<String> repositoryHosts, boolean allowLifecycleScripts) {
        /** 校验选择，不接受 URL、端口、通配符或凭据作为仓库主机。 */
        public EnvironmentSpec {
            name = CodingContractValidation.text(name, 200, "name");
            toolchains = List.copyOf(toolchains);
            if (toolchains.stream().map(ToolchainRef::kind).distinct().count() != toolchains.size()) {
                throw new IllegalArgumentException("duplicate toolchain kind");
            }
            repositoryHosts = Set.copyOf(repositoryHosts);
            if (repositoryHosts.size() > 50
                    || repositoryHosts.stream()
                            .anyMatch(host -> !host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?"))) {
                throw new IllegalArgumentException("repositoryHosts must be bounded DNS host names");
            }
        }
    }

    /**
     * Workspace 环境版本。
     *
     * @param revision 当前乐观锁版本，未保存的缺省配置为 0
     * @param spec 环境正文
     */
    public record Environment(long revision, EnvironmentSpec spec) {
        /** 校验版本和环境。 */
        public Environment {
            CodingContractValidation.nonNegative(revision, "revision");
            Objects.requireNonNull(spec, "spec");
        }
    }

    /**
     * 替换 Workspace 环境正文；expectedRevision 位于通用 WriteCommand。
     *
     * @param spec 新环境正文
     */
    public record EnvironmentUpdate(EnvironmentSpec spec) {
        /** 校验正文。 */
        public EnvironmentUpdate {
            Objects.requireNonNull(spec, "spec");
        }
    }
}
