package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DecodedItemPayload;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.EncodedItemPayload;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemPayload;
import com.javaclaw.api.ItemSchemaRegistry;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.ProviderState;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnExecutionPhase;
import com.javaclaw.runtime.TurnJournal;
import com.javaclaw.runtime.TurnRecoverySnapshot;
import com.javaclaw.runtime.TurnToolBatch;
import com.javaclaw.runtime.TurnVisibleTools;

/**
 * Thin Harness 的 H2 事务日志。
 *
 * <p>ToolResult Item 与 EffectReceipt 在同一事务提交；进程崩溃后只能恢复身份和摘要完全一致的副作用。
 */
public final class H2TurnJournal implements TurnJournal {
    private final H2Transactions transactions;
    private final TurnCompactionJournal compactions;
    private final TurnRepository turns = new TurnRepository();
    private final ItemRepository items = new ItemRepository(turns);
    private final EffectReceiptRepository effects = new EffectReceiptRepository();
    private final ProviderStateRepository providerStates = new ProviderStateRepository();
    private final TurnExecutionCheckpointRepository checkpoints;
    private final ItemSchemaRegistry schemas;
    private final CanonicalJson json;
    private final Clock clock;
    private final LiveTurnBudgets budgets;
    private final TurnStreamService streams;
    private final TurnStreamJournal streamJournal;

    /**
     * 创建事务日志。
     *
     * @param database data-v6 数据库
     * @param schemas Core 与已启用扩展的 Item codec
     * @param json 共享规范 JSON codec
     * @param clock 平台时钟
     */
    public H2TurnJournal(H2Database database, ItemSchemaRegistry schemas, CanonicalJson json, Clock clock) {
        this(database, schemas, json, clock, new LiveTurnBudgets());
    }

    /**
     * 创建与子任务共享预算注册表的事务日志。
     *
     * @param database data-v6 数据库
     * @param schemas 已启用 Item codec
     * @param json 规范 JSON
     * @param clock 平台时钟
     * @param budgets 组合根拥有的活动预算注册表
     */
    public H2TurnJournal(
            H2Database database, ItemSchemaRegistry schemas, CanonicalJson json, Clock clock, LiveTurnBudgets budgets) {
        this(database, schemas, json, clock, budgets, new TurnStreamService(database, json));
    }

