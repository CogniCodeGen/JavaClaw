package com.javaclaw.server;

import java.util.Objects;

import com.javaclaw.server.extension.contract.ExtensionHost;

/** 以依赖逆序关闭后台任务、Turn、审批、扩展与隔离 Worker。 */
record AppServerResources(
        AutoCloseable extensionJobs,
        AutoCloseable extensionJobInputs,
        AutoCloseable inputLifecycle,
        AutoCloseable approvalExpiration,
        AutoCloseable approvalLifecycle,
        AutoCloseable turns,
        AutoCloseable approvals,
        ExtensionHost extensions,
        AutoCloseable scheduleLifecycle,
        AutoCloseable modelDiscovery,
        AutoCloseable embeddings,
        AutoCloseable vault,
        AutoCloseable isolatedServices)
        implements AutoCloseable {
    AppServerResources {
        Objects.requireNonNull(extensionJobs, "extensionJobs");
        Objects.requireNonNull(extensionJobInputs, "extensionJobInputs");
        Objects.requireNonNull(inputLifecycle, "inputLifecycle");
        Objects.requireNonNull(approvalExpiration, "approvalExpiration");
        Objects.requireNonNull(approvalLifecycle, "approvalLifecycle");
        Objects.requireNonNull(turns, "turns");
        Objects.requireNonNull(approvals, "approvals");
        Objects.requireNonNull(extensions, "extensions");
        Objects.requireNonNull(scheduleLifecycle, "scheduleLifecycle");
        Objects.requireNonNull(modelDiscovery, "modelDiscovery");
        Objects.requireNonNull(embeddings, "embeddings");
        Objects.requireNonNull(vault, "vault");
        Objects.requireNonNull(isolatedServices, "isolatedServices");
    }

    @Override
    public void close() throws Exception {
        Exception failure = close(extensionJobs, null);
        failure = close(extensionJobInputs, failure);
        failure = close(inputLifecycle, failure);
        failure = close(approvalExpiration, failure);
        failure = close(approvalLifecycle, failure);
        failure = close(turns, failure);
        failure = close(approvals, failure);
        failure = close(extensions, failure);
        failure = close(scheduleLifecycle, failure);
        failure = close(modelDiscovery, failure);
        failure = close(embeddings, failure);
        failure = close(vault, failure);
        failure = close(isolatedServices, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static Exception close(AutoCloseable resource, Exception prior) {
        try {
            resource.close();
            return prior;
        } catch (Exception closeFailure) {
            if (prior == null) {
                return closeFailure;
            }
            prior.addSuppressed(closeFailure);
            return prior;
        }
    }
}
