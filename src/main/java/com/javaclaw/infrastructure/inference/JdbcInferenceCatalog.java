package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import com.javaclaw.inference.api.InferenceUsage;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** H2 中的全局推理目录。模型权重只保存路径与不可变摘要，不写入数据库。 */
public final class JdbcInferenceCatalog implements InferenceCatalogPort {

    private static final TypeReference<List<InferenceModelAsset.AssetFile>> ASSET_FILES = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> PARAMETER_MAP = new TypeReference<>() { };
    private static final TypeReference<Set<ApiScope>> API_SCOPES = new TypeReference<>() { };
    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcInferenceCatalog(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper json) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                java.util.Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    @Override
    public List<RuntimeInstallation> runtimes() {
        return jdbc.query("SELECT * FROM inference_runtimes ORDER BY installed_at DESC",
                (RowMapper<RuntimeInstallation>) this::mapRuntime);
    }

    @Override
    public Optional<RuntimeInstallation> runtime(String runtimeId) {
        return optional("SELECT * FROM inference_runtimes WHERE runtime_id = ?", this::mapRuntime, runtimeId);
    }

    @Override
    public void saveRuntime(RuntimeInstallation value) {
        InferenceRuntimeManifest m = value.manifest();
        jdbc.update("""
                MERGE INTO inference_runtimes (
                  runtime_id, engine, engine_version, adapter_version, protocol_major, protocol_minor,
                  platform, architecture, manifest_json, install_path, runtime_state, active, installed_at)
                KEY(runtime_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, m.runtimeId(), m.engine(), m.engineVersion(), m.adapterVersion(),
                m.protocol().major(), m.protocol().minor(), m.platform(), m.architecture(), write(m),
                value.installPath(), value.state().name(), value.active(), value.installedAt().toEpochMilli());
    }

    @Override
    public void setActiveRuntime(String engine, String runtimeId) {
        transactions.executeWithoutResult(status -> {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inference_runtimes WHERE runtime_id = ? AND engine = ?",
                    Integer.class, runtimeId, engine);
            if (exists == null || exists == 0) throw new IllegalArgumentException("运行时不存在或引擎不匹配");
            String current = jdbc.query("SELECT runtime_id FROM inference_runtimes "
                            + "WHERE engine = ? AND active = TRUE",
                    rs -> rs.next() ? rs.getString(1) : null, engine);
            if (runtimeId.equals(current)) return;
            if (current != null) {
                String latest = jdbc.query("SELECT runtime_id FROM inference_runtime_activation_history "
                                + "WHERE engine = ? ORDER BY activation_id DESC LIMIT 1",
                        rs -> rs.next() ? rs.getString(1) : null, engine);
                if (!current.equals(latest)) recordActivation(engine, current);
            }
            jdbc.update("UPDATE inference_runtimes SET active = FALSE, "
                    + "runtime_state = CASE WHEN runtime_state = 'ACTIVE' THEN 'INSTALLED' ELSE runtime_state END "
                    + "WHERE engine = ?", engine);
            jdbc.update("UPDATE inference_runtimes SET active = TRUE, runtime_state = 'ACTIVE' "
                    + "WHERE runtime_id = ?", runtimeId);
            recordActivation(engine, runtimeId);
        });
    }

    @Override
    public Optional<String> previousActiveRuntime(String engine) {
        String current = jdbc.query("SELECT runtime_id FROM inference_runtimes "
                        + "WHERE engine = ? AND active = TRUE",
                rs -> rs.next() ? rs.getString(1) : null, engine);
        if (current == null) return Optional.empty();
        return optional("SELECT runtime_id FROM inference_runtime_activation_history "
                        + "WHERE engine = ? AND runtime_id <> ? ORDER BY activation_id DESC LIMIT 1",
                (rs, row) -> rs.getString(1), engine, current);
    }

    @Override
    public void deleteRuntime(String runtimeId) {
        transactions.executeWithoutResult(status -> {
            int refs = count("SELECT COUNT(*) FROM inference_model_profiles WHERE runtime_id = ?", runtimeId);
            if (refs > 0) throw new IllegalStateException("运行时仍被模型档案引用");
            jdbc.update("DELETE FROM inference_runtime_activation_history WHERE runtime_id = ?", runtimeId);
            int deleted = jdbc.update("DELETE FROM inference_runtimes WHERE runtime_id = ?", runtimeId);
            if (deleted != 1) {
                throw new IllegalStateException("运行时不存在或删除失败: " + runtimeId);
            }
        });
    }

    private void recordActivation(String engine, String runtimeId) {
        jdbc.update("INSERT INTO inference_runtime_activation_history "
                        + "(engine, runtime_id, activated_at) VALUES (?, ?, ?)",
                engine, runtimeId, System.currentTimeMillis());
    }

    @Override
    public List<InferenceModelAsset> assets() {
        return jdbc.query("SELECT * FROM inference_model_assets ORDER BY created_at DESC",
                (RowMapper<InferenceModelAsset>) this::mapAsset);
    }

    @Override
    public List<ModelAssetSummary> assetSummaries() {
        return jdbc.query("""
                SELECT asset_id, display_name, model_type, size_bytes, asset_state, failure
                FROM inference_model_assets ORDER BY created_at DESC
                """, (rs, row) -> new ModelAssetSummary(
                UUID.fromString(rs.getString("asset_id")),
                nullable(rs.getString("display_name")), nullable(rs.getString("model_type")),
                rs.getLong("size_bytes"),
                InferenceModelAsset.State.valueOf(rs.getString("asset_state")),
                nullable(rs.getString("failure"))));
    }

    @Override
    public Optional<InferenceModelAsset> asset(UUID id) {
        return optional("SELECT * FROM inference_model_assets WHERE asset_id = ?", this::mapAsset, id.toString());
    }

    @Override
    public Optional<InferenceModelAsset> assetByHash(String sha256) {
        return optional("SELECT * FROM inference_model_assets WHERE content_sha256 = ?", this::mapAsset, sha256);
    }

    @Override
    public void saveAsset(InferenceModelAsset value) {
        jdbc.update("""
                MERGE INTO inference_model_assets (
                  asset_id, source_type, display_name, model_type, content_sha256, asset_path,
                  hf_repository, hf_commit, files_json, size_bytes, artifact_format,
                  quantization_type, source_size_bytes, quantized_size_bytes, source_updated_at,
                  asset_state, failure, created_at)
                KEY(asset_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.id().toString(), value.source().name(), value.displayName(),
                value.modelType(), value.contentSha256(), value.location(),
                emptyToNull(value.huggingFaceRepository()), emptyToNull(value.huggingFaceCommit()),
                write(value.files()), value.sizeBytes(), value.format(), value.quantizationType(),
                value.sourceSizeBytes(), value.artifactMetadata().quantizedSizeBytes(),
                value.sourceUpdatedAt().toEpochMilli(), value.state().name(), emptyToNull(value.failure()),
                value.createdAt().toEpochMilli());
    }

    @Override
    public boolean assetReferenced(UUID id) {
        return count("SELECT COUNT(*) FROM inference_model_profiles WHERE asset_id = ?", id.toString()) > 0;
    }

    @Override
    public void deleteAsset(UUID id) {
        if (assetReferenced(id)) throw new IllegalStateException("模型资产仍被档案引用");
        jdbc.update("DELETE FROM inference_model_assets WHERE asset_id = ?", id.toString());
    }

    @Override
    public List<InferenceModelProfile> profiles() {
        return jdbc.query("SELECT * FROM inference_model_profiles ORDER BY updated_at DESC",
                (RowMapper<InferenceModelProfile>) this::mapProfile);
    }

    @Override
    public List<ModelProfileSummary> profileSummaries(InferenceModelProfile.Kind kind) {
        java.util.Objects.requireNonNull(kind, "kind");
        return jdbc.query("""
                SELECT profile_id, asset_id, model_kind, profile_state, failure, updated_at
                FROM inference_model_profiles WHERE model_kind = ? ORDER BY updated_at DESC
                """, (rs, row) -> new ModelProfileSummary(
                UUID.fromString(rs.getString("profile_id")),
                UUID.fromString(rs.getString("asset_id")),
                InferenceModelProfile.Kind.valueOf(rs.getString("model_kind")),
                InferenceModelProfile.State.valueOf(rs.getString("profile_state")),
                nullable(rs.getString("failure")),
                Instant.ofEpochMilli(rs.getLong("updated_at"))), kind.name());
    }

    @Override
    public Optional<InferenceModelProfile> profile(UUID id) {
        return optional("SELECT * FROM inference_model_profiles WHERE profile_id = ?", this::mapProfile, id.toString());
    }

    @Override
    public void saveProfile(InferenceModelProfile value) {
        jdbc.update("""
                MERGE INTO inference_model_profiles (
                  profile_id, profile_name, model_kind, asset_id, runtime_id, load_parameters_json,
                  default_parameters_json, context_length, embedding_dimensions, profile_state,
                  failure, created_at, updated_at)
                KEY(profile_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.id().toString(), value.name(), value.kind().name(), value.assetId().toString(),
                value.runtimeId(), write(value.loadParameters()), write(value.defaultParameters()),
                value.contextLength(), value.embeddingDimensions(), value.state().name(),
                emptyToNull(value.failure()), value.createdAt().toEpochMilli(), value.updatedAt().toEpochMilli());
    }

    @Override
    public boolean profileReferenced(UUID id) {
        return count("SELECT COUNT(*) FROM inference_workspace_bindings WHERE profile_id = ?", id.toString()) > 0
                || count("SELECT COUNT(*) FROM inference_public_models WHERE profile_id = ?", id.toString()) > 0;
    }

    @Override
    public void deleteProfile(UUID id) {
        if (profileReferenced(id)) throw new IllegalStateException("模型档案仍被引用");
        jdbc.update("DELETE FROM inference_model_profiles WHERE profile_id = ?", id.toString());
    }

    @Override
    public Map<ModelTier, UUID> bindings(String workspaceId) {
        Map<ModelTier, UUID> result = new LinkedHashMap<>();
        jdbc.query("SELECT model_tier, profile_id FROM inference_workspace_bindings WHERE workspace_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(
                        ModelTier.valueOf(rs.getString(1)), UUID.fromString(rs.getString(2))), workspaceId);
        return Map.copyOf(result);
    }

    @Override
    public void replaceBindings(String workspaceId, Map<ModelTier, UUID> bindings) {
        Map<ModelTier, UUID> snapshot = Map.copyOf(bindings);
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM inference_workspace_bindings WHERE workspace_id = ?", workspaceId);
            long now = System.currentTimeMillis();
            snapshot.forEach((tier, profileId) -> jdbc.update("""
                    INSERT INTO inference_workspace_bindings
                      (workspace_id, model_tier, profile_id, updated_at) VALUES (?, ?, ?, ?)
                    """, workspaceId, tier.name(), profileId.toString(), now));
        });
    }

    @Override
    public void bind(String workspaceId, ModelTier tier, UUID profileId) {
        jdbc.update("""
                MERGE INTO inference_workspace_bindings (workspace_id, model_tier, profile_id, updated_at)
                KEY(workspace_id, model_tier) VALUES (?, ?, ?, ?)
                """, workspaceId, tier.name(), profileId.toString(), System.currentTimeMillis());
    }

    @Override
    public void clearBinding(String workspaceId, ModelTier tier) {
        jdbc.update("DELETE FROM inference_workspace_bindings WHERE workspace_id = ? AND model_tier = ?",
                workspaceId, tier.name());
    }

    @Override
    public List<PublishedModel> publishedModels() {
        return jdbc.query("SELECT * FROM inference_public_models ORDER BY model_alias", this::published);
    }

    @Override
    public Optional<PublishedModel> publishedModel(String alias) {
        return optional("SELECT * FROM inference_public_models WHERE model_alias = ?", this::published, alias);
    }

    @Override
    public void publish(PublishedModel value) {
        jdbc.update("""
                MERGE INTO inference_public_models
                  (model_alias, profile_id, enabled, created_at, updated_at)
                KEY(model_alias) VALUES (?, ?, ?, ?, ?)
                """, value.alias(), value.profileId().toString(), value.enabled(),
                value.createdAt().toEpochMilli(), value.updatedAt().toEpochMilli());
    }

    @Override
    public void unpublish(String alias) {
        jdbc.update("DELETE FROM inference_public_models WHERE model_alias = ?", alias);
    }

    @Override
    public GatewayConfiguration gatewayConfiguration() {
        return optional("SELECT * FROM inference_gateway_config WHERE config_id = 'default'",
                this::gateway).orElseGet(GatewayConfiguration::defaults);
    }

    @Override
    public void saveGatewayConfiguration(GatewayConfiguration value) {
        jdbc.update("""
                MERGE INTO inference_gateway_config (
                  config_id, enabled, bind_address, port, tls_enabled, allow_insecure_lan, key_store_path,
                  key_store_password_enc, max_request_bytes, request_timeout_seconds,
                  invocation_logging_enabled, updated_at)
                KEY(config_id) VALUES ('default', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.enabled(), value.bindAddress(), value.port(), value.tlsEnabled(),
                value.allowInsecureLanWithoutTls(),
                emptyToNull(value.keyStorePath()), emptyToNull(value.encryptedKeyStorePassword()),
                value.maxRequestBytes(), value.requestTimeoutSeconds(),
                value.invocationLoggingEnabled(), value.updatedAt().toEpochMilli());
    }

    @Override
    public List<ApiKeyRecord> apiKeys() {
        return jdbc.query("SELECT * FROM inference_api_keys ORDER BY created_at DESC", this::apiKey);
    }

    @Override
    public boolean hasActiveApiKey(Set<ApiScope> requiredScopes, String alias) {
        Set<ApiScope> required = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
        return jdbc.query("""
                SELECT scopes_json, models_json FROM inference_api_keys WHERE revoked = FALSE
                """, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> {
            while (rs.next()) {
                Set<ApiScope> scopes = read(rs.getString("scopes_json"), API_SCOPES);
                Set<String> aliases = read(rs.getString("models_json"), STRING_SET);
                if (scopes.containsAll(required)
                        && (alias == null || aliases.isEmpty() || aliases.contains(alias))) {
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public Optional<ApiKeyRecord> apiKeyByPrefix(String prefix) {
        return optional("SELECT * FROM inference_api_keys WHERE key_prefix = ?", this::apiKey, prefix);
    }

    @Override
    public void saveApiKey(ApiKeyRecord value) {
        jdbc.update("""
                MERGE INTO inference_api_keys (
                  key_id, key_name, key_prefix, key_salt, key_digest, scopes_json, models_json,
                  requests_per_minute, tokens_per_minute, max_concurrent, revoked, created_at, last_used_at)
                KEY(key_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.id().toString(), value.name(), value.prefix(), value.salt(), value.digest(),
                write(value.scopes()), write(value.modelAliases()), value.requestsPerMinute(),
                value.tokensPerMinute(), value.maxConcurrent(), value.revoked(),
                value.createdAt().toEpochMilli(), value.lastUsedAt() == null ? null : value.lastUsedAt().toEpochMilli());
    }

    @Override
    public void revokeApiKey(UUID keyId) {
        jdbc.update("UPDATE inference_api_keys SET revoked = TRUE WHERE key_id = ?", keyId.toString());
    }

    @Override
    public void recordApiUsage(UUID keyId, long minuteBucket, InferenceUsage usage, boolean failed) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    MERGE INTO inference_api_usage AS target
                    USING (VALUES (?, ?, ?, ?, ?)) AS source
                      (key_id, minute_bucket, prompt_tokens, completion_tokens, failed_count)
                    ON target.key_id = source.key_id
                      AND target.minute_bucket = source.minute_bucket
                    WHEN MATCHED THEN UPDATE SET
                      request_count = target.request_count + 1,
                      prompt_tokens = target.prompt_tokens + source.prompt_tokens,
                      completion_tokens = target.completion_tokens + source.completion_tokens,
                      failed_count = target.failed_count + source.failed_count
                    WHEN NOT MATCHED THEN INSERT
                      (key_id, minute_bucket, request_count, prompt_tokens, completion_tokens, failed_count)
                    VALUES (source.key_id, source.minute_bucket, 1, source.prompt_tokens,
                      source.completion_tokens, source.failed_count)
                    """, keyId.toString(), minuteBucket, usage.promptTokens(),
                    usage.completionTokens(), failed ? 1 : 0);
            jdbc.update("UPDATE inference_api_keys SET last_used_at = ? WHERE key_id = ?",
                    System.currentTimeMillis(), keyId.toString());
        });
    }

    @Override
    public Optional<ApiUsage> apiUsage(UUID keyId, long minuteBucket) {
        return optional("SELECT * FROM inference_api_usage WHERE key_id = ? AND minute_bucket = ?",
                (rs, row) -> new ApiUsage(rs.getLong("request_count"), rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"), rs.getLong("failed_count")),
                keyId.toString(), minuteBucket);
    }

    private RuntimeInstallation mapRuntime(ResultSet rs, int ignored) throws SQLException {
        InferenceRuntimeManifest manifest = read(rs.getString("manifest_json"), InferenceRuntimeManifest.class);
        return new RuntimeInstallation(manifest, rs.getString("install_path"),
                RuntimeState.valueOf(rs.getString("runtime_state")), rs.getBoolean("active"),
                Instant.ofEpochMilli(rs.getLong("installed_at")));
    }

    private InferenceModelAsset mapAsset(ResultSet rs, int ignored) throws SQLException {
        return new InferenceModelAsset(UUID.fromString(rs.getString("asset_id")),
                InferenceModelAsset.Source.valueOf(rs.getString("source_type")),
                nullable(rs.getString("display_name")), nullable(rs.getString("model_type")),
                rs.getString("content_sha256"), rs.getString("asset_path"),
                nullable(rs.getString("hf_repository")), nullable(rs.getString("hf_commit")),
                read(rs.getString("files_json"), ASSET_FILES), rs.getLong("size_bytes"),
                InferenceModelAsset.State.valueOf(rs.getString("asset_state")),
                nullable(rs.getString("failure")), Instant.ofEpochMilli(rs.getLong("created_at")),
                new InferenceModelAsset.ArtifactMetadata(
                        nullable(rs.getString("artifact_format")),
                        nullable(rs.getString("quantization_type")),
                        rs.getLong("source_size_bytes"), rs.getLong("quantized_size_bytes"),
                        Instant.ofEpochMilli(rs.getLong("source_updated_at"))));
    }

    private InferenceModelProfile mapProfile(ResultSet rs, int ignored) throws SQLException {
        return new InferenceModelProfile(UUID.fromString(rs.getString("profile_id")),
                rs.getString("profile_name"), InferenceModelProfile.Kind.valueOf(rs.getString("model_kind")),
                UUID.fromString(rs.getString("asset_id")), rs.getString("runtime_id"),
                read(rs.getString("load_parameters_json"), PARAMETER_MAP),
                read(rs.getString("default_parameters_json"), PARAMETER_MAP),
                rs.getInt("context_length"), rs.getInt("embedding_dimensions"),
                InferenceModelProfile.State.valueOf(rs.getString("profile_state")),
                nullable(rs.getString("failure")), Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }

    private PublishedModel published(ResultSet rs, int ignored) throws SQLException {
        return new PublishedModel(rs.getString("model_alias"), UUID.fromString(rs.getString("profile_id")),
                rs.getBoolean("enabled"), Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }

    private GatewayConfiguration gateway(ResultSet rs, int ignored) throws SQLException {
        return new GatewayConfiguration(rs.getBoolean("enabled"), rs.getString("bind_address"),
                rs.getInt("port"), rs.getBoolean("tls_enabled"), rs.getBoolean("allow_insecure_lan"),
                nullable(rs.getString("key_store_path")),
                nullable(rs.getString("key_store_password_enc")), rs.getLong("max_request_bytes"),
                rs.getInt("request_timeout_seconds"), rs.getBoolean("invocation_logging_enabled"),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }

    private ApiKeyRecord apiKey(ResultSet rs, int ignored) throws SQLException {
        Long last = (Long) rs.getObject("last_used_at");
        Set<ApiScope> scopes = read(rs.getString("scopes_json"), API_SCOPES);
        Set<String> aliases = read(rs.getString("models_json"), STRING_SET);
        return new ApiKeyRecord(UUID.fromString(rs.getString("key_id")), rs.getString("key_name"),
                rs.getString("key_prefix"), rs.getString("key_salt"), rs.getString("key_digest"),
                scopes.isEmpty() ? EnumSet.noneOf(ApiScope.class) : EnumSet.copyOf(scopes),
                new LinkedHashSet<>(aliases), rs.getInt("requests_per_minute"),
                rs.getLong("tokens_per_minute"), rs.getInt("max_concurrent"), rs.getBoolean("revoked"),
                Instant.ofEpochMilli(rs.getLong("created_at")), last == null ? null : Instant.ofEpochMilli(last));
    }

    private <T> Optional<T> optional(String sql, org.springframework.jdbc.core.RowMapper<T> mapper,
                                     Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("无法序列化推理目录记录", failure);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception failure) {
            throw new IllegalStateException("推理目录记录已损坏", failure);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception failure) {
            throw new IllegalStateException("推理目录记录已损坏", failure);
        }
    }

    private static String nullable(String value) { return value == null ? "" : value; }
    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
