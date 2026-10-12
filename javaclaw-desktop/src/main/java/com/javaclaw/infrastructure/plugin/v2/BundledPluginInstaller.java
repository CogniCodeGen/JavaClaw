package com.javaclaw.infrastructure.plugin.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.PluginException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 发行清单只导入静态工件；批准、配置与启用仍由工作区用户完成。 */
public final class BundledPluginInstaller {
    private final PluginManagementService plugins;
    private final ObjectMapper json;
    private final Path importStateRoot;

    public BundledPluginInstaller(PluginManagementService plugins, ObjectMapper json) { this(plugins, json, null); }
    public BundledPluginInstaller(PluginManagementService plugins, ObjectMapper json, Path importStateRoot) {
        this.plugins = plugins; this.json = json; this.importStateRoot = importStateRoot;
    }

    public List<ImportResult> importPackages(Path applicationHome, String workspaceId) {
        PluginManifest.identifier(workspaceId);
        Path home = applicationHome.toAbsolutePath().normalize();
        try {
            Path manifest = PluginPaths.managed(home, home.resolve("runtime/plugin-distribution.json"));
            if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return List.of();
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.size(manifest) > 1024 * 1024) throw new IOException("发行插件清单必须为不超过 1 MiB 的普通文件");
            JsonNode root = json.readTree(Files.readAllBytes(manifest));
            JsonNode packages = root.path("packages");
            if (!root.isObject() || !packages.isArray() || packages.size() > 256) throw new IOException("发行插件清单格式无效");
            Path stateRoot = importStateRoot == null ? home.resolve("plugins") : importStateRoot;
            Path ledger = PluginPaths.managed(stateRoot, stateRoot.resolve(".bundled-imports.json"));
            Set<String> imported = readImported(ledger);
            List<ImportResult> results = new ArrayList<>();
            for (JsonNode declaration : packages) {
                String source = declaration.path("path").asText("");
                try {
                    String relative = PluginPaths.relative(source);
                    String hash = declaration.path("sha256").asText("").toLowerCase(java.util.Locale.ROOT);
                    Path archive = PluginPaths.managed(home, home.resolve(relative));
                    if (!hash.matches("[0-9a-f]{64}")) throw new IOException("发行包 SHA256 格式无效");
                    if (imported.contains(hash)) {
                        results.add(new ImportResult(source, "", "SKIPPED_IMPORTED", "发行工件已处理，保留用户卸载或选择的结果"));
                        continue;
                    }
                    if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(archive) > PluginPackageVerifier.MAX_ARCHIVE_BYTES || !PluginPaths.sha256(archive).equals(hash)) throw new IOException("发行包 SHA256 或文件类型校验失败");
                    var preview = plugins.inspectPackage(archive);
                    var existing = plugins.list(workspaceId).stream().filter(value -> value.id().equals(preview.id())).findFirst();
                    if (existing.isPresent()) {
                        String message = existing.get().artifactHash().equals(preview.artifactHash()) ? "相同工件已存在" : "保留用户已安装的插件版本";
                        results.add(new ImportResult(source, preview.id(), "SKIPPED_EXISTING", message));
                        plugins.discardPackagePreview(preview.previewId());
                    } else {
                        plugins.install(workspaceId, preview.previewId());
                        results.add(new ImportResult(source, preview.id(), "INSTALLED_DISABLED", "已静态导入，等待用户配置与启用"));
                    }
                    imported.add(hash);
                    PluginPaths.directory(stateRoot, stateRoot);
                    PluginStateStore.atomicWrite(ledger, json.writeValueAsBytes(imported.stream().sorted().toList()), true);
                } catch (Exception failure) { results.add(new ImportResult(source, "", "FAILED", failure.getMessage() == null ? "发行包导入失败" : failure.getMessage())); }
            }
            return List.copyOf(results);
        } catch (IOException failure) { throw new PluginException("BUNDLED_IMPORT_FAILED", "发行插件清单无法读取", failure); }
    }

    private Set<String> readImported(Path ledger) throws IOException {
        if (!Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) return new HashSet<>();
        if (!Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS) || Files.size(ledger) > 1024 * 1024) throw new IOException("发行导入记录无效");
        JsonNode values = json.readTree(Files.readAllBytes(ledger));
        if (values == null || !values.isArray()) throw new IOException("发行导入记录必须为数组");
        Set<String> hashes = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !value.asText().matches("[0-9a-f]{64}")) throw new IOException("发行导入记录包含无效哈希");
            hashes.add(value.asText());
        }
        return hashes;
    }

    public record ImportResult(String source, String pluginId, String status, String message) { }
}
