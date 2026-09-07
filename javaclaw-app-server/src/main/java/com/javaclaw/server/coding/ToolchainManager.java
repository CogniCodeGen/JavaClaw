package com.javaclaw.server.coding;

import java.io.IOException;
import java.time.Clock;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Catalog;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstallAccepted;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstallRequest;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstallationState;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstalledList;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobPort;
import com.javaclaw.extension.spi.ExtensionJobRegistrar;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CodingToolchainRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;
import com.javaclaw.server.toolchain.ToolchainArtifactDownloadPort;

/** 可信工具链目录、可恢复安装 Job 和只读活动租约的应用级所有者。 安装串行发布，活动租约禁止替换其内容；关闭仅发布取消信号，不删除执行中的版本。 */
public final class ToolchainManager implements CodingToolchainPort {
    private final Dependencies dependencies;
    private final CodingToolchainRepository repository;
    private final ToolchainInstaller installer;
    private final Object ownership = new Object();
    private final Map<String, Integer> leases = new HashMap<>();
    private final java.util.Set<String> installing = new java.util.HashSet<>();
    private volatile boolean closed;

    /**
     * 绑定平台依赖；私有目录验证及 staging 恢复延迟至首次实际安装或租约验证。
     *
     * @param dependencies 平台拥有的数据库、Job、目录、JSON、时钟与流式下载器
     * @throws Exception 安装组件无法初始化
     */
    public ToolchainManager(Dependencies dependencies) throws Exception {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        repository = new CodingToolchainRepository(dependencies.database(), dependencies.json(), dependencies.clock());
        installer = new ToolchainInstaller(
                dependencies.database().dataRoot(), dependencies.downloads(), dependencies.json());
    }

    @Override
    public Catalog catalog() {
        return dependencies.catalog().list();
    }

    @Override
    public InstalledList installed(WorkspaceId workspaceId) {
        return new InstalledList(repository.list(workspaceId));
    }

    @Override
    public InstallAccepted install(ExtensionRequest request, CancellationToken cancellation) throws Exception {
        token(cancellation).throwIfCancelled();
        InstallRequest payload = dependencies.json().decode(request.payload(), InstallRequest.class);
        ToolchainArtifact artifact = dependencies.catalog().require(payload.reference());
        checkExisting(request.workspaceId(), artifact, token(cancellation));
        token(cancellation).throwIfCancelled();
        return repository.submit(request, artifact);
    }

    @Override
    public Lease acquire(WorkspaceId workspaceId, List<ToolchainRef> references) throws Exception {
        List<ToolchainRef> fixed = List.copyOf(references);
        CancellationToken cancellation = token(new com.javaclaw.api.CancellationSource());
        reserve(fixed, cancellation);
        try {
            var installed = repository.list(workspaceId);
            Map<ToolchainKind, InstalledArtifact> result = new EnumMap<>(ToolchainKind.class);
            for (ToolchainRef reference : fixed) {
                ToolchainArtifact artifact = dependencies.catalog().require(reference);
                if (installed.stream()
                        .noneMatch(item ->
                                item.reference().equals(reference) && item.state() == InstallationState.READY)) {
                    throw new IOException("TOOLCHAIN_NOT_READY: " + reference.kind());
                }
                result.put(reference.kind(), new InstalledArtifact(artifact, installer.verify(artifact, cancellation)));
            }
            cancellation.throwIfCancelled();
            return new InstallationLease(Map.copyOf(result));
        } catch (Exception failure) {
            release(fixed);
            throw failure;
        }
    }

    private void reserve(List<ToolchainRef> references, CancellationToken cancellation) throws IOException {
        synchronized (ownership) {
            cancellation.throwIfCancelled();
            if (references.stream().map(ToolchainRef::kind).distinct().count() != references.size()) {
                throw new IllegalArgumentException("同一环境不能选择两个相同种类的工具链");
            }
            for (ToolchainRef reference : references) {
                dependencies.catalog().require(reference);
                if (installing.contains(reference.artifactSha256())) {
                    throw new IOException("TOOLCHAIN_INSTALLING: 当前制品正在校验或发布");
                }
            }
            references.forEach(reference -> leases.merge(reference.artifactSha256(), 1, Integer::sum));
        }
    }

    private void release(List<ToolchainRef> references) {
        synchronized (ownership) {
            references.forEach(reference -> leases.compute(
                    reference.artifactSha256(), (ignored, count) -> count == null || count == 1 ? null : count - 1));
        }
    }

    @Override
    public void registerJobs(ExtensionJobRegistrar registrar) {
        registrar.register(
                new ExtensionId(CodingContracts.EXTENSION_ID),
                new ExtensionJobRegistration(CodingToolchainRepository.JOB_TYPE, new InstallerExecutor()));
    }

