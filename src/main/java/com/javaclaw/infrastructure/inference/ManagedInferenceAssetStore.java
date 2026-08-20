package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceAssetPreparationPort;
import com.javaclaw.application.inference.InferenceAssetIntegrityPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceModelMetadataPort;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.util.ProcessTerminator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.atomicMove;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.AssetSnapshot;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.createDirectoryTree;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.contentHash;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.deleteTree;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.digest;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.inspect;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.metadataSnapshot;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.rejectUnsafeExistingFile;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.requireManifestMetadata;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.requireNotCancelled;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.requireUnder;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.safeResolve;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.sameContent;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.sha256;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.SourceFile;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.updateDigest;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.validateModelFileSet;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.validateRelative;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.validateRoot;
import static com.javaclaw.infrastructure.inference.ManagedInferenceFiles.verifyCachedContent;

/** 模型资产的内容寻址文件存储与 Hugging Face 下载器。 */
public final class ManagedInferenceAssetStore
        implements InferenceAssetPreparationPort, InferenceAssetIntegrityPort,
        InferenceModelMetadataPort {

    private static final Logger log = LoggerFactory.getLogger(ManagedInferenceAssetStore.class);
    private static final Set<String> HF_METADATA_FILES = Set.of(
            "config.json", "generation_config.json", "tokenizer.json", "tokenizer_config.json",
            "special_tokens_map.json", "added_tokens.json", "preprocessor_config.json",
            "sentence_bert_config.json", "modules.json");
    private static final long MAX_METADATA_BYTES = 64L * 1024 * 1024;
    private static final long MAX_CONFIG_BYTES = 4L * 1024 * 1024;
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final InferenceCatalogPort catalog;
    private final HttpClient http;
    private final ObjectMapper json;
    private final InferenceModelMetadataReader metadataReader;
    private final URI huggingFaceBase;
    private final ManagedInferenceStorage managedStorage;
    private final Map<UUID, ManagedInferenceFiles.AssetSnapshot> verifiedThisLifecycle =
            new ConcurrentHashMap<>();

    public ManagedInferenceAssetStore(
            DataRoot dataRoot, InferenceCatalogPort catalog, HttpClient http, ObjectMapper json) {
        this(dataRoot, defaultPluginDataDirectory(dataRoot), catalog, http, json,
                URI.create("https://huggingface.co/"));
    }

    public ManagedInferenceAssetStore(
            DataRoot dataRoot, Supplier<Path> pluginDataDirectory,
            InferenceCatalogPort catalog, HttpClient http, ObjectMapper json) {
        this(dataRoot, pluginDataDirectory, catalog, http, json,
                URI.create("https://huggingface.co/"));
    }

    ManagedInferenceAssetStore(
            DataRoot dataRoot, InferenceCatalogPort catalog, HttpClient http,
            ObjectMapper json, URI huggingFaceBase) {
        this(dataRoot, defaultPluginDataDirectory(dataRoot), catalog, http, json, huggingFaceBase);
    }

    ManagedInferenceAssetStore(
            DataRoot dataRoot, Supplier<Path> pluginDataDirectory,
            InferenceCatalogPort catalog, HttpClient http,
            ObjectMapper json, URI huggingFaceBase) {
        DataRoot root = java.util.Objects.requireNonNull(dataRoot, "dataRoot");
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.http = java.util.Objects.requireNonNull(http, "http");
        this.json = java.util.Objects.requireNonNull(json, "json");
        metadataReader = new InferenceModelMetadataReader(json);
        URI supplied = java.util.Objects.requireNonNull(huggingFaceBase, "huggingFaceBase");
        String normalized = supplied.toString().endsWith("/")
                ? supplied.toString() : supplied + "/";
        this.huggingFaceBase = URI.create(normalized);
        managedStorage = new ManagedInferenceStorage(root, pluginDataDirectory, catalog, json);
    }

    private static Supplier<Path> defaultPluginDataDirectory(DataRoot dataRoot) {
        Path value = java.util.Objects.requireNonNull(dataRoot, "dataRoot").path()
                .resolveSibling("plugins").resolve("builtin-deliverance").resolve("data")
                .toAbsolutePath().normalize();
        return () -> value;
    }

    @Override
    public void reconcileManagedAssets() throws Exception {
        ensureStoragePrepared();
    }

    @Override
    public HuggingFacePreview previewHuggingFace(
            HuggingFaceRequest request, BooleanSupplier cancelled) throws Exception {
        HuggingFaceRevision revision = resolveRevision(request, cancelled);
        long size = revision.files().stream().mapToLong(HuggingFaceFile::size)
                .filter(value -> value > 0).sum();
        return new HuggingFacePreview(request.repository(), revision.commit(), size,
                revision.license(), revision.gated(), revision.files().size(), revision.modelType());
    }

    @Override
    public InferenceModelAsset importLocalDirectory(
            Path source, Consumer<Progress> progress, BooleanSupplier cancelled) throws Exception {
        ensureStoragePrepared();
        requireNotCancelled(cancelled);
        Path realSource = validateRoot(source);
        ModelMetadata metadata = metadataReader.inspect(realSource, cancelled);
        List<SourceFile> files = inspect(realSource, cancelled);
        validateModelFileSet(files);
        long total = files.stream().mapToLong(SourceFile::size).sum();
        Path stage = createStage("local-");
        try {
            long copied = 0;
            for (SourceFile file : files) {
                requireNotCancelled(cancelled);
                Path target = safeResolve(stage, file.relative());
                Files.createDirectories(target.getParent());
                materialize(file.source(), target);
                String copiedHash = sha256(target, cancelled);
                if (!copiedHash.equals(file.sha256()) || Files.size(target) != file.size()) {
                    throw new IOException("导入期间源文件发生变化: " + file.relative());
                }
                copied += file.size();
                progress.accept(new Progress("复制本地模型", file.relative(), copied, total));
            }
            return promote(stage, files, InferenceModelAsset.Source.LOCAL_DIRECTORY,
                    displayName(realSource), metadata.modelType(), "", "", cancelled);
        } catch (Exception failure) {
            deleteTree(stage);
            throw failure;
        }
    }

    @Override
    public InferenceModelAsset downloadHuggingFace(
            HuggingFaceRequest request, Consumer<Progress> progress,
            BooleanSupplier cancelled) throws Exception {
        ensureStoragePrepared();
        requireNotCancelled(cancelled);
        HuggingFaceRevision revision = resolveRevision(request, cancelled);
        if (revision.gated() && request.token().isBlank()) {
            throw new IOException("该 Hugging Face 模型受许可门控，需要 Token 并先在网站接受许可");
        }
        String bucket = request.repository().replace('/', '_') + "-" + revision.commit();
        Path downloadsRoot = storage().downloadsRoot();
        Path download = downloadsRoot.resolve(bucket).toAbsolutePath().normalize();
        requireUnder(downloadsRoot, download);
        createDirectoryTree(downloadsRoot, download);

        long total = revision.files().stream().mapToLong(HuggingFaceFile::size).filter(v -> v > 0).sum();
        long completed = 0;
        for (HuggingFaceFile file : revision.files()) {
            requireNotCancelled(cancelled);
            Path target = safeResolve(download, file.path());
            createDirectoryTree(download, target.getParent());
            downloadFile(request, revision.commit(), file, target, cancelled);
            String actualHash = sha256(target, cancelled);
            if (!file.sha256().isBlank() && !file.sha256().equalsIgnoreCase(actualHash)) {
                throw new IOException("Hugging Face 文件校验失败: " + file.path());
            }
            completed += Files.size(target);
            progress.accept(new Progress("下载 Hugging Face 模型", file.path(), completed,
                    Math.max(total, completed)));
        }

        List<SourceFile> local = inspect(download, cancelled);
        validateModelFileSet(local);
        ModelMetadata metadata = metadataReader.inspect(download, cancelled);
        Path stage = createStage("hf-");
        try {
            for (SourceFile file : local) {
                Path target = safeResolve(stage, file.relative());
                Files.createDirectories(target.getParent());
                materialize(file.source(), target);
                if (Files.size(target) != file.size()
                        || !sha256(target, cancelled).equals(file.sha256())) {
                    throw new IOException("准备期间下载缓存发生变化: " + file.relative());
                }
            }
            InferenceModelAsset asset = promote(stage, local,
                    InferenceModelAsset.Source.HUGGING_FACE, repositoryName(request.repository()),
                    metadata.modelType(),
                    request.repository(),
                    revision.commit(), cancelled);
            deleteTree(download);
            return asset;
        } catch (Exception failure) {
            deleteTree(stage);
            throw failure;
        }
    }

    @Override
    public ModelMetadata inspectModel(Path modelDirectory, BooleanSupplier cancelled) throws Exception {
        return metadataReader.inspect(modelDirectory, cancelled);
    }

    @Override
    public void deleteManagedAsset(InferenceModelAsset asset) throws Exception {
        ensureStoragePrepared();
        Path target = Path.of(asset.location()).toAbsolutePath().normalize();
        if (!target.equals(managedAssetPath(asset.contentSha256(), target))) {
            throw new IOException("拒绝删除不匹配内容摘要的模型目录");
        }
        deleteTree(target);
        managedStorage.deleteMetadata(asset.id());
        verifiedThisLifecycle.remove(asset.id());
    }

    @Override
    public void verifyForColdStart(InferenceModelAsset asset, BooleanSupplier cancelled) throws Exception {
        ensureStoragePrepared();
        BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
        try {
            Path target = Path.of(asset.location()).toAbsolutePath().normalize();
            if (!target.equals(managedAssetPath(asset.contentSha256(), target))) {
                throw new IOException("内容寻址模型位置与摘要不匹配");
            }
            AssetSnapshot current = metadataSnapshot(target, cancellation);
            requireManifestMetadata(asset.files(), current.files());
            AssetSnapshot cached = verifiedThisLifecycle.get(asset.id());
            if (current.equals(cached)) return;

            List<SourceFile> actual = inspect(target, cancellation);
            if (!sameContent(asset.files(), actual)
                    || !asset.contentSha256().equals(contentHash(actual))) {
                throw new IOException("内容寻址模型资产完整性校验失败: " + asset.contentSha256());
            }
            verifiedThisLifecycle.put(asset.id(), current);
        } catch (Exception failure) {
            verifiedThisLifecycle.remove(asset.id());
            if (isCancellation(failure)) {
                Thread.currentThread().interrupt();
                throw failure;
            }
            catalog.saveAsset(copyState(asset, InferenceModelAsset.State.FAILED,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            throw failure;
        }
    }

    private static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException
                    || current instanceof CancellationException) return true;
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    private HuggingFaceRevision resolveRevision(
            HuggingFaceRequest request, BooleanSupplier cancelled) throws Exception {
        requireNotCancelled(cancelled);
        String repository = encodePath(request.repository());
        String revision = URLEncoder.encode(request.revision(), StandardCharsets.UTF_8);
        URI uri = huggingFaceBase.resolve(
                "api/models/" + repository + "/revision/" + revision + "?blobs=true");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET();
        authorize(builder, request.token());
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("Hugging Face 模型需要有效 Token 或尚未接受许可");
        }
        if (response.statusCode() != 200) {
            throw new IOException("无法解析 Hugging Face 模型版本，HTTP " + response.statusCode());
        }
        JsonNode root = json.readTree(response.body());
        String commit = root.path("sha").asText("");
        if (!commit.matches("[0-9a-fA-F]{40,64}")) {
            throw new IOException("Hugging Face 未返回固定 commit");
        }
        boolean gated = root.path("gated").isTextual()
                ? !"false".equalsIgnoreCase(root.path("gated").asText())
                : root.path("gated").asBoolean(false);
        String license = root.path("cardData").path("license").asText(
                root.path("license").asText("未声明"));

        List<HuggingFaceFile> files = new ArrayList<>();
        for (JsonNode sibling : root.path("siblings")) {
            String path = sibling.path("rfilename").asText("");
            if (!shouldDownload(path)) continue;
            long size = sibling.path("size").asLong(
                    sibling.path("lfs").path("size").asLong(-1));
            String sha = sibling.path("lfs").path("sha256").asText("");
            if (!sha.matches("[0-9a-fA-F]{64}")) sha = "";
            if (!path.endsWith(".safetensors") && size > MAX_METADATA_BYTES) {
                throw new IOException("模型元数据文件异常过大: " + path);
            }
            validateRelative(path);
            files.add(new HuggingFaceFile(path, size, sha.toLowerCase(Locale.ROOT)));
        }
        if (files.isEmpty()) throw new IOException("Hugging Face 仓库没有可用模型文件");
        files.sort(Comparator.comparing(HuggingFaceFile::path));
        String modelType = root.path("config").path("model_type").asText("").strip();
        if (modelType.isBlank()) {
            modelType = remoteModelMetadata(request, commit, cancelled).modelType();
        }
        return new HuggingFaceRevision(commit.toLowerCase(Locale.ROOT), List.copyOf(files),
                license, gated, new ModelMetadata(modelType, List.of()).modelType());
    }

    private ModelMetadata remoteModelMetadata(
            HuggingFaceRequest request, String commit, BooleanSupplier cancelled) throws Exception {
        requireNotCancelled(cancelled);
        URI uri = huggingFaceBase.resolve(encodePath(request.repository()) + "/resolve/"
                + commit + "/config.json?download=true");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET();
        authorize(builder, request.token());
        HttpResponse<InputStream> response = http.send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            response.body().close();
            throw new IOException("Hugging Face config.json 需要有效 Token");
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("无法读取 Hugging Face config.json，HTTP " + response.statusCode());
        }
        byte[] bytes;
        try (InputStream input = response.body()) {
            bytes = input.readNBytes((int) MAX_CONFIG_BYTES + 1);
        }
        if (bytes.length > MAX_CONFIG_BYTES) throw new IOException("Hugging Face config.json 超过 4 MiB");
        JsonNode document = json.readTree(bytes);
        String modelType = document == null ? "" : document.path("model_type").asText("");
        List<String> architectures = new ArrayList<>();
        if (document != null && document.path("architectures").isArray()) {
            document.path("architectures").forEach(value -> {
                if (value.isTextual()) architectures.add(value.asText());
            });
        }
        return new ModelMetadata(modelType, architectures);
    }

    private void downloadFile(
            HuggingFaceRequest request, String commit, HuggingFaceFile file, Path target,
            BooleanSupplier cancelled) throws Exception {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        rejectUnsafeExistingFile(target);
        rejectUnsafeExistingFile(part);
        long offset = Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) ? Files.size(part) : 0;
        if (file.size() >= 0 && offset > file.size()) {
            Files.delete(part);
            offset = 0;
        }
        URI uri = huggingFaceBase.resolve(encodePath(request.repository())
                + "/resolve/" + commit + "/" + encodePath(file.path()) + "?download=true");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofHours(12)).GET();
        authorize(builder, request.token());
        if (offset > 0) builder.header("Range", "bytes=" + offset + "-");
        HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("Hugging Face 下载被拒绝: " + file.path());
        }
        boolean append = offset > 0 && response.statusCode() == 206;
        if (response.statusCode() != 200 && response.statusCode() != 206) {
            response.body().close();
            throw new IOException("Hugging Face 下载失败 " + response.statusCode() + ": " + file.path());
        }
        if (!append) offset = 0;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(part,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                     LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            long downloaded = offset;
            while ((read = in.read(buffer)) >= 0) {
                requireNotCancelled(cancelled);
                if (read > 0) {
                    downloaded = Math.addExact(downloaded, read);
                    if (!isWeight(file.path()) && downloaded > MAX_METADATA_BYTES) {
                        throw new IOException("模型元数据文件超过 64 MiB: " + file.path());
                    }
                    if (file.size() >= 0 && downloaded > file.size()) {
                        throw new IOException("Hugging Face 文件超过声明长度: " + file.path());
                    }
                    out.write(buffer, 0, read);
                }
            }
        }
        long actualSize = Files.size(part);
        if (file.size() >= 0 && actualSize != file.size()) {
            throw new IOException("Hugging Face 文件长度不符: " + file.path());
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private InferenceModelAsset promote(
            Path stage, List<SourceFile> files, InferenceModelAsset.Source source,
            String displayName, String modelType, String repository, String commit,
            BooleanSupplier cancelled) throws Exception {
        requireNotCancelled(cancelled);
        List<InferenceModelAsset.AssetFile> manifest = new ArrayList<>();
        MessageDigest digest = digest();
        long total = 0;
        for (SourceFile original : files.stream().sorted(Comparator.comparing(SourceFile::relative)).toList()) {
            Path copied = safeResolve(stage, original.relative());
            long size = Files.size(copied);
            String sha = sha256(copied, cancelled);
            updateDigest(digest, original.relative(), size, sha);
            manifest.add(new InferenceModelAsset.AssetFile(original.relative(), size, sha));
            total = Math.addExact(total, size);
        }
        String contentHash = HexFormat.of().formatHex(digest.digest());
        InferenceModelAsset.ArtifactMetadata artifactMetadata = artifactMetadata(stage, total);
        Path target = assetPath(contentHash);
        var existing = catalog.assetByHash(contentHash);
        if (existing.isPresent()) {
            Path recorded = Path.of(existing.get().location()).toAbsolutePath().normalize();
            if (!recorded.equals(target)) {
                throw new IOException("内容寻址模型记录位置与摘要不匹配: " + contentHash);
            }
            Files.createDirectories(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                verifyOrRepairCachedContent(stage, target, manifest, contentHash, cancelled);
            } else {
                try {
                    atomicMove(stage, target);
                } catch (FileAlreadyExistsException concurrentRepair) {
                    verifyOrRepairCachedContent(stage, target, manifest, contentHash, cancelled);
                }
            }
            InferenceModelAsset repaired = existing.get().state() == InferenceModelAsset.State.READY
                    && existing.get().displayName().equals(displayName)
                    && existing.get().modelType().equals(modelType)
                    && existing.get().artifactMetadata().equals(artifactMetadata)
                    ? existing.get() : copyState(existing.get(), displayName,
                    modelType, artifactMetadata, InferenceModelAsset.State.READY, "");
            if (repaired != existing.get()) catalog.saveAsset(repaired);
            writeMetadata(repaired);
            verifiedThisLifecycle.remove(repaired.id());
            return repaired;
        }
        Files.createDirectories(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyOrRepairCachedContent(stage, target, manifest, contentHash, cancelled);
        } else {
            try {
                atomicMove(stage, target);
            } catch (FileAlreadyExistsException concurrentPromotion) {
                verifyOrRepairCachedContent(stage, target, manifest, contentHash, cancelled);
            }
        }
        UUID assetId = UUID.nameUUIDFromBytes(
                ("sha256:" + contentHash).getBytes(StandardCharsets.UTF_8));
        InferenceModelAsset created = new InferenceModelAsset(assetId, source, displayName, modelType,
                contentHash, target.toString(),
                repository, commit, manifest, total, InferenceModelAsset.State.READY, "", Instant.now(),
                artifactMetadata);
        writeMetadata(created);
        return created;
    }

    private InferenceModelAsset.ArtifactMetadata artifactMetadata(Path root, long quantizedSize) {
        Path manifest = root.resolve(HuggingFaceModelCatalogClient.QUANTIZATION_MANIFEST)
                .toAbsolutePath().normalize();
        if (!manifest.startsWith(root.toAbsolutePath().normalize())
                || Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return InferenceModelAsset.ArtifactMetadata.unknown(quantizedSize);
        }
        try {
            if (Files.size(manifest) <= 0 || Files.size(manifest) > MAX_CONFIG_BYTES) {
                return InferenceModelAsset.ArtifactMetadata.unknown(quantizedSize);
            }
            JsonNode value = json.readTree(manifest.toFile());
            if (value == null || !value.isObject() || value.path("schemaVersion").asInt(-1) != 1) {
                return InferenceModelAsset.ArtifactMetadata.unknown(quantizedSize);
            }
            String target = value.path("targetType").asText("").strip().toUpperCase(Locale.ROOT);
            if (!target.equals("Q4") && !target.equals("I8")) {
                return InferenceModelAsset.ArtifactMetadata.unknown(quantizedSize);
            }
            long sourceSize = value.path("sourceSizeBytes").asLong(0);
            return new InferenceModelAsset.ArtifactMetadata("SAFETENSORS", target,
                    Math.max(0, sourceSize), quantizedSize, Instant.EPOCH);
        } catch (IOException invalid) {
            log.debug("忽略无效 Deliverance 量化清单 {}: {}", manifest, invalid.getMessage());
            return InferenceModelAsset.ArtifactMetadata.unknown(quantizedSize);
        }
    }

    private static InferenceModelAsset copyState(
            InferenceModelAsset source, InferenceModelAsset.State state, String failure) {
        return copyState(source, source.displayName(), source.modelType(), state, failure);
    }

    private static InferenceModelAsset copyState(
            InferenceModelAsset source, String displayName,
            String modelType,
            InferenceModelAsset.State state, String failure) {
        return copyState(source, displayName, modelType, source.artifactMetadata(), state, failure);
    }

    private static InferenceModelAsset copyState(
            InferenceModelAsset source, String displayName, String modelType,
            InferenceModelAsset.ArtifactMetadata artifactMetadata,
            InferenceModelAsset.State state, String failure) {
        return new InferenceModelAsset(source.id(), source.source(), displayName, modelType,
                source.contentSha256(),
                source.location(), source.huggingFaceRepository(), source.huggingFaceCommit(),
                source.files(), source.sizeBytes(), state, failure, source.createdAt(), artifactMetadata);
    }

    private static String displayName(Path source) {
        Path name = source.getFileName();
        return name == null || name.toString().isBlank() ? "本地模型" : name.toString();
    }

    private static String repositoryName(String repository) {
        int slash = repository.lastIndexOf('/');
        return slash >= 0 && slash + 1 < repository.length()
                ? repository.substring(slash + 1) : repository;
    }

    private void verifyOrRepairCachedContent(
            Path stage, Path target, List<InferenceModelAsset.AssetFile> manifest,
            String contentHash, BooleanSupplier cancelled) throws Exception {
        try {
            verifyCachedContent(target, manifest, contentHash, cancelled);
            deleteTree(stage);
            return;
        } catch (Exception corrupt) {
            requireNotCancelled(cancelled);
            if (!(corrupt instanceof IOException)) throw corrupt;
            Path backup = target.resolveSibling(target.getFileName() + ".repair-" + UUID.randomUUID());
            boolean oldMoved = false;
            try {
                atomicMove(target, backup);
                oldMoved = true;
                atomicMove(stage, target);
                verifyCachedContent(target, manifest, contentHash, cancelled);
                deleteTree(backup);
            } catch (Exception repairFailure) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) deleteTree(target);
                if (oldMoved && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                    atomicMove(backup, target);
                }
                repairFailure.addSuppressed(corrupt);
                throw repairFailure;
            }
        }
    }

    private Path createStage(String prefix) throws IOException {
        return managedStorage.createStage(prefix);
    }

    private void materialize(Path source, Path target) throws IOException, InterruptedException {
        if (tryReflink(source, target)) return;
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private boolean tryReflink(Path source, Path target) throws InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (os.contains("mac")) {
            command = List.of("/bin/cp", "-c", source.toString(), target.toString());
        } else if (os.contains("linux")) {
            command = List.of("/bin/cp", "--reflink=always", source.toString(), target.toString());
        } else {
            return false;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                ProcessTerminator.destroyTreeForcibly(process);
                Files.deleteIfExists(target);
                return false;
            }
            int exit = process.exitValue();
            if (exit == 0) return true;
            Files.deleteIfExists(target);
            return false;
        } catch (InterruptedException interrupted) {
            ProcessTerminator.destroyTreeForcibly(process);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            return false;
        }
    }

    private ManagedInferenceStorage.Layout storage() {
        return managedStorage.layout();
    }

    private void ensureStoragePrepared() throws Exception {
        managedStorage.ensurePrepared();
    }

    private Path assetPath(String hash) {
        return managedStorage.assetPath(hash);
    }

    private Path managedAssetPath(String hash, Path recorded) throws IOException {
        return managedStorage.managedAssetPath(hash, recorded);
    }

    private void writeMetadata(InferenceModelAsset asset) throws IOException {
        managedStorage.writeMetadata(asset);
    }

    private static boolean shouldDownload(String path) {
        if (path == null || path.isBlank()) return false;
        String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".safetensors") || name.endsWith(".json") || name.endsWith(".model")
                || name.endsWith(".tiktoken") || name.endsWith(".txt")
                || HF_METADATA_FILES.contains(name) || name.startsWith("tokenizer");
    }

    private static boolean isWeight(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".safetensors");
    }

    private static String encodePath(String path) {
        return String.join("/", java.util.Arrays.stream(path.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .toList());
    }

    private static void authorize(HttpRequest.Builder builder, String token) {
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
    }

    private record HuggingFaceFile(String path, long size, String sha256) { }
    private record HuggingFaceRevision(
            String commit, List<HuggingFaceFile> files, String license, boolean gated,
            String modelType) { }
}
