package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.util.AtomicFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Owns the Deliverance plugin data layout and manifest recovery. */
final class ManagedInferenceStorage {

    private static final Logger log = LoggerFactory.getLogger(ManagedInferenceStorage.class);

    private final Supplier<Path> pluginDataDirectory;
    private final InferenceCatalogPort catalog;
    private final ObjectMapper json;
    private volatile Layout layout;
    private volatile boolean prepared;

    ManagedInferenceStorage(
            Supplier<Path> pluginDataDirectory,
            InferenceCatalogPort catalog, ObjectMapper json) {
        this.pluginDataDirectory = Objects.requireNonNull(
                pluginDataDirectory, "pluginDataDirectory");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.json = Objects.requireNonNull(json, "json");
    }

    Layout layout() {
        Layout current = layout;
        Path data = Objects.requireNonNull(pluginDataDirectory.get(),
                        "Deliverance 插件 data 目录尚不可用")
                .toAbsolutePath().normalize();
        if (current != null) {
            if (!current.dataRoot().equals(data)) {
                throw new IllegalStateException("Deliverance 插件 data 目录在运行期间发生变化");
            }
            return current;
        }
        synchronized (this) {
            current = layout;
            if (current == null) {
                Path models = data.resolve("models").toAbsolutePath().normalize();
                ManagedInferenceFiles.requireUnder(data, models);
                current = new Layout(data, models,
                        models.resolve("assets").resolve("sha256")
                                .toAbsolutePath().normalize(),
                        models.resolve("downloads").toAbsolutePath().normalize(),
                        models.resolve("staging").toAbsolutePath().normalize(),
                        models.resolve("metadata").toAbsolutePath().normalize());
                layout = current;
            }
            return current;
        }
    }

    void ensurePrepared() throws Exception {
        if (prepared) return;
        synchronized (this) {
            if (prepared) return;
            Layout current = layout();
            preparePlainDirectory(current.dataRoot());
            preparePlainDirectory(current.modelsRoot());
            preparePlainDirectory(current.assetsRoot());
            preparePlainDirectory(current.downloadsRoot());
            preparePlainDirectory(current.stagingRoot());
            preparePlainDirectory(current.metadataRoot());
            reconcileMetadata(current);
            prepared = true;
        }
    }

    Path createStage(String prefix) throws IOException {
        try {
            ensurePrepared();
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("无法准备 Deliverance 模型目录", failure);
        }
        Path stagingRoot = layout().stagingRoot();
        Files.createDirectories(stagingRoot);
        return Files.createTempDirectory(stagingRoot, prefix);
    }

    Path assetPath(String hash) {
        Path assetsRoot = layout().assetsRoot();
        Path path = assetsRoot.resolve(hash.substring(0, 2)).resolve(hash)
                .toAbsolutePath().normalize();
        ManagedInferenceFiles.requireUnder(assetsRoot, path);
        return path;
    }

    Path managedAssetPath(String hash, Path recorded) throws IOException {
        Path current = assetPath(hash);
        if (recorded.equals(current)) return current;
        throw new IOException("模型资产不在 Deliverance 管理目录中");
    }

    void writeMetadata(InferenceModelAsset asset) throws IOException {
        Layout current = layout();
        Path path = metadataPath(asset.id());
        ManagedInferenceFiles.requireUnder(current.metadataRoot(), path);
        AtomicFileWriter.writeJson(json.writerWithDefaultPrettyPrinter(), path.toFile(),
                new AssetManifest(1, withLocation(asset, assetPath(asset.contentSha256()))));
    }

    void deleteMetadata(UUID assetId) throws IOException {
        Files.deleteIfExists(metadataPath(assetId));
    }