    @Override
    public void close() {
        closed = true;
    }

    private void checkExisting(WorkspaceId workspace, ToolchainArtifact artifact, CancellationToken cancellation)
            throws Exception {
        boolean ready = repository.list(workspace).stream()
                .anyMatch(item ->
                        item.reference().equals(artifact.reference()) && item.state() == InstallationState.READY);
        if (ready) {
            try {
                installer.verify(artifact, cancellation);
            } catch (IOException damaged) {
                repository.damaged(workspace, artifact.reference());
                synchronized (ownership) {
                    if (leases.containsKey(artifact.reference().artifactSha256())) {
                        throw new IOException("活动工具链已损坏，必须先结束使用它的执行", damaged);
                    }
                }
            }
        }
    }

    private CancellationToken token(CancellationToken source) {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return closed || source.isCancelled();
            }

            @Override
            public Optional<String> reason() {
                return closed ? Optional.of("工具链管理器已关闭") : source.reason();
            }
        };
    }

    private final class InstallerExecutor implements ExtensionJobExecutor {
        @Override
        public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
            return Optional.of(new ExtensionJobWorkUnit("install-" + job.definitionId(), job.frozenInput()));
        }

        @Override
        public ExtensionJobStepResult execute(ExtensionJobExecution execution, CancellationToken cancellation)
                throws Exception {
            ExtensionJob job = execution.job();
            ToolchainArtifact artifact = dependencies.json().decode(job.frozenInput(), ToolchainArtifact.class);
            if (!dependencies.catalog().require(artifact.reference()).equals(artifact)
                    || !execution.unit().intent().equals(job.frozenInput())) {
                throw new SecurityException("工具链 Job 的冻结清单与可信目录不一致");
            }
            try {
                publish(job, artifact, token(cancellation));
                var result = dependencies
                        .json()
                        .encode(new InstallationResult(artifact.reference().artifactSha256(), true));
                return new ExtensionJobStepResult(
                        result, result, ExecutionState.COMPLETED, Optional.empty(), Optional.empty());
            } catch (Exception failure) {
                try {
                    repository.finish(job, false, Optional.of("TOOLCHAIN_INSTALL_FAILED"));
                } catch (RuntimeException persistenceFailure) {
                    failure.addSuppressed(persistenceFailure);
                }
                throw failure;
            }
        }

        private void publish(ExtensionJob job, ToolchainArtifact artifact, CancellationToken cancellation)
                throws Exception {
            String digest = artifact.reference().artifactSha256();
            boolean verifyOnly;
            synchronized (ownership) {
                cancellation.throwIfCancelled();
                if (!installing.add(digest)) {
                    throw new IOException("TOOLCHAIN_INSTALLING: 同一制品正在发布");
                }
                verifyOnly = leases.containsKey(digest);
            }
            try {
                // 计数锁不跨越下载或文件扫描；其他命令能够立即释放租约并完成 Turn 资源终结。
                if (verifyOnly) {
                    installer.verify(artifact, cancellation);
                } else {
                    installer.install(artifact, cancellation);
                }
                repository.finish(job, true, Optional.empty());
            } finally {
                synchronized (ownership) {
                    installing.remove(digest);
                }
            }
        }
    }

    private final class InstallationLease implements Lease {
        private final Map<ToolchainKind, InstalledArtifact> installations;
        private final AtomicBoolean released = new AtomicBoolean();

        private InstallationLease(Map<ToolchainKind, InstalledArtifact> installations) {
            this.installations = installations;
        }

        @Override
        public Map<ToolchainKind, InstalledArtifact> installations() {
            return installations;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release(installations.values().stream()
                        .map(value -> value.artifact().reference())
                        .toList());
            }
        }
    }

    /**
     * 应用组合根注入的资源；所有组件非空，Job 生命周期仍由平台 Supervisor 管理。
     *
     * @param database data-v6 数据库与私有安装根
     * @param jobs 共享 Job 查询和控制端口
     * @param catalog 适用于当前宿主的可信发行目录
     * @param json 规范 JSON codec
     * @param clock 持久化时间来源
     * @param downloads 固定 DNS 和 HTTPS 的流式下载边界
     */
    public record Dependencies(
            H2Database database,
            ExtensionJobPort jobs,
            CodingToolchainCatalog catalog,
            CanonicalJson json,
            Clock clock,
            ToolchainArtifactDownloadPort downloads) {
        /** 校验全部依赖，防止安装时退回宿主 PATH 或不受控网络。 */
        public Dependencies {
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(jobs, "jobs");
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(downloads, "downloads");
        }
    }

    private record InstallationResult(String artifactSha256, boolean installed) {}
}
