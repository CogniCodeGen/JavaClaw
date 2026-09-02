package com.javaclaw.server.extension.thirdparty;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 已安装 Bundle 的强类型、不可变运行索引。 */
record InstalledThirdPartyBundle(
        ThirdPartyBundleManifest manifest,
        String manifestDigest,
        Path root,
        ExtensionDescriptor descriptor,
        Map<String, String> queryOperations,
        Map<String, String> commandOperations,
        Map<String, ToolContribution> tools,
        List<ExtensionSchema> schemas,
        List<ExtensionRpcContracts.ViewDocument> views,
        Set<com.javaclaw.extension.spi.ContributionKind> contributionKinds) {
    InstalledThirdPartyBundle {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(manifestDigest, "manifestDigest");
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Objects.requireNonNull(descriptor, "descriptor");
        queryOperations = Map.copyOf(queryOperations);
        commandOperations = Map.copyOf(commandOperations);
        tools = Map.copyOf(tools);
        schemas = List.copyOf(schemas);
        views = List.copyOf(views);
        contributionKinds = Set.copyOf(contributionKinds);
    }

    record ToolContribution(String contributionId, ToolDescriptor descriptor) {
        ToolContribution {
            if (contributionId == null || contributionId.isBlank()) {
                throw new IllegalArgumentException("contributionId must not be blank");
            }
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
