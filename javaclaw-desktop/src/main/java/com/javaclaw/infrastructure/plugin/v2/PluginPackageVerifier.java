package com.javaclaw.infrastructure.plugin.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService.InstallProgress;
import com.javaclaw.application.plugin.v2.PluginManagementService.PackageFile;
import com.javaclaw.application.plugin.v2.PluginManagementService.PackagePreview;
import com.javaclaw.application.plugin.v2.PluginManagementService.PluginException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 本地与远程包统一进入私有暂存目录，预览阶段只验证数据。 */
final class PluginPackageVerifier {
    static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 768L * 1024 * 1024;
    static final int MAX_FILES = 4_096;
    private final Path stagingRoot;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration bodyTimeout;

    PluginPackageVerifier(Path stagingRoot, ObjectMapper json, HttpClient http) throws IOException {
        this(stagingRoot, json, http, Duration.ofMinutes(3));
    }

    PluginPackageVerifier(Path stagingRoot, ObjectMapper json, HttpClient http, Duration bodyTimeout) throws IOException {
        this.stagingRoot = stagingRoot.toAbsolutePath().normalize();
        this.json = json;
        this.http = http;
        if (bodyTimeout == null || bodyTimeout.toMillis() <= 0) throw new IllegalArgumentException("响应体超时必须大于零");
        this.bodyTimeout = bodyTimeout;
        PluginPaths.directory(this.stagingRoot, this.stagingRoot);
        // 未发布的预览没有跨宿主恢复意义，重启时清除孤立暂存目录。
        try (var leftovers = Files.list(this.stagingRoot)) {
            for (Path path : leftovers.filter(value -> value.getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f-]{27}")).toList()) PluginPaths.deleteTree(this.stagingRoot, path);
        }
    }

    VerifiedPackage inspect(Path source, Consumer<InstallProgress> progress, BooleanSupplier cancelled) {
        try {
            if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("安装源必须为普通 ZIP 文件");
            try (InputStream input = Files.newInputStream(source)) {
                return stage(input, Files.size(source), source.toAbsolutePath().toString(), progress, cancelled);
            }
        } catch (PluginException | CancellationException failure) { throw failure; }
        catch (Exception failure) { throw new PluginException("package_invalid", "无法预览插件包: " + failure.getMessage(), failure); }
    }

    VerifiedPackage inspect(URI source, Consumer<InstallProgress> progress, BooleanSupplier cancelled) {
        requireHttps(source);
        // 临时客户端与响应体均在本次下载结束时关闭，避免保留连接和执行资源。
        try (HttpClient downloads = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).sslContext(http.sslContext()).build()) {
            // 重定向逐跳验证；不能让共享 HttpClient 自动跳转至明文或文件地址。
            URI target = source;
            for (int redirects = 0; redirects <= 5; redirects++) {
                checkCancelled(cancelled);
                HttpResponse<InputStream> response = downloads.send(HttpRequest.newBuilder(target)
                        .timeout(Duration.ofMinutes(3)).header("Accept", "application/zip").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    try (InputStream ignored = response.body()) {
                        target = target.resolve(response.headers().firstValue("location").orElseThrow(() -> new IOException("重定向缺少目标")));
                        requireHttps(target);
                        continue;
                    }
                }
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("下载返回 HTTP " + response.statusCode());
                }
                long size = response.headers().firstValueAsLong("content-length").orElse(-1);
                try (InputStream body = response.body()) {
                    // ofInputStream 的请求超时只覆盖响应头，另为缓慢响应体设置总截止时间。
                    AtomicReference<InputStream> pendingBody = new AtomicReference<>(body);
                    CompletableFuture<Void> deadline = CompletableFuture.runAsync(() -> {
                        InputStream pending = pendingBody.getAndSet(null);
                        if (pending != null) {
                            try { pending.close(); }
                            catch (IOException ignored) { /* 超时已通过关闭响应体传递给读取线程。 */ }
                        }
                    }, CompletableFuture.delayedExecutor(bodyTimeout.toMillis(), TimeUnit.MILLISECONDS));
                    try { return stage(body, size, source.toString(), progress, cancelled); }
                    finally { pendingBody.set(null); deadline.cancel(false); }
                }
            }
            throw new IOException("下载重定向次数超限");
        } catch (PluginException | CancellationException failure) { throw failure; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("插件下载已取消"); }
        catch (Exception failure) { throw new PluginException("download_failed", "插件下载失败: " + failure.getMessage(), failure); }
    }

    private VerifiedPackage stage(InputStream input, long expected, String source,
                                  Consumer<InstallProgress> progress, BooleanSupplier cancelled) throws Exception {
        if (expected > MAX_ARCHIVE_BYTES) throw new IOException("插件压缩包超过 256 MiB");
        String previewId = UUID.randomUUID().toString();
        Path staging = PluginPaths.directory(stagingRoot, stagingRoot.resolve(previewId));
        try {
            Path archive = staging.resolve("package.zip");
            long copied = 0;
            try (var output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkCancelled(cancelled);
                    copied += read;
                    if (copied > MAX_ARCHIVE_BYTES) throw new IOException("插件压缩包超过 256 MiB");
                    output.write(buffer, 0, read);
                    progress.accept(new InstallProgress("STAGING", copied, expected, "正在暂存插件包"));
                }
            }
            rejectZipLinks(archive);
            Path extracted = PluginPaths.directory(stagingRoot, staging.resolve("contents"));
            extract(archive, extracted, cancelled);
            VerifiedContent content = verifyContents(extracted, cancelled);
            PackagePreview preview = new PackagePreview(previewId, content.manifest.id(), content.manifest.name(),
                    content.manifest.version(), content.manifest.description(), content.manifest.publisher(),
                    content.signerFingerprint, content.hash, content.manifest.json(), content.manifest.schemaJson(),
                    content.manifest.permissions(), content.manifest.capabilities(), content.files, content.signatureVerified(), copied, source);
            progress.accept(new InstallProgress("VERIFIED", copied, copied, content.signatureVerified() ? "签名与全部文件校验完成" : "文件校验完成；插件包未签名，需要明确确认来源"));
            return new VerifiedPackage(preview, content.manifest, staging, extracted, Instant.now());
        } catch (Throwable failure) {
            try { PluginPaths.deleteTree(stagingRoot, staging); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    VerifiedContent verifyContents(Path extracted, BooleanSupplier cancelled) throws Exception {
        Path list = PluginPaths.managed(extracted, extracted.resolve("files.sha256"));
        Path signaturePath = PluginPaths.managed(extracted, extracted.resolve("signature.json"));
        if (!Files.isRegularFile(list, LinkOption.NOFOLLOW_LINKS)) throw new IOException("插件包缺少 SHA256 文件清单");
        boolean signed = Files.exists(signaturePath, LinkOption.NOFOLLOW_LINKS);
        if (signed && !Files.isRegularFile(signaturePath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("插件签名必须为普通文件");
        if (Files.size(list) > 1024 * 1024 || signed && Files.size(signaturePath) > 16_384) throw new IOException("插件签名元数据过大");
        byte[] signedBytes = Files.readAllBytes(list);
        byte[] publicKey = new byte[0];
        if (signed) {
        JsonNode signature = json.readTree(signaturePath.toFile());
        if (!"Ed25519".equals(signature.path("algorithm").asText())) throw new IOException("仅支持 Ed25519 插件签名");
        publicKey = Base64.getDecoder().decode(signature.path("publicKey").asText());
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey)));
        verifier.update(signedBytes);
        if (!verifier.verify(Base64.getDecoder().decode(signature.path("signature").asText()))) throw new IOException("插件文件清单签名无效");
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String line : new String(signedBytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            if (!line.matches("[a-fA-F0-9]{64} [ *].+")) throw new IOException("文件 SHA256 清单格式无效");
            String name = PluginPaths.relative(line.substring(66));
            if (name.equals("files.sha256") || name.equals("signature.json") || hashes.putIfAbsent(name, line.substring(0, 64).toLowerCase(java.util.Locale.ROOT)) != null) throw new IOException("文件清单包含重复或保留条目");
        }
        if (!hashes.containsKey("plugin.json")) throw new IOException("文件清单缺少 plugin.json");
        List<PackageFile> files = new ArrayList<>();
        Set<String> actual = new HashSet<>();
        long total = 0;
        try (var paths = Files.walk(extracted)) {
            for (Path file : paths.toList()) {
                checkCancelled(cancelled);
                PluginPaths.managed(extracted, file);
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("插件包含非普通文件");
                String name = extracted.relativize(file).toString().replace('\\', '/');
                total += Files.size(file);
                if (total > MAX_EXPANDED_BYTES || actual.size() >= MAX_FILES) throw new IOException("插件展开内容超限");
                if (name.equals("files.sha256") || name.equals("signature.json")) continue;
                actual.add(name);
                String expected = hashes.get(name);
                String hash = PluginPaths.sha256(file);
                if (expected == null || !expected.equals(hash)) throw new IOException("插件文件未声明或 SHA256 不一致: " + name);
                files.add(new PackageFile(name, Files.size(file), hash));
            }
        }
        if (!actual.equals(hashes.keySet())) throw new IOException("插件文件清单与包内容不一致");
        Path manifestPath = extracted.resolve("plugin.json");
        if (Files.size(manifestPath) > 1024 * 1024) throw new IOException("插件清单过大");
        PluginManifest manifest = PluginManifest.parse(json, Files.readString(manifestPath));
        if (!hashes.containsKey(manifest.artifact())) throw new IOException("插件入口工件未签名");
        return new VerifiedContent(manifest, PluginPaths.sha256(signedBytes), signed ? PluginPaths.sha256(publicKey) : "", List.copyOf(files), signed);
    }

    private void extract(Path archive, Path extracted, BooleanSupplier cancelled) throws IOException {
        Set<String> names = new HashSet<>();
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled(cancelled);
                String name = PluginPaths.relative(entry.getName());
                if (!names.add(name.toLowerCase(java.util.Locale.ROOT)) || names.size() > MAX_FILES) throw new IOException("ZIP 条目重复或数量超限");
                Path destination = PluginPaths.managed(extracted, extracted.resolve(name));
                if (entry.isDirectory()) { PluginPaths.directory(extracted, destination); continue; }
                if (entry.getSize() > MAX_EXPANDED_BYTES) throw new IOException("ZIP 文件展开大小超限");
                PluginPaths.directory(extracted, destination.getParent());
                try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        checkCancelled(cancelled);
                        expanded += read;
                        if (expanded > MAX_EXPANDED_BYTES) throw new IOException("ZIP 展开内容超过 768 MiB");
                        output.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    /** JDK ZipEntry 不公开 Unix 文件类型，直接检查中央目录拒绝符号链接。 */
    private static void rejectZipLinks(Path archive) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(archive)) {
            long size = channel.size();
            int tailSize = (int) Math.min(size, 65_557);
            ByteBuffer tail = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN);
            channel.position(size - tailSize);
            while (tail.hasRemaining() && channel.read(tail) > 0) { }
            int end = -1;
            for (int index = tailSize - 22; index >= 0; index--) {
                if (tail.getInt(index) == 0x06054b50 && index + 22 + Short.toUnsignedInt(tail.getShort(index + 20)) == tailSize) { end = index; break; }
            }
            if (end < 0) throw new IOException("ZIP 中央目录缺失");
            if (tail.getShort(end + 4) != 0 || tail.getShort(end + 6) != 0 || tail.getShort(end + 8) != tail.getShort(end + 10)) throw new IOException("多卷 ZIP 不受支持");
            int count = Short.toUnsignedInt(tail.getShort(end + 10));
            long centralSize = Integer.toUnsignedLong(tail.getInt(end + 12));
            long centralOffset = Integer.toUnsignedLong(tail.getInt(end + 16));
            if (count == 0 || count > MAX_FILES || centralSize > 4L * 1024 * 1024 || centralOffset + centralSize > size) throw new IOException("ZIP 中央目录超限或 ZIP64 不受支持");
            ByteBuffer central = ByteBuffer.allocate((int) centralSize).order(ByteOrder.LITTLE_ENDIAN);
            channel.position(centralOffset);
            while (central.hasRemaining() && channel.read(central) > 0) { }
            central.flip();
            for (int index = 0; index < count; index++) {
                int offset = central.position();
                if (central.remaining() < 46 || central.getInt(offset) != 0x02014b50) throw new IOException("ZIP 中央目录损坏");
                int flags = Short.toUnsignedInt(central.getShort(offset + 8));
                int mode = (int) (Integer.toUnsignedLong(central.getInt(offset + 38)) >>> 16);
                if ((flags & 1) != 0 || (mode & 0170000) == 0120000) throw new IOException("ZIP 加密条目或符号链接被拒绝");
                int length = 46 + Short.toUnsignedInt(central.getShort(offset + 28)) + Short.toUnsignedInt(central.getShort(offset + 30)) + Short.toUnsignedInt(central.getShort(offset + 32));
                if (length > central.remaining()) throw new IOException("ZIP 中央目录条目损坏");
                central.position(offset + length);
            }
        }
    }

    private static void requireHttps(URI source) {
        if (source == null || !"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null
                || source.getUserInfo() != null || source.getFragment() != null) throw new PluginException("invalid_source", "远程插件地址必须为无凭据的 HTTPS URL");
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException("插件预览已取消");
    }
    record VerifiedPackage(PackagePreview preview, PluginManifest manifest, Path staging,
                           Path contents, Instant createdAt) { }
    record VerifiedContent(PluginManifest manifest, String hash, String signerFingerprint, List<PackageFile> files, boolean signatureVerified) { }
}
