package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.util.AtomicFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Owns the Deliverance plugin data layout, legacy migration, and manifest recovery. */
final class ManagedInferenceStorage {

    private static final Logger log = LoggerFactory.getLogger(ManagedInferenceStorage.class);

    private final Path legacyInferenceRoot;
    private final Supplier<Path> pluginDataDirectory;
    private final InferenceCatalogPort catalog;
    private final ObjectMapper json;
    private volatile Layout layout;
    private volatile boolean prepared;

    ManagedInferenceStorage(
            DataRoot dataRoot, Supplier<Path> pluginDataDirectory,
            InferenceCatalogPort catalog, ObjectMapper json) {
        legacyInferenceRoot = Objects.requireNonNull(dataRoot, "dataRoot").path()
                .resolve("inference").toAbsolutePath().normalize();
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
            migrateLegacyAssets(current);
            migrateLegacyDownloads(current);
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
        Path legacy = legacyAssetPath(hash);
        if (recorded.equals(legacy)) return legacy;
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

    private void migrateLegacyAssets(Layout current) {
        Path legacyRoot = legacyInferenceRoot.resolve("assets").resolve("sha256")
                .toAbsolutePath().normalize();
        if (legacyRoot.equals(current.assetsRoot())
                || !Files.isDirectory(legacyRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(legacyRoot)) return;
        for (InferenceModelAsset asset : catalog.assets()) {
            Path recorded;
            try {
                recorded = Path.of(asset.location()).toAbsolutePath().normalize();
            } catch (RuntimeException invalid) {
                continue;
            }
            if (!recorded.startsWith(legacyRoot)) continue;
            migrateLegacyAsset(current, asset, recorded);
        }
        ManagedInferenceFiles.deleteEmptyDirectories(legacyRoot);
    }

    private void migrateLegacyAsset(
            Layout current, InferenceModelAsset asset, Path recorded) {
        boolean moved = false;
        boolean createdTarget = false;
        Path target = null;
        try {
            Path expectedLegacy = legacyAssetPath(asset.contentSha256());
            if (!recorded.equals(expectedLegacy)) {
                throw new IOException("旧模型位置与内容摘要不匹配");
            }
            if (!Files.isDirectory(recorded, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(recorded)) {
                throw new IOException("旧模型目录不存在或不安全");
            }
            target = assetPath(asset.contentSha256());
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                ManagedInferenceFiles.verifyCachedContent(
                        recorded, asset.files(), asset.contentSha256(), () -> false);
                Files.createDirectories(target.getParent());
                if (ManagedInferenceFiles.sameFileStore(recorded, target.getParent())) {
                    try {
                        Files.move(recorded, target, StandardCopyOption.ATOMIC_MOVE);
                        moved = true;
                        createdTarget = true;
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        copyLegacyAsset(recorded, target, asset, current);
                        createdTarget = true;
                    }
                } else {
                    copyLegacyAsset(recorded, target, asset, current);
                    createdTarget = true;
                }
            } else {
                ManagedInferenceFiles.verifyCachedContent(
                        target, asset.files(), asset.contentSha256(), () -> false);
            }
            InferenceModelAsset migrated = withLocation(asset, target);
            writeMetadata(migrated);
            catalog.saveAsset(migrated);
            if (!moved) cleanupLegacyCopy(recorded);
            log.info("已迁移 Deliverance 模型资产到插件 data: {}", asset.displayName());
        } catch (Exception failure) {
            rollbackLegacyAsset(asset, recorded, target, moved, createdTarget, failure);
            log.warn("迁移 Deliverance 模型资产失败，保留旧副本 {}: {}",
                    asset.displayName(), failure.getMessage());
        }
    }

    private void cleanupLegacyCopy(Path recorded) {
        try {
            ManagedInferenceFiles.deleteTree(recorded);
        } catch (IOException cleanupFailure) {
            log.warn("模型已迁移但旧副本暂未清理 {}: {}",
                    recorded, cleanupFailure.getMessage());
        }
    }

    private void rollbackLegacyAsset(
            InferenceModelAsset asset, Path recorded, Path target,
            boolean moved, boolean createdTarget, Exception failure) {
        try {
            if (moved && target != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(recorded, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(recorded.getParent());
                Files.move(target, recorded, StandardCopyOption.ATOMIC_MOVE);
                deleteMetadata(asset.id());
            } else if (createdTarget && target != null
                    && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                ManagedInferenceFiles.deleteTree(target);
                deleteMetadata(asset.id());
            }
        } catch (Exception rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void migrateLegacyDownloads(Layout current) {
        Path legacy = legacyInferenceRoot.resolve("downloads").toAbsolutePath().normalize();
        if (legacy.equals(current.downloadsRoot())
                || !Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(legacy)) return;
        try (var entries = Files.list(legacy)) {
            for (Path source : entries.toList()) {
                if (Files.isSymbolicLink(source)) {
                    log.warn("忽略包含符号链接的旧模型下载: {}", source);
                    continue;
                }
                Path target = current.downloadsRoot().resolve(source.getFileName())
                        .toAbsolutePath().normalize();
                ManagedInferenceFiles.requireUnder(current.downloadsRoot(), target);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    moveOrCopyLegacyDownload(source, target, current);
                } catch (Exception failure) {
                    log.warn("迁移未完成的模型下载失败，保留原文件 {}: {}",
                            source, failure.getMessage());
                }
            }
        } catch (IOException failure) {
            log.warn("扫描旧模型下载目录失败，保留原目录: {}", failure.getMessage());
        }
        ManagedInferenceFiles.deleteEmptyDirectories(legacy);
    }

    private void copyLegacyAsset(
            Path source, Path target, InferenceModelAsset asset, Layout current) throws Exception {
        Path stage = Files.createTempDirectory(current.stagingRoot(), "legacy-");
        try {
            ManagedInferenceFiles.copyDirectory(source, stage);
            ManagedInferenceFiles.verifyCachedContent(
                    stage, asset.files(), asset.contentSha256(), () -> false);
            ManagedInferenceFiles.atomicMove(stage, target);
        } finally {
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                ManagedInferenceFiles.deleteTree(stage);
            }
        }
    }

    private void moveOrCopyLegacyDownload(Path source, Path target, Layout current)
            throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException unsupported) {
            try {
                Files.move(source, target);
                return;
            } catch (IOException crossFileSystem) {
                log.debug("旧下载无法直接移动，将复制并校验: {}", crossFileSystem.getMessage());
            }
        }
        Path stage = current.stagingRoot().resolve("download-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        ManagedInferenceFiles.requireUnder(current.stagingRoot(), stage);
        try {
            copyAndVerifyLegacyDownload(source, stage);
            ManagedInferenceFiles.atomicMove(stage, target);
            ManagedInferenceFiles.deleteTree(source);
        } finally {
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                ManagedInferenceFiles.deleteTree(stage);
            }
        }
    }

    private static void copyAndVerifyLegacyDownload(Path source, Path stage) throws Exception {
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            var expected = ManagedInferenceFiles.inspect(source, () -> false);
            ManagedInferenceFiles.copyDirectory(source, stage);
            var actual = ManagedInferenceFiles.inspect(stage, () -> false);
            if (!ManagedInferenceFiles.sameSourceContent(expected, actual)) {
                throw new IOException("跨盘迁移下载目录校验失败");
            }
        } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(source, stage, StandardCopyOption.COPY_ATTRIBUTES);
            if (Files.size(source) != Files.size(stage)
                    || !ManagedInferenceFiles.sha256(source, () -> false)
                    .equals(ManagedInferenceFiles.sha256(stage, () -> false))) {
                throw new IOException("跨盘迁移下载文件校验失败");
            }
        } else {
            throw new IOException("旧模型下载不是普通文件或目录");
        }
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

    private Path legacyAssetPath(String hash) {
        Path root = legacyInferenceRoot.resolve("assets").resolve("sha256")
                .toAbsolutePath().normalize();
        Path path = root.resolve(hash.substring(0, 2)).resolve(hash)
                .toAbsolutePath().normalize();
        ManagedInferenceFiles.requireUnder(root, path);
        return path;
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
