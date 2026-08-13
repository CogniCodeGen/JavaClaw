package com.javaclaw.infrastructure.plugin;

import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService;
import com.javaclaw.framework.extension.TrustedExtensionService;
import com.javaclaw.framework.spi.ExtensionArtifactRecord;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Maps the framework's trusted-extension host onto the product application boundary. */
public final class FrameworkAgentExtensionManagementAdapter
        implements AgentExtensionManagementApplicationService {

    private final TrustedExtensionService extensions;

    public FrameworkAgentExtensionManagementAdapter(TrustedExtensionService extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    @Override
    public InstallPreview preview(Path jar) {
        var preview = extensions.preview(jar);
        return new InstallPreview(preview.path(), preview.sha256(), preview.sizeBytes());
    }

    @Override
    public List<AgentExtension> installed() {
        return map(extensions.installed());
    }

    @Override
    public List<AgentExtension> install(InstallPreview preview) {
        Objects.requireNonNull(preview, "preview");
        extensions.install(preview.path(), preview.sha256(), true);
        return installed();
    }

    @Override
    public List<AgentExtension> setEnabled(String extensionId, boolean enabled) {
        if (enabled) extensions.enable(extensionId);
        else extensions.disable(extensionId);
        return installed();
    }

    private static List<AgentExtension> map(List<ExtensionArtifactRecord> records) {
        LinkedHashMap<String, List<ExtensionArtifactRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(
                        ExtensionArtifactRecord::extensionId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return grouped.entrySet().stream().map(entry -> {
            List<ExtensionArtifactRecord> artifacts = entry.getValue();
            String versions = artifacts.stream().map(record -> record.version().toString())
                    .distinct().collect(Collectors.joining(", "));
            String hashes = artifacts.stream().map(ExtensionArtifactRecord::artifactSha256)
                    .distinct().collect(Collectors.joining(", "));
            boolean enabled = artifacts.stream().anyMatch(
                    ExtensionArtifactRecord::enabledForNewRuns);
            return new AgentExtension(entry.getKey(), versions, hashes, enabled);
        }).toList();
    }
}
