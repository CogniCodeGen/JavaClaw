package com.javaclaw.infrastructure.plugin.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.config.CredentialCipher;
import com.javaclaw.framework.spi.JsonSchemaValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 配置历史不可变，当前指针最后发布；密钥在历史中同样只保存密文。 */
final class PluginStateStore {
    private final Path root;
    private final ObjectMapper json;
    private final CredentialCipher cipher;
    private final Set<Path> recovered = java.util.concurrent.ConcurrentHashMap.newKeySet();

    PluginStateStore(Path root, ObjectMapper json, CredentialCipher cipher) {
        this.root = root.toAbsolutePath().normalize(); this.json = json; this.cipher = cipher;
        try { PluginPaths.directory(this.root, this.root); }
        catch (IOException failure) { throw error("无法创建插件工作区存储", failure); }
    }

    Path directory(String workspace, String plugin) {
        try { return PluginPaths.managed(root, root.resolve(PluginManifest.identifier(workspace)).resolve("plugins").resolve(PluginManifest.identifier(plugin))); }
        catch (IOException failure) { throw error("插件工作区路径不安全", failure); }
    }

    Path data(String workspace, String plugin) {
        try { return PluginPaths.directory(root, directory(workspace, plugin).resolve("data")); }
        catch (IOException failure) { throw error("无法创建插件私有数据目录", failure); }
    }

    StoredConfiguration current(String workspace, PluginManifest manifest, String hash) {
        Path directory = directory(workspace, manifest.id());
        recoverCurrent(directory);
        Path current = directory.resolve("current.json");
        if (!Files.exists(current)) {
            ObjectNode defaults = json.createObjectNode();
            try { json.readTree(manifest.schemaJson()).path("properties").fields().forEachRemaining(field -> {
                if (field.getValue().has("default") && !manifest.secretKeys().contains(field.getKey())) defaults.set(field.getKey(), field.getValue().get("default"));
            }); } catch (IOException failure) { throw error("配置 Schema 无法解析", failure); }
            StoredConfiguration initial = new StoredConfiguration(1, hash, defaults.toString(), Map.of(), Set.of());
            commit(workspace, manifest.id(), initial);
            rememberManifest(workspace, manifest, hash);
            return initial;
        }
        StoredConfiguration value = read(current, StoredConfiguration.class);
        if (value.artifactHash.equals(hash)) rememberManifest(workspace, manifest, hash);
        return value;
    }

