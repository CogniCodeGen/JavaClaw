package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

/** Workspace 默认 Profile 与 Thread 覆盖的版本化绑定服务。 */
public final class ProfileBindingService {
    private final H2Transactions transactions;
    private final ProfileBindingRepository bindings = new ProfileBindingRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final CoreCommandService core;
    private final AgentProfileService profiles;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建绑定服务。
     *
     * @param database data-v5 数据库
     * @param core Workspace 与 Thread 查询服务
     * @param profiles Profile 精确版本服务
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public ProfileBindingService(
            H2Database database,
            CoreCommandService core,
            AgentProfileService profiles,
            CanonicalJson json,
            Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.core = Objects.requireNonNull(core, "core");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 读取指定作用域的直接绑定，不向上回退。
     *
     * @param workspaceId Workspace
     * @param threadId Thread 覆盖；Workspace 默认时为空
     * @return 绑定
     */
    public Optional<ProfileBinding> find(WorkspaceId workspaceId, Optional<ThreadId> threadId) {
        validateScope(workspaceId, threadId);
        return execute(connection -> bindings.find(connection, workspaceId, threadId, false));
    }

    /**
     * 为 Turn 解析显式、Thread、Workspace 三层 Profile。
     *
     * @param threadId Thread
     * @param explicit 客户端显式引用
     * @return ACTIVE Profile
     */
    public AgentProfile resolve(ThreadId threadId, Optional<AgentProfileRef> explicit) {
        ConversationThread thread =
                core.findThread(threadId).orElseThrow(() -> PersistenceException.invalidRequest("Thread 不存在"));
        AgentProfileRef reference = explicit.orElseGet(() -> find(thread.workspaceId(), Optional.of(threadId))
                .or(() -> find(thread.workspaceId(), Optional.empty()))
                .map(ProfileBinding::profile)
                .orElseThrow(() -> PersistenceException.invalidRequest("Thread 与 Workspace 均未配置默认 Profile")));
        return profiles.requireAvailable(reference.id(), reference.revision());
    }

    /**
     * 创建或更新默认绑定。
     *
     * @param identity expected revision 为绑定当前版本；新建为 0
     * @param workspaceId Workspace
     * @param threadId Thread 覆盖；Workspace 默认时为空
     * @param profile 精确 Profile
     * @return 已提交绑定
     */
    public ProfileBinding update(
            CommandIdentity identity, WorkspaceId workspaceId, Optional<ThreadId> threadId, AgentProfileRef profile) {
        validateScope(workspaceId, threadId);
        profiles.requireAvailable(profile.id(), profile.revision());
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<IdempotencyRepository.StoredCommand> stored =
                        idempotency.find(connection, identity.idempotencyKey());
                if (stored.isPresent()) {
                    return recover(identity, stored.orElseThrow());
                }
                Optional<ProfileBinding> current = bindings.find(connection, workspaceId, threadId, true);
                requireRevision(current, identity.expectedRevision());
                ProfileBinding next = new ProfileBinding(
                        workspaceId,
                        threadId,
                        profile,
                        Math.addExact(identity.expectedRevision(), 1),
                        Instant.now(clock));
                if (current.isEmpty()) {
                    bindings.insert(connection, next);
                } else {
                    bindings.update(connection, next, identity.expectedRevision());
                }
                idempotency.insert(connection, identity, json.encode(next), next.updatedAt());
                return next;
            });
        }
    }

    private void validateScope(WorkspaceId workspaceId, Optional<ThreadId> threadId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        core.findWorkspace(workspaceId).orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在"));
        threadId.ifPresent(id -> {
            ConversationThread thread =
                    core.findThread(id).orElseThrow(() -> PersistenceException.invalidRequest("Thread 不存在"));
            if (!thread.workspaceId().equals(workspaceId)) {
                throw PersistenceException.invalidRequest("Profile binding 的 Thread 不属于 Workspace");
            }
        });
    }

    private ProfileBinding recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), ProfileBinding.class);
    }

    private static void requireRevision(Optional<ProfileBinding> current, long expectedRevision) {
        if (current.isEmpty() && expectedRevision == 0) {
            return;
        }
        if (current.isEmpty() || current.orElseThrow().revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Profile binding revision 已改变");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Profile binding 事务失败", failure);
        }
    }
}