    private static void preparePlainDirectory(Path value) throws IOException {
        if (!Files.exists(value, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(value);
        ManagedInferenceFiles.requirePlainDirectory(value);
    }

    private void reconcileMetadata(Layout current) throws IOException {
        try (var entries = Files.list(current.metadataRoot())) {
            for (Path file : entries.filter(
                    path -> path.getFileName().toString().endsWith(".json")).toList()) {
                reconcileMetadataFile(file);
            }
        }
        for (InferenceModelAsset asset : catalog.assets()) reconcileCatalogAsset(current, asset);
    }

    private void reconcileMetadataFile(Path file) {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("忽略不安全的模型资产清单: {}", file);
            return;
        }
        InferenceModelAsset recovered = null;
        try {
            AssetManifest manifest = json.readValue(file.toFile(), AssetManifest.class);
            if (manifest.schemaVersion() != 1 || manifest.asset() == null) {
                throw new IOException("资产清单版本无效");
            }
            recovered = withLocation(
                    manifest.asset(), assetPath(manifest.asset().contentSha256()));
            Path directory = Path.of(recovered.location());
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(directory)) {
                catalog.saveAsset(copyState(recovered, InferenceModelAsset.State.FAILED,
                        "插件 data 中的模型目录缺失"));
                return;
            }
            ManagedInferenceFiles.requireManifestMetadata(recovered.files(),
                    ManagedInferenceFiles.metadataSnapshot(directory, () -> false).files());
            recoverCatalogRecord(recovered, directory);
        } catch (Exception failure) {
            if (recovered != null) {
                catalog.saveAsset(copyState(recovered, InferenceModelAsset.State.FAILED,
                        failure.getMessage() == null ? "模型资产清单不完整" : failure.getMessage()));
            }
            log.warn("恢复模型资产清单失败 {}: {}", file, failure.getMessage());
        }
    }

    private void recoverCatalogRecord(InferenceModelAsset recovered, Path directory) {
        InferenceModelAsset existing = catalog.asset(recovered.id()).orElse(null);
        if (existing == null) {
            catalog.saveAsset(recovered);
        } else if (!Path.of(existing.location()).toAbsolutePath().normalize().equals(directory)) {
            catalog.saveAsset(withLocation(existing, directory));
        } else if (existing.state() == InferenceModelAsset.State.FAILED
                && recovered.state() == InferenceModelAsset.State.READY) {
            catalog.saveAsset(recovered);
        }
    }

    private void reconcileCatalogAsset(Layout current, InferenceModelAsset asset) {
        Path location;
        try {
            location = Path.of(asset.location()).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            catalog.saveAsset(copyState(
                    asset, InferenceModelAsset.State.FAILED, "模型资产路径无效"));
            return;
        }
        if (!location.startsWith(current.assetsRoot())) return;
        if (!Files.isDirectory(location, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(location)) {
            catalog.saveAsset(copyState(asset, InferenceModelAsset.State.FAILED,
                    "插件 data 中的模型目录缺失"));
            return;
        }
        try {
            ManagedInferenceFiles.requireManifestMetadata(asset.files(),
                    ManagedInferenceFiles.metadataSnapshot(location, () -> false).files());
            writeMetadata(asset);
        } catch (Exception invalid) {
            catalog.saveAsset(copyState(asset, InferenceModelAsset.State.FAILED,
                    invalid.getMessage() == null ? "模型资产清单不完整" : invalid.getMessage()));
        }
    }

    private Path metadataPath(UUID assetId) {
        Path root = layout().metadataRoot();
        Path value = root.resolve(assetId + ".json").toAbsolutePath().normalize();
        ManagedInferenceFiles.requireUnder(root, value);
        return value;
    }

    private static InferenceModelAsset withLocation(
            InferenceModelAsset asset, Path location) {
        return new InferenceModelAsset(asset.id(), asset.source(), asset.displayName(),
                asset.modelType(), asset.contentSha256(),
                location.toAbsolutePath().normalize().toString(), asset.huggingFaceRepository(),
                asset.huggingFaceCommit(), asset.files(), asset.sizeBytes(), asset.state(),
                asset.failure(), asset.createdAt(), asset.artifactMetadata());
    }

    private static InferenceModelAsset copyState(
            InferenceModelAsset source, InferenceModelAsset.State state, String failure) {
        return new InferenceModelAsset(source.id(), source.source(), source.displayName(),
                source.modelType(), source.contentSha256(), source.location(),
                source.huggingFaceRepository(), source.huggingFaceCommit(), source.files(),
                source.sizeBytes(), state, failure, source.createdAt(), source.artifactMetadata());
    }

    record Layout(Path dataRoot, Path modelsRoot, Path assetsRoot,
                  Path downloadsRoot, Path stagingRoot, Path metadataRoot) { }

    private record AssetManifest(int schemaVersion, InferenceModelAsset asset) { }
}
