package com.javaclaw.server.turn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PromptOptimizationAdoption;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.PromptOptimizationProvenance;
import com.javaclaw.api.PromptOptimizationRef;
import com.javaclaw.api.PromptOptimizationResult;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.PromptOptimizationRpcContracts;
import com.javaclaw.server.persistence.AgentProfileService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.PromptOptimizationRecord;
import com.javaclaw.server.persistence.PromptOptimizationRepository;
import com.javaclaw.server.persistence.TurnStartRequest;

/**
 * 通过普通 Thin Harness Turn 生成、投影和人工采纳 Agent Profile Prompt 草稿。
 *
 * <p>实现说明：管理表只保存 Thread/Turn 引用和优化说明 provenance。运行状态及正文始终来自 Core Turn/Item；启动 RPC 只提交虚拟线程， 不等待模型。采纳命令使用源 Profile 精确
 * revision，冲突不会删除或改写草稿。
 */
public final class PromptOptimizationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PromptOptimizationService.class);
    private static final int MAXIMUM_DRAFT_BYTES = 65_536;

    private final CoreCommandService core;
    private final AgentProfileService profiles;
    private final PromptOptimizationRepository records;
    private final TurnDispatcher turns;
    private final PromptOptimizationInstruction instruction;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建 Prompt 优化用例。
     *
     * @param core Core Thread、Turn 与 Item 服务
     * @param profiles Agent Profile 版本服务
     * @param records 最小管理关联 Repository
     * @param turns 普通非阻塞 Turn 调度端口
     * @param instruction 已审阅内置优化说明
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public PromptOptimizationService(
            CoreCommandService core,
            AgentProfileService profiles,
            PromptOptimizationRepository records,
            TurnDispatcher turns,
            PromptOptimizationInstruction instruction,
            CanonicalJson json,
            Clock clock) {
        this.core = Objects.requireNonNull(core, "core");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.records = Objects.requireNonNull(records, "records");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.instruction = Objects.requireNonNull(instruction, "instruction");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 幂等创建并异步提交一个普通 Harness Turn。
     *
     * @param identity 启动命令身份，expected revision 必须为 0
     * @param request Workspace、源 Profile 与显式计费确认
     * @return QUEUED、RUNNING 或重试时的最新投影
     */
    public synchronized PromptOptimizationDraft start(
            CommandIdentity identity, PromptOptimizationRpcContracts.StartPayload request) {
        CommandIdentity checkedIdentity = Objects.requireNonNull(identity, "identity");
        PromptOptimizationRpcContracts.StartPayload checkedRequest = requireBillingConfirmation(request);
        Optional<PromptOptimizationRecord> recovered = records.recoverStart(checkedIdentity);
        if (recovered.isPresent()) {
            PromptOptimizationRecord record = recovered.orElseThrow();
            dispatchIfQueued(record);
            return project(record);
        }
        PromptOptimizationRecord created = createRecord(checkedIdentity, checkedRequest);
        PromptOptimizationRecord stored = records.recordStart(checkedIdentity, created);
        dispatchIfQueued(stored);
        return project(stored);
    }

    /**
     * 读取草稿最新投影。
     *
     * @param id 优化任务标识
     * @return 权威 Turn/Item 投影
     */
    public PromptOptimizationDraft read(PromptOptimizationId id) {
        return project(records.require(id));
    }

    /**
     * 列出 Workspace 的草稿投影。
     *
     * @param workspaceId Workspace
     * @return 创建时间倒序目录
     */
    public List<PromptOptimizationDraft> list(WorkspaceId workspaceId) {
        return records.list(workspaceId).stream().map(this::project).toList();
    }

    /**
     * 持久化取消请求并发布给活动 Harness。
     *
     * @param identity expected revision 必须匹配当前 Turn
     * @param id 优化任务标识
     * @param reason 脱敏原因
     * @return 取消后的最新投影
     */
    public PromptOptimizationDraft cancel(CommandIdentity identity, PromptOptimizationId id, String reason) {
        PromptOptimizationRecord record = records.require(id);
        AgentTurn current = requireTurn(record);
        if (terminal(current.status())) {
            throw PersistenceException.invalidRequest("只有活动 Prompt 优化任务可以取消");
        }
        AgentTurn cancelled = core.requestTurnCancellation(identity, current.id(), reason);
        turns.cancel(cancelled.id(), reason);
        return project(record);
    }

    /**
     * 使用 READY 草稿创建新的 Agent Profile revision；绝不自动采纳。
     *
     * @param identity expected revision 必须等于源 Profile revision
     * @param request 草稿与显式人工确认
     * @return 新 Profile 和保留的草稿快照
     */
    public PromptOptimizationAdoption adopt(
            CommandIdentity identity, PromptOptimizationRpcContracts.AdoptPayload request) {
        PromptOptimizationRpcContracts.AdoptPayload checkedRequest = requireAdoptionConfirmation(request);
        PromptOptimizationRecord record = records.require(checkedRequest.draftId());
        requireSourceRevision(identity, record);
        PromptOptimizationDraft draft = project(record);
        String content = draft.result()
                .content()
                .orElseThrow(() -> PersistenceException.invalidRequest("只有 READY Prompt 草稿可以采纳"));
        AgentProfile source = profiles.require(
                record.ref().sourceProfile().id(), record.ref().sourceProfile().revision());
        AgentProfileSpec updatedSpec = withSystemInstruction(source.spec(), content);
        AgentProfile updated = profiles.update(identity, source.id(), updatedSpec, source.lifecycle());
        PromptOptimizationRecord adopted = records.markAdopted(record.ref().id(), updated.revision());
        return new PromptOptimizationAdoption(project(adopted), updated);
    }

    /** 在 App Server 启动时重新提交已经持久化但尚未运行的 QUEUED Turn。 */
    public void resumeQueued() {
        records.listQueued().forEach(record -> {
            try {
                dispatchIfQueued(record);
            } catch (RuntimeException failure) {
                LOGGER.error(
                        "Prompt optimization {} resume failed", record.ref().id(), failure);
            }
        });
    }

    private PromptOptimizationRecord createRecord(
            CommandIdentity identity, PromptOptimizationRpcContracts.StartPayload request) {
        AgentProfile source =
                profiles.require(request.profile().id(), request.profile().revision());
        String title = "优化 Agent Profile：" + source.spec().displayName();
        DerivedStart derived = derived(identity, request, title);
        com.javaclaw.api.ConversationThread thread = core.createThread(
                derived.threadIdentity(),
                request.workspaceId(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                title);
        String message = instruction.message(source.id(), source.revision());
        CoreRpcContracts.TurnStartPayload payload =
                new CoreRpcContracts.TurnStartPayload(thread.id(), Optional.of(request.profile()), message);
        CorePayloads.Message item = new CorePayloads.Message(MessageRole.USER, message, List.of(), Optional.empty());
        TurnStartRequest startRequest = turns.resolve(payload, item);
        AgentTurn turn = core.startTurn(turnIdentity(derived.parentKey(), payload), startRequest);
        PromptOptimizationRef ref = new PromptOptimizationRef(
                PromptOptimizationId.random(), request.workspaceId(), request.profile(), thread.id(), turn.id());
        return new PromptOptimizationRecord(
                ref,
                PromptOptimizationInstruction.REVISION,
                instruction.digest(),
                Optional.empty(),
                Optional.empty(),
                Instant.now(clock));
    }

    private void dispatchIfQueued(PromptOptimizationRecord record) {
        AgentTurn turn = requireTurn(record);
        if (turn.status() != TurnStatus.QUEUED) {
            return;
        }
        requireCurrentInstruction(record);
        String message = instruction.message(
                record.ref().sourceProfile().id(), record.ref().sourceProfile().revision());
        CoreRpcContracts.TurnStartPayload payload = new CoreRpcContracts.TurnStartPayload(
                record.ref().threadId(), Optional.of(record.ref().sourceProfile()), message);
        turns.dispatch(turn, payload);
    }

    private PromptOptimizationDraft project(PromptOptimizationRecord record) {
        AgentTurn turn = requireTurn(record);
        PromptOptimizationResult result = result(turn, record.ref());
        Instant updatedAt = record.createdAt();
        if (turn.updatedAt().isAfter(updatedAt)) {
            updatedAt = turn.updatedAt();
        }
        Instant adoptedAt = record.adoptedAt().orElse(record.createdAt());
        if (adoptedAt.isAfter(updatedAt)) {
            updatedAt = adoptedAt;
        }
        PromptOptimizationProvenance provenance = new PromptOptimizationProvenance(
                record.instructionRevision(), record.instructionDigest(), record.createdAt(), updatedAt);
        Optional<AgentProfileRef> adopted = record.adoptedProfileRevision()
                .map(revision ->
                        new AgentProfileRef(record.ref().sourceProfile().id(), revision));
        return new PromptOptimizationDraft(record.ref(), result, provenance, adopted);
    }

    private PromptOptimizationResult result(AgentTurn turn, PromptOptimizationRef ref) {
        return switch (turn.status()) {
            case QUEUED -> pending(PromptOptimizationState.QUEUED, turn);
            case RUNNING, WAITING -> pending(PromptOptimizationState.RUNNING, turn);
            case CANCELLED -> pending(PromptOptimizationState.CANCELLED, turn);
            case FAILED -> failed(turn, turn.errorCode().orElse("PROMPT_OPTIMIZATION_FAILED"));
            case COMPLETED -> completed(turn, ref);
        };
    }

    private PromptOptimizationResult completed(AgentTurn turn, PromptOptimizationRef ref) {
        Optional<String> content = finalAssistant(ref);
        if (content.isEmpty()) {
            return failed(turn, "PROMPT_OPTIMIZATION_EMPTY");
        }
        String value = content.orElseThrow();
        if (value.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_DRAFT_BYTES) {
            return failed(turn, "PROMPT_OPTIMIZATION_TOO_LARGE");
        }
        return new PromptOptimizationResult(
                PromptOptimizationState.READY,
                turn.revision(),
                Optional.of(value),
                Optional.of(digest(value)),
                Optional.empty());
    }

    private Optional<String> finalAssistant(PromptOptimizationRef ref) {
        return core.listItems(ref.threadId()).stream()
                .filter(item -> item.turnId().equals(ref.turnId()))
                .filter(item -> CoreSchemas.MESSAGE.equals(item.schemaId()))
                .sorted(Comparator.comparingLong(ItemEnvelope::sequence).reversed())
                .map(item -> json.decode(item.payload(), CorePayloads.Message.class))
                .filter(message -> message.role() == MessageRole.ASSISTANT)
                .map(CorePayloads.Message::text)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    private static PromptOptimizationResult pending(PromptOptimizationState state, AgentTurn turn) {
        return new PromptOptimizationResult(
                state, turn.revision(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static PromptOptimizationResult failed(AgentTurn turn, String errorCode) {
        return new PromptOptimizationResult(
                PromptOptimizationState.FAILED,
                turn.revision(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(errorCode));
    }

    private DerivedStart derived(
            CommandIdentity identity, PromptOptimizationRpcContracts.StartPayload request, String title) {
        String threadKey = derivedKey("thread", identity.idempotencyKey());
        ThreadFingerprint threadFingerprint =
                new ThreadFingerprint(request.workspaceId(), request.profile(), title, instruction.digest());
        CommandIdentity threadIdentity = new CommandIdentity(
                "profile/prompt/optimization/thread",
                threadKey,
                0,
                json.encode(threadFingerprint).sha256());
        return new DerivedStart(threadIdentity, identity.idempotencyKey());
    }

    private CommandIdentity turnIdentity(String parentKey, CoreRpcContracts.TurnStartPayload payload) {
        return new CommandIdentity(
                "profile/prompt/optimization/turn",
                derivedKey("turn", parentKey),
                0,
                json.encode(payload).sha256());
    }

    private static String derivedKey(String role, String sourceKey) {
        return "prompt-opt-" + role + ":" + digest(sourceKey);
    }

    private void requireCurrentInstruction(PromptOptimizationRecord record) {
        boolean matches = PromptOptimizationInstruction.REVISION.equals(record.instructionRevision())
                && instruction.digest().equals(record.instructionDigest());
        if (!matches) {
            throw new IllegalStateException("Prompt optimization instruction revision is unavailable");
        }
    }

    private static PromptOptimizationRpcContracts.StartPayload requireBillingConfirmation(
            PromptOptimizationRpcContracts.StartPayload request) {
        PromptOptimizationRpcContracts.StartPayload checked = Objects.requireNonNull(request, "request");
        if (!checked.billingConfirmed()
                || !PromptOptimizationRpcContracts.BILLING_CONFIRMATION.equals(checked.confirmation())) {
            throw PersistenceException.invalidRequest("必须显式确认 Prompt 优化可能产生 Provider 费用");
        }
        return checked;
    }

    private static PromptOptimizationRpcContracts.AdoptPayload requireAdoptionConfirmation(
            PromptOptimizationRpcContracts.AdoptPayload request) {
        PromptOptimizationRpcContracts.AdoptPayload checked = Objects.requireNonNull(request, "request");
        if (!checked.adoptionConfirmed()
                || !PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION.equals(checked.confirmation())) {
            throw PersistenceException.invalidRequest("必须显式确认人工采纳 Prompt 草稿");
        }
        return checked;
    }

    private static void requireSourceRevision(CommandIdentity identity, PromptOptimizationRecord record) {
        if (Objects.requireNonNull(identity, "identity").expectedRevision()
                != record.ref().sourceProfile().revision()) {
            throw PersistenceException.revisionConflict("采纳必须使用源 Agent Profile 精确 revision");
        }
    }

    private AgentTurn requireTurn(PromptOptimizationRecord record) {
        return core.findTurn(record.ref().turnId())
                .orElseThrow(() -> new IllegalStateException("Prompt 优化关联的 Turn 不存在"));
    }

    private static AgentProfileSpec withSystemInstruction(AgentProfileSpec source, String instruction) {
        return new AgentProfileSpec(
                source.displayName(),
                instruction,
                source.provider(),
                source.permissionProfile(),
                source.visibleTools(),
                source.budget());
    }

    private static boolean terminal(TurnStatus status) {
        return status == TurnStatus.COMPLETED || status == TurnStatus.CANCELLED || status == TurnStatus.FAILED;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record ThreadFingerprint(
            WorkspaceId workspaceId, AgentProfileRef profile, String title, String instructionDigest) {}

    private record DerivedStart(CommandIdentity threadIdentity, String parentKey) {}
}
