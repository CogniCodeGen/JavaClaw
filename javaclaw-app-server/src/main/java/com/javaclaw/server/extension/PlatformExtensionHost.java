package com.javaclaw.server.extension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.server.extension.contract.ExtensionHost;
import com.javaclaw.server.extension.contract.GovernedExtensionResponse;
import com.javaclaw.server.extension.thirdparty.ThirdPartyExtensionHost;

/** 将可信内置和进程外第三方 Extension 合并为唯一 Core 调用入口。 */
public final class PlatformExtensionHost implements ExtensionHost {
    private final BuiltinExtensionHost builtins;
    private final ThirdPartyExtensionHost thirdParty;

    /**
     * 创建平台 Host，并立即拒绝跨信任层工具重名。
     *
     * @param builtins 内置 Host
     * @param thirdParty 第三方 Host
     */
    public PlatformExtensionHost(BuiltinExtensionHost builtins, ThirdPartyExtensionHost thirdParty) {
        this.builtins = Objects.requireNonNull(builtins, "builtins");
        this.thirdParty = Objects.requireNonNull(thirdParty, "thirdParty");
        mergeTools();
    }

    @Override
    public List<ExtensionRpcContracts.Summary> list() {
        return java.util.stream.Stream.concat(builtins.list().stream(), thirdParty.list().stream())
                .sorted(Comparator.comparing(ExtensionRpcContracts.Summary::id))
                .toList();
    }

    @Override
    public List<ToolDescriptor> tools() {
        return mergeTools();
    }

    @Override
    public ExtensionResponse executeTool(
            ToolCallRequest request,
            ToolDescriptor frozenDescriptor,
            PermissionProfile callerPermissions,
            CancellationToken cancellation)
            throws Exception {
        return host(frozenDescriptor.identity().producerId())
                .executeTool(request, frozenDescriptor, callerPermissions, cancellation);
    }

    @Override
    public GovernedExtensionResponse executeToolWithFacts(
            ToolCallRequest request,
            ToolDescriptor frozenDescriptor,
            PermissionProfile callerPermissions,
            CancellationToken cancellation)
            throws Exception {
        return host(frozenDescriptor.identity().producerId())
                .executeToolWithFacts(request, frozenDescriptor, callerPermissions, cancellation);
    }

    @Override
    public ExtensionResponse query(ExtensionRpcContracts.CallPayload call) throws Exception {
        return host(call.extensionId()).query(call);
    }

    @Override
    public ExtensionResponse command(
            ExtensionRpcContracts.CallPayload call, String idempotencyKey, long expectedRevision) throws Exception {
        return host(call.extensionId()).command(call, idempotencyKey, expectedRevision);
    }

    @Override
    public ExtensionSchema schema(String extensionId, String schemaId) {
        return host(extensionId).schema(extensionId, schemaId);
    }

    @Override
    public List<ExtensionRpcContracts.ViewDocument> views(Optional<String> extensionId) {
        if (extensionId.isPresent()) {
            String id = extensionId.orElseThrow();
            return host(id).views(Optional.of(id));
        }
        return java.util.stream.Stream.concat(
                        builtins.views(Optional.empty()).stream(), thirdParty.views(Optional.empty()).stream())
                .sorted(Comparator.comparing(ExtensionRpcContracts.ViewDocument::extensionId)
                        .thenComparing(ExtensionRpcContracts.ViewDocument::viewId))
                .toList();
    }

    /** 先关闭不可信进程边界，再逆序关闭内置 Bundle。 */
    @Override
    public void close() throws Exception {
        Exception failure = null;
        try {
            thirdParty.close();
        } catch (Exception closeFailure) {
            failure = closeFailure;
        }
        try {
            builtins.close();
        } catch (Exception closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private ExtensionHost host(String extensionId) {
        return thirdParty.installed(extensionId) ? thirdParty : builtins;
    }

    private List<ToolDescriptor> mergeTools() {
        LinkedHashMap<String, ToolDescriptor> unique = new LinkedHashMap<>();
        ArrayList<ToolDescriptor> all = new ArrayList<>(builtins.tools());
        all.addAll(thirdParty.tools());
        for (ToolDescriptor tool : all) {
            if (unique.put(tool.identity().name(), tool) != null) {
                throw new IllegalStateException("duplicate tool name across extension trust levels: "
                        + tool.identity().name());
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(tool -> tool.identity().name()))
                .toList();
    }
}
