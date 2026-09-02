package com.javaclaw.server.extension.thirdparty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ViewSchemaWireCodec;

/** 把已验证 manifest 编译为无歧义的调用、工具、Schema 和 View 索引。 */
final class ThirdPartyBundleCompiler {
    private final CanonicalJson json;
    private final ViewSchemaWireCodec views;

    ThirdPartyBundleCompiler(CanonicalJson json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
        views = new ViewSchemaWireCodec(json);
    }

    InstalledThirdPartyBundle compile(VerifiedThirdPartyBundle bundle, long revision) {
        ThirdPartyBundleManifest manifest = bundle.manifest();
        ExtensionDescriptor descriptor = new ExtensionDescriptor(
                new ExtensionId(manifest.id()),
                manifest.displayName(),
                manifest.version(),
                revision,
                manifest.contributionKinds(),
                new ExtensionRequirements(
                        ExtensionTrust.THIRD_PARTY,
                        ExtensionAvailability.OPTIONAL,
                        2,
                        ThirdPartyPermissionPolicy.descriptorPermission(manifest, bundle.root(), json)));
        LinkedHashMap<String, String> queries = new LinkedHashMap<>();
        LinkedHashMap<String, String> commands = new LinkedHashMap<>();
        LinkedHashMap<String, InstalledThirdPartyBundle.ToolContribution> tools = new LinkedHashMap<>();
        java.util.ArrayList<ExtensionRpcContracts.ViewDocument> viewDocuments = new java.util.ArrayList<>();
        for (ThirdPartyBundleManifest.Contribution contribution : manifest.contributions()) {
            switch (contribution.kind()) {
                case QUERY -> addOperations(queries, contribution);
                case COMMAND, ORCHESTRATOR -> addOperations(commands, contribution);
                case TOOL -> addTool(tools, manifest, revision, contribution);
                case VIEW -> addView(viewDocuments, manifest.id(), contribution);
                default -> {
                    // Resource、Timer、MCP、Hook 与 Service 由专用平台消费者读取签名描述。
                }
            }
        }
        List<ExtensionSchema> schemas = manifest.schemas().stream()
                .map(schema -> new ExtensionSchema(schema.schemaId(), schema.schema()))
                .toList();
        return new InstalledThirdPartyBundle(
                manifest,
                bundle.manifestDigest(),
                bundle.root(),
                descriptor,
                queries,
                commands,
                tools,
                schemas,
                viewDocuments,
                manifest.contributionKinds());
    }

    private void addTool(
            Map<String, InstalledThirdPartyBundle.ToolContribution> target,
            ThirdPartyBundleManifest manifest,
            long revision,
            ThirdPartyBundleManifest.Contribution contribution) {
        ThirdPartyToolDefinition definition =
                json.decode(contribution.descriptor().orElseThrow(), ThirdPartyToolDefinition.class);
        ToolDescriptor descriptor = definition.descriptor(manifest.id(), revision, json);
        if (definition.risk().ordinal()
                > manifest.permissions().maximumToolRisk().ordinal()) {
            throw new IllegalArgumentException("tool risk exceeds signed permission request");
        }
        if (target.put(
                        descriptor.identity().name(),
                        new InstalledThirdPartyBundle.ToolContribution(contribution.contributionId(), descriptor))
                != null) {
            throw new IllegalArgumentException(
                    "duplicate third-party tool name: " + descriptor.identity().name());
        }
    }

    private void addView(
            List<ExtensionRpcContracts.ViewDocument> target,
            String extensionId,
            ThirdPartyBundleManifest.Contribution contribution) {
        var schema = views.decode(contribution.descriptor().orElseThrow());
        target.add(new ExtensionRpcContracts.ViewDocument(extensionId, schema.viewId(), views.encode(schema)));
    }

    private static void addOperations(Map<String, String> target, ThirdPartyBundleManifest.Contribution contribution) {
        for (String operation : contribution.operations()) {
            if (target.put(operation, contribution.contributionId()) != null) {
                throw new IllegalArgumentException("duplicate third-party operation: " + operation);
            }
        }
    }
}
