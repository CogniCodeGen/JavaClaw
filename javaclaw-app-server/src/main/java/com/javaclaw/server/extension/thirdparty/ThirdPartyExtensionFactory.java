package com.javaclaw.server.extension.thirdparty;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ExtensionTrustKeyRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ThirdPartyExtensionRepository;
import com.javaclaw.server.persistence.ThirdPartyTrashRepository;

/** 第三方 Host 的显式依赖装配，不承担运行期服务定位。 */
final class ThirdPartyExtensionFactory {
    private ThirdPartyExtensionFactory() {}

    static ThirdPartyExtensionHost start(Bootstrap bootstrap) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        ThirdPartyBundleRegistry registry = new ThirdPartyBundleRegistry();
        ThirdPartyWorkerActionHandler actions = new ThirdPartyWorkerActionHandler(
                bootstrap.database(),
                bootstrap.json(),
                bootstrap.clock(),
                bootstrap.execution().network());
        ThirdPartyWorkerClient workers =
                new ThirdPartyWorkerClient(bootstrap.execution().sandbox(), bootstrap.json(), actions);
        ThirdPartyExtensionRepository repository =
                new ThirdPartyExtensionRepository(bootstrap.database(), bootstrap.json(), bootstrap.clock());
        ExtensionTrustKeyRepository trustRepository =
                new ExtensionTrustKeyRepository(bootstrap.database(), bootstrap.clock());
        ExtensionTrustedKeys trustedKeys = ExtensionTrustedKeys.from(trustRepository.list());
        ThirdPartyBundleArchive archives =
                new ThirdPartyBundleArchive(bootstrap.json(), trustedKeys, bootstrap.directories());
        ThirdPartyTrashRepository trashRepository =
                new ThirdPartyTrashRepository(bootstrap.database(), bootstrap.json(), bootstrap.clock());
        Set<String> reservedTools = bootstrap.reservedTools().stream()
                .map(tool -> tool.identity().name())
                .collect(java.util.stream.Collectors.toSet());
        ThirdPartyTrashManager trash = new ThirdPartyTrashManager(new ThirdPartyTrashManager.Dependencies(
                archives,
                new ThirdPartyBundleCompiler(bootstrap.json()),
                bootstrap.directories(),
                repository,
                trashRepository,
                registry,
                reservedTools,
                bootstrap.clock()));
        ThirdPartyBundleManager manager = new ThirdPartyBundleManager(new ThirdPartyBundleManager.Dependencies(
                bootstrap.attachments(),
                archives,
                bootstrap.directories(),
                new ThirdPartyBundleCompiler(bootstrap.json()),
                workers,
                repository,
                trustRepository,
                registry,
                trash,
                reservedTools,
                bootstrap.clock()));
        ExtensionTrustKeyService trust =
                new ExtensionTrustKeyService(bootstrap.attachments(), trustRepository, trustedKeys, registry);
        ThirdPartyExtensionHost host = new ThirdPartyExtensionHost(new ThirdPartyExtensionHost.Dependencies(
                repository,
                new ExtensionCatalogRepository(bootstrap.database(), bootstrap.json(), bootstrap.clock()),
                registry,
                manager,
                trash,
                trust,
                workers,
                bootstrap.core(),
                bootstrap.clock()));
        manager.recover();
        return host;
    }

    record Bootstrap(
            H2Database database,
            CanonicalJson json,
            Clock clock,
            ThirdPartyExtensionHost.ExecutionPorts execution,
            CoreCommandService core,
            List<ToolDescriptor> reservedTools,
            ThirdPartyBundleDirectories directories,
            AttachmentService attachments) {
        Bootstrap {
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(core, "core");
            reservedTools = List.copyOf(reservedTools);
            Objects.requireNonNull(directories, "directories");
            Objects.requireNonNull(attachments, "attachments");
        }
    }
}
