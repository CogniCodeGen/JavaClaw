package com.javaclaw.server.extension;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.WorkspaceExecutionPort;

/** 将组合根端口装配为单次调用上下文；执行能力始终取当前调用绑定，不能从全局端口获取。 */
final class BuiltinExtensionContexts {
    private BuiltinExtensionContexts() {}

    static ExtensionExecutionContext create(
            BuiltinExtensionRuntimePorts ports,
            ScheduleTargetCatalogPort scheduleTargets,
            RegisteredExtension extension,
            Workspace workspace,
            PermissionProfile effective,
            CancellationToken cancellation,
            WorkspaceExecutionPort execution) {
        return new ExtensionExecutionContext(
                extension.descriptor(),
                workspace.id(),
                effective,
                cancellation,
                ports.clock(),
                ports.managedStore(),
                ports.turns(),
                ports.executionPolicies(),
                scheduleTargets,
                ports.inputs(),
                ports.jobs(),
                ports.evidence(),
                ports.attachments().apply(workspace.id()),
                ports.credentials(),
                ports.privateNetworkGrants(),
                ports.services(),
                ports.embeddings(),
                execution,
                ports.scheduleBindings().apply(extension.descriptor().id()));
    }
}