    /**
     * 创建共享公开流提交唤醒的事务日志。
     *
     * @param database 数据库
     * @param schemas Item codec
     * @param json JSON codec
     * @param clock 时钟
     * @param budgets 活动预算
     * @param streams 组合根共享流服务
     */
    public H2TurnJournal(
            H2Database database,
            ItemSchemaRegistry schemas,
            CanonicalJson json,
            Clock clock,
            LiveTurnBudgets budgets,
            TurnStreamService streams) {
        this.streams = Objects.requireNonNull(streams, "streams");
        streamJournal = new TurnStreamJournal(json);
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        compactions = new TurnCompactionJournal(database, json, clock, schemas);
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.json = Objects.requireNonNull(json, "json");
        checkpoints = new TurnExecutionCheckpointRepository(json);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public com.javaclaw.runtime.CompactionTicket recordCompactionIntent(
            com.javaclaw.runtime.CompactionRequest request, boolean nativeCall) {
        return compactions.intent(request, nativeCall);
    }

    @Override
    public void commitCompaction(
            com.javaclaw.runtime.CompactionRequest request,
            com.javaclaw.runtime.CompactionTicket ticket,
            com.javaclaw.runtime.CompactionOutcome outcome,
            ModelUsage cumulativeUsage) {
        compactions.commit(request, ticket, outcome, cumulativeUsage);
    }

    @Override
    public void activateBudget(TurnId turnId, com.javaclaw.runtime.BudgetAccount budget) {
        var reservations =
                execute(connection -> new ChildTurnReservationRepository(json).reservations(connection, turnId));
        budgets.activate(turnId, budget, reservations);
    }

    @Override
    public void deactivateBudget(TurnId turnId, com.javaclaw.runtime.BudgetAccount budget) {
        budgets.deactivate(turnId, budget);
    }

    @Override
    public TurnRecoverySnapshot beginOrRecover(TurnExecutionCommand command) {
        Objects.requireNonNull(command, "command");
        return execute(connection -> {
            com.javaclaw.api.AgentTurn current =
                    turns.find(connection, command.turn().id()).orElseThrow(() -> new PersistenceException("Turn 不存在"));
            if (current.status() == TurnStatus.QUEUED) {
                turns.transition(
                        connection, current.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty(), now());
                checkpoints.insert(connection, current.id(), now());
            } else if (current.status() != TurnStatus.RUNNING) {
                throw new PersistenceException("Turn 当前状态不能执行 Harness: " + current.status());
            }
            TurnCompactionJournal.recoverLocal(connection, current.id());
            TurnExecutionCheckpointRepository.StoredCheckpoint checkpoint = checkpoints
                    .find(connection, current.id())
                    .orElseThrow(() -> new PersistenceException("RUNNING Turn 缺少执行 checkpoint"));
            return snapshot(connection, current.id(), checkpoint);
        });
    }

    @Override
    public Optional<TurnRecoverySnapshot> findRecovery(TurnId turnId) {
        Objects.requireNonNull(turnId, "turnId");
        return execute(connection -> {
            Optional<TurnExecutionCheckpointRepository.StoredCheckpoint> checkpoint =
                    checkpoints.find(connection, turnId);
            if (checkpoint.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(snapshot(connection, turnId, checkpoint.orElseThrow()));
        });
    }

    @Override
    public TurnRecoverySnapshot readRecovery(TurnId turnId) {
        return findRecovery(turnId).orElseThrow(() -> new PersistenceException("Turn 缺少执行 checkpoint"));
    }

    @Override
    public void recordModelIntent(TurnId turnId, int invocationNumber, String intentDigest) {
        if (invocationNumber < 1) {
            throw new IllegalArgumentException("invocationNumber must be positive");
        }
        execute(connection -> {
            streamJournal.lock(connection, turnId);
            TurnCompactionJournal.requireNoActive(connection, turnId);
            checkpoints.recordModelIntent(connection, turnId, invocationNumber, intentDigest, now());
            streamJournal.intent(connection, turnId, invocationNumber, now());
            return null;
        });
        streams.committed(turnId);
    }

    @Override
    public void commitModelResult(
            TurnId turnId, int invocationNumber, ModelInvocationResult result, ModelUsage cumulativeUsage) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(cumulativeUsage, "cumulativeUsage");
        execute(connection -> {
            streamJournal.lock(connection, turnId);
            TurnCompactionJournal.requireNoActive(connection, turnId);
            Instant committedAt = now();
            var messageId = streamJournal.messageId(connection, turnId, invocationNumber);
            var assistant = appendAssistant(connection, turnId, result, committedAt, messageId);
            appendCalls(connection, turnId, result.toolCalls(), committedAt);
            result.providerState()
                    .ifPresent(state -> saveProviderState(
                            connection,
                            turnId,
                            state,
                            Math.addExact(
                                    result.usage().inputTokens(), result.usage().generatedTokens()),
                            committedAt));
            checkpoints.commitModelResult(
                    connection,
                    turnId,
                    invocationNumber,
                    cumulativeUsage,
                    new TurnToolBatch(result.toolCalls()),
                    committedAt);
            streamJournal.committed(connection, turnId, assistant, committedAt);
            return null;
        });
        streams.committed(turnId);
    }

    @Override
    public void recordToolIntent(
            TurnId turnId, int toolIndex, ToolCallRequest request, int consumedToolCalls, String intentDigest) {
        Objects.requireNonNull(request, "request");
        execute(connection -> {
            requirePendingCall(connection, turnId, toolIndex, request);
            checkpoints.recordToolIntent(connection, turnId, toolIndex, consumedToolCalls, intentDigest, now());
            return null;
        });
    }

    @Override
    public void commitToolResult(
            TurnId turnId,
            int toolIndex,
            ToolCallRequest request,
            ToolExecutionOutcome outcome,
            List<ToolIdentity> visibleTools) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outcome, "outcome");
        execute(connection -> {
            TurnExecutionCheckpointRepository.StoredCheckpoint checkpoint =
                    requirePendingCall(connection, turnId, toolIndex, request);
            Instant committedAt = now();
            ToolCallResult result = outcome.result();
            if (!result.callId().equals(request.callId())) {
                throw new PersistenceException("ToolResult 与已提交工具调用不匹配");
            }
            CorePayloads.ToolResult item =
                    new CorePayloads.ToolResult(result.callId(), result.success(), result.output(), result.receipt());
            for (var fact : outcome.facts()) {
                appendItem(connection, turnId, fact.kind(), fact.schemaId(), fact.payload(), committedAt);
            }
            appendTerminalFacts(connection, turnId, committedAt);
            appendItem(connection, turnId, "tool-result", CoreSchemas.TOOL_RESULT, item, committedAt);
            if (result.receipt().isPresent()) {
                appendItem(
                        connection,
                        turnId,
                        "effect-receipt",
                        CoreSchemas.EFFECT_RECEIPT,
                        result.receipt().orElseThrow(),
                        committedAt);
            }
            CodingOperationRepository.markJournaled(connection, turnId, request.callId(), result.output());
            checkpoints.commitToolResult(
                    connection,
                    turnId,
                    toolIndex,
                    checkpoint.toolBatch().calls().size(),
                    new TurnVisibleTools(visibleTools),
                    committedAt);
            return null;
        });
    }

    @Override
    public void transition(TurnId turnId, TurnStatus expected, TurnStatus next, Optional<String> errorCode) {
        execute(connection -> {
            streamJournal.lock(connection, turnId);
            appendTerminalFacts(connection, turnId, now());
            turns.transition(connection, turnId, expected, next, errorCode, now());
            return null;
        });
        streams.committed(turnId);
    }

    private void appendTerminalFacts(java.sql.Connection connection, TurnId turnId, Instant instant)
            throws java.sql.SQLException {
        for (var fact : CodingTerminalRepository.drainFinalFacts(connection, turnId, json)) {
            appendItem(connection, turnId, "command", CoreSchemas.COMMAND, fact, instant);
        }
    }

    @Override
    public void append(TurnId turnId, String kind, String schemaId, ItemPayload payload, ItemStatus status) {
        EncodedItemPayload encoded = schemas.encode(payload);
        if (!encoded.schemaId().equals(schemaId)) {
            throw new IllegalArgumentException("Item schemaId 与 payload codec 不一致");
        }
        execute(connection -> {
            Instant committedAt = now();
            ItemRepository.ItemWrite write = new ItemRepository.ItemWrite(
                    turnId, kind, schemaId, "core", status, encoded.payload(), committedAt);
            items.append(connection, write);
            if (payload instanceof CorePayloads.ToolResult result
                    && result.receipt().isPresent()) {
                persistEffect(connection, turnId, result, result.receipt().orElseThrow(), committedAt);
            }
            return null;
        });
    }

    @Override
    public void saveProviderState(TurnId turnId, String modelId, ProviderState state, long estimatedInputTokens) {
        if (estimatedInputTokens < 0) {
            throw new IllegalArgumentException("estimatedInputTokens must not be negative");
        }
        execute(connection -> {
            providerStates.save(connection, turnId, modelId, state, estimatedInputTokens, now());
            return null;
        });
    }

    @Override
    public Optional<ToolCallResult> recoverEffect(ToolCallRequest request) {
        return execute(connection ->
                effects.find(connection, request.idempotencyKey()).map(stored -> recover(request, stored)));
    }

    private TurnRecoverySnapshot snapshot(
            java.sql.Connection connection,
            TurnId turnId,
            TurnExecutionCheckpointRepository.StoredCheckpoint checkpoint)
            throws java.sql.SQLException {
        String assistant = items.listByTurnAndSchema(connection, turnId, CoreSchemas.MESSAGE).stream()
                .map(this::decodeMessage)
                .filter(message -> message.role() == com.javaclaw.api.MessageRole.ASSISTANT)
                .map(CorePayloads.Message::text)
                .collect(java.util.stream.Collectors.joining());
        Set<String> callIds = new LinkedHashSet<>();
        for (ItemEnvelope item : items.listByTurnAndSchema(connection, turnId, CoreSchemas.TOOL_CALL)) {
            callIds.add(decodeCall(item).callId());
        }
        return new TurnRecoverySnapshot(
                checkpoint.phase(),
                checkpoint.usage(),
                checkpoint.toolCalls(),
                checkpoint.modelInvocations(),
                checkpoint.toolBatch(),
                checkpoint.nextToolIndex(),
                checkpoint.visibleTools(),
                callIds,
                assistant,
                checkpoint.activeIntentDigest());
    }

    private Optional<com.javaclaw.api.ItemEnvelope> appendAssistant(
            java.sql.Connection connection,
            TurnId turnId,
            ModelInvocationResult result,
            Instant committedAt,
            com.javaclaw.api.ItemId messageId)
            throws java.sql.SQLException {
        if (result.text().isEmpty()) {
            return Optional.empty();
        }
        CorePayloads.Message message = new CorePayloads.Message(
                com.javaclaw.api.MessageRole.ASSISTANT, result.text(), List.of(), Optional.empty());
        EncodedItemPayload encoded = schemas.encode(message);
        var write = new ItemRepository.ItemWrite(
                turnId, "message", CoreSchemas.MESSAGE, "core", ItemStatus.COMPLETED, encoded.payload(), committedAt);
        return Optional.of(items.append(connection, write, messageId));
    }

    private void appendCalls(
            java.sql.Connection connection, TurnId turnId, List<ModelToolCall> calls, Instant committedAt)
            throws java.sql.SQLException {
        for (ModelToolCall call : calls) {
            CorePayloads.ToolCall item = new CorePayloads.ToolCall(
                    call.callId(),
                    call.tool().producerId(),
                    call.tool().name(),
                    call.tool().revision(),
                    call.arguments());
            appendItem(connection, turnId, "tool-call", CoreSchemas.TOOL_CALL, item, committedAt);
        }
    }

    private void appendItem(
            java.sql.Connection connection,
            TurnId turnId,
            String kind,
            String schemaId,
            ItemPayload payload,
            Instant committedAt)
            throws java.sql.SQLException {
        EncodedItemPayload encoded = schemas.encode(payload);
        if (!encoded.schemaId().equals(schemaId)) {
            throw new PersistenceException("Item schemaId 与 payload codec 不一致");
        }
        ItemRepository.ItemWrite write = new ItemRepository.ItemWrite(
                turnId, kind, schemaId, "core", ItemStatus.COMPLETED, encoded.payload(), committedAt);
        items.append(connection, write);
        if (payload instanceof CorePayloads.ToolResult result
                && result.receipt().isPresent()) {
            persistEffect(connection, turnId, result, result.receipt().orElseThrow(), committedAt);
        }
    }

    private void saveProviderState(
            java.sql.Connection connection,
            TurnId turnId,
            ProviderState state,
            long estimatedInputTokens,
            Instant committedAt) {
        try {
            String modelId = turns.find(connection, turnId)
                    .orElseThrow(() -> new PersistenceException("Turn 不存在"))
                    .provider()
                    .routeKey();
            providerStates.save(connection, turnId, modelId, state, estimatedInputTokens, committedAt);
        } catch (java.sql.SQLException failure) {
            throw new PersistenceException("Provider state 提交失败", failure);
        }
    }

    private TurnExecutionCheckpointRepository.StoredCheckpoint requirePendingCall(
            java.sql.Connection connection, TurnId turnId, int toolIndex, ToolCallRequest request)
            throws java.sql.SQLException {
        TurnExecutionCheckpointRepository.StoredCheckpoint checkpoint = checkpoints
                .find(connection, turnId)
                .orElseThrow(() -> new PersistenceException("Turn 缺少执行 checkpoint"));
        boolean phaseMatches = checkpoint.phase() == TurnExecutionPhase.TOOLS_READY
                || checkpoint.phase() == TurnExecutionPhase.TOOL_APPROVAL_RESOLVED
                || checkpoint.phase() == TurnExecutionPhase.TOOL_IN_FLIGHT;
        if (!phaseMatches || checkpoint.nextToolIndex() != toolIndex) {
            throw PersistenceException.revisionConflict("工具调用恢复位置已经改变");
        }
        ModelToolCall call = checkpoint.toolBatch().calls().get(toolIndex);
        boolean identityMatches = request.turnId().equals(turnId)
                && request.callId().equals(call.callId())
                && request.tool().equals(call.tool())
                && request.arguments().equals(call.arguments());
        if (!identityMatches) {
            throw PersistenceException.idempotencyConflict("工具调用与已提交模型结果不一致");
        }
        return checkpoint;
    }

    private CorePayloads.Message decodeMessage(ItemEnvelope item) {
        DecodedItemPayload decoded = schemas.decode(item.schemaId(), item.payload());
        if (decoded instanceof DecodedItemPayload.Known known && known.value() instanceof CorePayloads.Message value) {
            return value;
        }
        throw new PersistenceException("已持久化 Message 无法按当前 Core schema 解码");
    }

    private void persistEffect(
            java.sql.Connection connection,
            TurnId turnId,
            CorePayloads.ToolResult result,
            EffectReceipt receipt,
            Instant now)
            throws java.sql.SQLException {
        CorePayloads.ToolCall call = findCall(connection, turnId, result.callId());
        validateReceipt(call, result, receipt);
        ToolCallResult storedResult =
                new ToolCallResult(result.callId(), result.success(), result.output(), result.receipt());
        Optional<EffectReceiptRepository.StoredEffect> existing = effects.find(connection, receipt.idempotencyKey());
        if (existing.isPresent()) {
            validateStored(call, result, receipt, turnId, existing.orElseThrow());
            return;
        }
        effects.insert(connection, turnId, call, receipt, json.encode(storedResult), now);
    }

    private CorePayloads.ToolCall findCall(java.sql.Connection connection, TurnId turnId, String callId)
            throws java.sql.SQLException {
        List<ItemEnvelope> candidates = items.listByTurnAndSchema(connection, turnId, CoreSchemas.TOOL_CALL);
        return candidates.stream()
                .map(this::decodeCall)
                .filter(call -> call.callId().equals(callId))
                .findFirst()
                .orElseThrow(() -> new PersistenceException("EffectReceipt 缺少对应 ToolCall"));
    }

    private CorePayloads.ToolCall decodeCall(ItemEnvelope item) {
        DecodedItemPayload decoded = schemas.decode(item.schemaId(), item.payload());
        if (decoded instanceof DecodedItemPayload.Known known && known.value() instanceof CorePayloads.ToolCall call) {
            return call;
        }
        throw new PersistenceException("已持久化 ToolCall 无法按当前 Core schema 解码");
    }

    private ToolCallResult recover(ToolCallRequest request, EffectReceiptRepository.StoredEffect stored) {
        boolean identityMatches = stored.turnId().equals(request.turnId())
                && stored.callId().equals(request.callId())
                && stored.producerId().equals(request.tool().producerId())
                && stored.toolName().equals(request.tool().name())
                && stored.toolRevision() == request.tool().revision()
                && stored.requestDigest().equals(request.arguments().sha256());
        if (!identityMatches) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同工具请求使用");
        }
        ToolCallResult result = json.decode(stored.resultPayload(), ToolCallResult.class);
        validateRecovered(request, stored, result);
        return result;
    }

    private static void validateReceipt(
            CorePayloads.ToolCall call, CorePayloads.ToolResult result, EffectReceipt receipt) {
        boolean valid = call.callId().equals(result.callId())
                && call.toolName().equals(receipt.toolName())
                && call.arguments().sha256().equals(receipt.requestDigest())
                && result.output().sha256().equals(receipt.resultDigest());
        if (!valid) {
            throw new PersistenceException("EffectReceipt 与 ToolCall/ToolResult 不一致");
        }
    }

    private static void validateStored(
            CorePayloads.ToolCall call,
            CorePayloads.ToolResult result,
            EffectReceipt receipt,
            TurnId turnId,
            EffectReceiptRepository.StoredEffect stored) {
        boolean same = stored.turnId().equals(turnId)
                && stored.callId().equals(call.callId())
                && stored.producerId().equals(call.producerId())
                && stored.toolName().equals(call.toolName())
                && stored.toolRevision() == call.toolRevision()
                && stored.requestDigest().equals(receipt.requestDigest())
                && stored.resultDigest().equals(result.output().sha256());
        if (!same) {
            throw PersistenceException.idempotencyConflict("幂等键已绑定其他副作用");
        }
    }

    private static void validateRecovered(
            ToolCallRequest request, EffectReceiptRepository.StoredEffect stored, ToolCallResult result) {
        Optional<EffectReceipt> receipt = result.receipt();
        boolean valid = result.callId().equals(request.callId())
                && result.output().sha256().equals(stored.resultDigest())
                && receipt.isPresent()
                && receipt.orElseThrow().idempotencyKey().equals(stored.idempotencyKey())
                && receipt.orElseThrow().requestDigest().equals(stored.requestDigest())
                && receipt.orElseThrow().resultDigest().equals(stored.resultDigest());
        if (!valid) {
            throw new PersistenceException("已保存副作用结果完整性校验失败");
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Turn 日志事务失败", failure);
        }
    }
}
