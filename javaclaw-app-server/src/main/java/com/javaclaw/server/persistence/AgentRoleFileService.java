package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileExport;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ModelPreference;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.role.AgentRoleTomlCodec;

/** Role 文件显式预览、确认和导出；从不监听文件变化，也不根据模糊模型名称猜测 Provider。 */
public final class AgentRoleFileService {
    private final H2Transactions transactions;
    private final AgentRoleService roles;
    private final ProviderService providers;
    private final CanonicalJson json;
    private final Clock clock;
    private final AgentRoleImportRepository imports;
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final AgentRoleTomlCodec codec = new AgentRoleTomlCodec();

    /**
     * 创建 Role 文件服务。
     *
     * @param database data-v6 数据库
     * @param roles Role 版本服务
     * @param providers Provider 精确映射服务
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public AgentRoleFileService(
            H2Database database, AgentRoleService roles, ProviderService providers, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.roles = Objects.requireNonNull(roles, "roles");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        imports = new AgentRoleImportRepository(json);
    }

    /**
     * 持久化待确认预览，不写入运行时 Role；重新导入返回与当前版本的字段差异。
     *
     * @param roleId 用户选择的目标稳定标识
     * @param content UTF-8 内容，不得超过 1 MiB
     * @param format 明确文件模式
     * @return 待确认预览，模型映射不明确时包含 unresolvedModel
     */
    public AgentRoleFilePreview preview(String roleId, String content, AgentRoleFileFormat format) {
        AgentRoleTomlCodec.ParsedRole parsed = codec.parse(content, Objects.requireNonNull(format, "format"));
        AgentRoleSpec spec = parsed.spec();
        Optional<String> unresolved = parsed.unresolvedModel();
        if (unresolved.isPresent()) {
            Optional<ProviderRef> match = uniqueProvider(unresolved.orElseThrow());
            if (match.isPresent()) {
                spec = withModel(spec, match.orElseThrow());
                unresolved = Optional.empty();
            }
        }
        AgentRoleFilePreview preview = new AgentRoleFilePreview(
                UUID.randomUUID().toString(),
                roleId,
                spec,
                parsed.contentDigest(),
                unresolved,
                differences(roleId, spec),
                format);
        return execute(connection -> {
            imports.insert(connection, preview, Instant.now(clock));
            return preview;
        });
    }

    /**
     * 提交用户确认的不可变预览；Role 版本、导入记录和幂等结果在同一事务内提交。
     *
     * @param identity 新建 expected revision 为 0，更新必须匹配当前 Role revision
     * @param previewId 已持久化预览标识
     * @param modelMapping 未能唯一映射模型时用户选择的精确 Provider
     * @return 新的 Role revision
     */
    public AgentRole commit(CommandIdentity identity, String previewId, Optional<ProviderRef> modelMapping) {
        Objects.requireNonNull(modelMapping, "modelMapping");
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<IdempotencyRepository.StoredCommand> existing =
                        idempotency.find(connection, identity.idempotencyKey());
                if (existing.isPresent()) {
                    return recover(identity, existing.orElseThrow());
                }
                AgentRoleImportRepository.StoredImport stored = imports.require(connection, previewId, true);
                if (stored.committed().isPresent()) {
                    throw PersistenceException.invalidRequest("该 Role 导入预览已经提交");
                }
                AgentRoleSpec spec = resolveModel(stored.preview(), modelMapping);
                AgentRole role = roles.writeInTransaction(
                        connection, identity, stored.preview().roleId(), spec, RoleLifecycle.ACTIVE);
                imports.commit(connection, previewId, role.ref(), Instant.now(clock));
                return role;
            });
        }
    }

    /**
     * 读取精确历史 Role 并导出；不会读取、写入客户端文件系统。
     *
     * @param reference 精确 Role
     * @param format 明确导出模式
     * @return 建议文件名、TOML 内容和摘要
     */
    public AgentRoleFileExport export(AgentRoleRef reference, AgentRoleFileFormat format) {
        AgentRole role = roles.require(reference.id(), reference.revision());
        String content = codec.export(role, Objects.requireNonNull(format, "format"));
        return new AgentRoleFileExport(role.id() + ".agent.toml", content, AgentRoleTomlCodec.digest(content), format);
    }

    private AgentRoleSpec resolveModel(AgentRoleFilePreview preview, Optional<ProviderRef> mapping) {
        if (preview.unresolvedModel().isPresent()) {
            ProviderRef selected =
                    mapping.orElseThrow(() -> PersistenceException.invalidRequest("必须显式选择模型对应 Provider"));
            if (!preview.unresolvedModel().orElseThrow().equals(selected.model())) {
                throw PersistenceException.invalidRequest("所选 Provider 的模型名必须与预览一致");
            }
            providers.requireAvailable(selected, ProviderModelPurpose.CHAT);
            return withModel(preview.spec(), selected);
        }
        if (mapping.isPresent()) {
            throw PersistenceException.invalidRequest("已解析的模型不能在确认时静默替换，请重新预览");
        }
        preview.spec()
                .model()
                .ifPresent(value -> providers.requireAvailable(value.provider(), ProviderModelPurpose.CHAT));
        return preview.spec();
    }

    private Optional<ProviderRef> uniqueProvider(String model) {
        List<ProviderRef> matches = providers.listLatest().stream()
                .filter(provider -> provider.lifecycle() == ProviderLifecycle.ACTIVE)
                .filter(provider -> provider.spec().models().stream()
                        .anyMatch(value -> value.modelId().equals(model) && value.supports(ProviderModelPurpose.CHAT)))
                .map(provider -> new ProviderRef(provider.id(), provider.revision(), model))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private List<String> differences(String roleId, AgentRoleSpec spec) {
        Optional<AgentRoleSpec> current = roles.listLatest().stream()
                .filter(role -> role.id().equals(roleId))
                .map(AgentRole::spec)
                .findFirst();
        if (current.isEmpty()) {
            return List.of("create");
        }
        AgentRoleSpec before = current.orElseThrow();
        List<String> changed = new ArrayList<>();
        difference(changed, "name", before.name(), spec.name());
        difference(changed, "description", before.description(), spec.description());
        difference(changed, "developerInstructions", before.developerInstructions(), spec.developerInstructions());
        difference(changed, "model", before.model(), spec.model());
        difference(changed, "reasoning", before.reasoning(), spec.reasoning());
        difference(changed, "narrowing", before.narrowing(), spec.narrowing());
        difference(changed, "permissionConstraint", before.permissionConstraint(), spec.permissionConstraint());
        difference(changed, "extensions", before.extensions(), spec.extensions());
        return List.copyOf(changed);
    }

    private static void difference(List<String> changed, String field, Object before, Object after) {
        if (!before.equals(after)) {
            changed.add(field);
        }
    }

    private static AgentRoleSpec withModel(AgentRoleSpec spec, ProviderRef reference) {
        return new AgentRoleSpec(
                spec.name(),
                spec.description(),
                spec.developerInstructions(),
                Optional.of(new ModelPreference(reference)),
                spec.reasoning(),
                spec.narrowing(),
                spec.permissionConstraint(),
                spec.extensions());
    }

    private AgentRole recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), AgentRole.class);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Role 文件事务失败", failure);
        }
    }
}