    void rememberManifest(String workspace, PluginManifest manifest, String hash) {
        if (!hash.matches("[0-9a-f]{64}")) throw new PluginException("STATE_STORAGE_FAILED", "清单快照哈希无效");
        try {
            Path manifests = PluginPaths.directory(root, directory(workspace, manifest.id()).resolve("manifests"));
            Path file = manifests.resolve(hash + ".json");
            if (!Files.exists(file)) atomicWrite(file, manifest.json().getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException failure) { throw error("无法保存插件清单快照", failure); }
    }

    PluginManifest manifestSnapshot(String workspace, String plugin, String hash) {
        if (!hash.matches("[0-9a-f]{64}")) throw new PluginException("RECOVERY_BLOCKED", "清单快照哈希无效");
        try {
            Path file = PluginPaths.managed(root, directory(workspace, plugin).resolve("manifests").resolve(hash + ".json"));
            if (!Files.isRegularFile(file) || Files.size(file) > 1024 * 1024) throw new IOException("清单快照不存在");
            PluginManifest manifest = PluginManifest.parse(json, Files.readString(file));
            if (!manifest.id().equals(plugin)) throw new IOException("清单快照身份不匹配");
            return manifest;
        } catch (IOException failure) { throw new PluginException("RECOVERY_BLOCKED", "旧插件版本及其展示快照均不可用", failure); }
    }

    StoredConfiguration historical(String workspace, String plugin, String hash, long revision) {
        if (revision < 1) throw new PluginException("RECOVERY_BLOCKED", "配置版本无效");
        Path file = directory(workspace, plugin).resolve("revisions").resolve(revision + ".json");
        if (!Files.isRegularFile(file)) throw new PluginException("RECOVERY_BLOCKED", "恢复所需的配置版本已不可用");
        StoredConfiguration value = read(file, StoredConfiguration.class);
        if (!value.artifactHash.equals(hash)) throw new PluginException("RECOVERY_BLOCKED", "配置版本与工件哈希不匹配");
        return value;
    }

    StoredConfiguration candidate(PluginManifest manifest, String hash, StoredConfiguration old, ConfigurationUpdate update) {
        if (old.revision != update.expectedRevision()) throw new PluginException("REVISION_CONFLICT", "配置已更新，请刷新后重试");
        if (!manifest.permissions().containsAll(update.grantedPermissions())) throw new PluginException("PERMISSION_DENIED", "只能授予清单中声明的权限");
        ObjectNode body = object(update.configurationJson());
        Map<String, String> secrets = new LinkedHashMap<>();
        old.encryptedSecrets.forEach((key, value) -> { if (manifest.secretKeys().contains(key)) secrets.put(key, value); });
        for (String key : manifest.secretKeys()) {
            if (body.has(key)) throw new PluginException("SECRET_ACTION_REQUIRED", "敏感字段必须使用保留、替换或清除操作: " + key);
            SecretChange change = update.secretChanges().getOrDefault(key, SecretChange.keep());
            switch (change.action()) {
                case KEEP -> { }
                case CLEAR -> secrets.remove(key);
                case SET -> {
                    String encrypted = cipher.encrypt(change.value());
                    if (!cipher.isEncrypted(encrypted)) throw new PluginException("SECRET_ENCRYPTION_FAILED", "密钥加密失败");
                    secrets.put(key, encrypted);
                }
            }
        }
        if (!manifest.secretKeys().containsAll(update.secretChanges().keySet())) throw new PluginException("UNKNOWN_SECRET", "密钥字段未在配置 Schema 中声明");
        StoredConfiguration next = new StoredConfiguration(Math.addExact(old.revision, 1), hash, body.toString(), secrets, update.grantedPermissions());
        validate(manifest, next);
        return next;
    }

    void validate(PluginManifest manifest, StoredConfiguration configuration) {
        try {
            List<?> issues = new JsonSchemaValidator().validate(json.readTree(manifest.schemaJson()), object(runtime(configuration)), "$");
            if (!issues.isEmpty()) throw new PluginException("INVALID_CONFIGURATION", "配置不符合 Schema: " + issues);
        } catch (IOException failure) { throw error("配置无法解析", failure); }
    }

    String runtime(StoredConfiguration configuration) {
        ObjectNode body = object(configuration.configurationJson);
        configuration.encryptedSecrets.forEach((key, value) -> {
            if (!cipher.isEncrypted(value)) throw new PluginException("SECRET_UNAVAILABLE", "插件密钥历史不是有效密文: " + key);
            String plain = cipher.decrypt(value);
            if (plain == null || cipher.isEncrypted(plain)) throw new PluginException("SECRET_UNAVAILABLE", "无法解密插件密钥: " + key);
            body.put(key, plain);
        });
        return body.toString();
    }

    private void recoverCurrent(Path directory) {
        if (!recovered.add(directory)) return;
        try {
            Path revisions = directory.resolve("revisions");
            if (!Files.isDirectory(revisions)) return;
            PluginPaths.managed(root, revisions);
            Path latest;
            try (var files = Files.list(revisions)) {
                latest = files.filter(path -> path.getFileName().toString().matches("[1-9][0-9]{0,18}\\.json"))
                        .max(java.util.Comparator.comparingLong(path -> Long.parseLong(path.getFileName().toString().replace(".json", "")))).orElse(null);
            }
            if (latest == null) return;
            StoredConfiguration value = read(latest, StoredConfiguration.class);
            long revision = Long.parseLong(latest.getFileName().toString().replace(".json", ""));
            if (value.revision != revision) throw new IOException("配置历史文件名与版本不一致");
            Path pointer = directory.resolve("current.json");
            if (!Files.isRegularFile(pointer) || read(pointer, StoredConfiguration.class).revision < revision) {
                // 历史写入成功而当前指针尚未发布时，重启补齐这次已经持久化的提交。
                atomicWrite(pointer, json.writeValueAsBytes(value), true);
            }
        } catch (IOException | RuntimeException failure) {
            recovered.remove(directory);
            throw new PluginException("STATE_RECOVERY_FAILED", "插件配置提交恢复失败", failure);
        }
    }

    ConfigurationSnapshot snapshot(PluginManifest manifest, StoredConfiguration configuration) {
        Map<String, Boolean> configured = new LinkedHashMap<>();
        manifest.secretKeys().forEach(key -> configured.put(key, configuration.encryptedSecrets.containsKey(key)));
        return new ConfigurationSnapshot(configuration.revision, manifest.schemaJson(), configuration.configurationJson, configured, configuration.permissions);
    }

    void commit(String workspace, String plugin, StoredConfiguration value) {
        Path directory = directory(workspace, plugin);
        try {
            PluginPaths.directory(root, directory.resolve("revisions"));
            Path history = directory.resolve("revisions").resolve(value.revision + ".json");
            byte[] bytes = json.writeValueAsBytes(value);
            if (Files.exists(history)) {
                StoredConfiguration existing = read(history, StoredConfiguration.class);
                if (!existing.equals(value)) throw new PluginException("REVISION_CONFLICT", "配置历史已存在，不能覆盖");
            } else atomicWrite(history, bytes, false);
            atomicWrite(directory.resolve("current.json"), bytes, true);
        } catch (IOException failure) { throw error("无法提交插件配置", failure); }
    }

    Preferences preferences(String workspace, String plugin) {
        Path file = directory(workspace, plugin).resolve("preferences.json");
        return Files.isRegularFile(file) ? read(file, Preferences.class) : new Preferences(false, StartupPolicy.MANUAL, false, false);
    }

    void preferences(String workspace, String plugin, Preferences preferences) {
        Path directory = directory(workspace, plugin);
        try { PluginPaths.directory(root, directory); atomicWrite(directory.resolve("preferences.json"), json.writeValueAsBytes(preferences), true); }
        catch (IOException failure) { throw error("无法保存插件状态", failure); }
    }

    List<String> referencingWorkspaces(String plugin) {
        try (var directories = Files.list(root)) {
            return directories.filter(path -> Files.isDirectory(path) && !Files.isSymbolicLink(path))
                    .map(path -> path.getFileName().toString()).filter(workspace -> Files.exists(directory(workspace, plugin))).sorted().toList();
        } catch (IOException failure) { throw error("无法读取工作区引用", failure); }
    }

    void delete(String workspace, String plugin) {
        try { PluginPaths.deleteTree(root, directory(workspace, plugin)); }
        catch (IOException failure) { throw error("无法清理插件私有数据", failure); }
    }

    private ObjectNode object(String value) {
        try {
            JsonNode node = json.readTree(value == null || value.isBlank() ? "{}" : value);
            if (node instanceof ObjectNode object) return object;
            throw new PluginException("INVALID_CONFIGURATION", "配置必须是 JSON 对象");
        } catch (IOException failure) { throw new PluginException("INVALID_CONFIGURATION", "JSON 无效", failure); }
    }

    <T> T read(Path path, Class<T> type) {
        try {
            PluginPaths.managed(root, path);
            if (Files.size(path) > 4 * 1024 * 1024) throw new IOException("状态文件过大");
            return json.readValue(Files.readAllBytes(path), type);
        } catch (IOException failure) { throw error("插件状态无法读取", failure); }
    }

    static void atomicWrite(Path target, byte[] bytes, boolean replace) throws IOException {
        Path temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (replace) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    record StoredConfiguration(long revision, String artifactHash, String configurationJson,
                               Map<String, String> encryptedSecrets, Set<String> permissions) {
        StoredConfiguration { encryptedSecrets = Map.copyOf(encryptedSecrets); permissions = Set.copyOf(permissions); }
    }
    record Preferences(boolean enabled, StartupPolicy startupPolicy, boolean revoked, boolean quarantined) { }
    private static PluginException error(String message, Exception failure) { return new PluginException("STATE_STORAGE_FAILED", message, failure); }
}
