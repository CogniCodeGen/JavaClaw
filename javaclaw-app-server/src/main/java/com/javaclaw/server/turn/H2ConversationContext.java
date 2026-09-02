package com.javaclaw.server.turn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.DecodedItemPayload;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemPayload;
import com.javaclaw.api.ItemSchemaRegistry;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.runtime.ContextAssembler;
import com.javaclaw.runtime.ConversationWindow;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderStateService;

/** 从 data-v5 Item 与 Provider state 重建模型窗口。 */
public final class H2ConversationContext implements ContextAssembler {
    private final CoreCommandService core;
    private final ProviderStateService providerStates;
    private final ItemSchemaRegistry schemas;

    /**
     * 创建上下文组装器。
     *
     * @param core Core 查询服务
     * @param providerStates opaque state 查询服务
     * @param schemas Core 与 Extension codec 快照
     */
    public H2ConversationContext(
            CoreCommandService core, ProviderStateService providerStates, ItemSchemaRegistry schemas) {
        this.core = Objects.requireNonNull(core, "core");
        this.providerStates = Objects.requireNonNull(providerStates, "providerStates");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
    }

    @Override
    public ConversationWindow assemble(TurnExecutionCommand command, CancellationToken cancellation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        List<ItemEnvelope> allItems = core.listItems(command.turn().threadId());
        verifyCurrentInput(command, allItems);
        Optional<ProviderStateService.StateSnapshot> state =
                providerStates.latest(command.turn().threadId(), command.modelRoute());
        long afterSequence =
                state.map(ProviderStateService.StateSnapshot::throughSequence).orElse(0L);
        List<ModelMessage> messages = convert(allItems, afterSequence, cancellation);
        long estimate = state.map(ProviderStateService.StateSnapshot::estimatedInputTokens)
                .orElseGet(() -> estimate(command.systemInstruction()));
        estimate = Math.addExact(estimate, estimate(messages));
        return new ConversationWindow(messages, state.map(ProviderStateService.StateSnapshot::state), estimate);
    }

    private List<ModelMessage> convert(List<ItemEnvelope> items, long afterSequence, CancellationToken cancellation) {
        List<ModelMessage> messages = new ArrayList<>();
        List<ModelToolCall> pendingCalls = new ArrayList<>();
        Map<String, CorePayloads.ToolCall> calls = new LinkedHashMap<>();
        for (ItemEnvelope item : items) {
            cancellation.throwIfCancelled();
            if (item.sequence() <= afterSequence || item.status() != ItemStatus.COMPLETED) {
                continue;
            }
            ItemPayload payload = knownPayload(item);
            if (payload instanceof CorePayloads.ToolCall call) {
                rememberCall(call, calls, pendingCalls);
            } else {
                flushCalls(messages, pendingCalls);
                appendPayload(messages, calls, payload);
            }
        }
        flushCalls(messages, pendingCalls);
        return List.copyOf(messages);
    }

    private ItemPayload knownPayload(ItemEnvelope item) {
        DecodedItemPayload decoded = schemas.decode(item.schemaId(), item.payload());
        if (decoded instanceof DecodedItemPayload.Known known) {
            return known.value();
        }
        return new IgnoredPayload();
    }

    private static void rememberCall(
            CorePayloads.ToolCall call, Map<String, CorePayloads.ToolCall> calls, List<ModelToolCall> pendingCalls) {
        if (calls.putIfAbsent(call.callId(), call) != null) {
            throw new PersistenceException("Thread 中存在重复 ToolCall ID");
        }
        ToolIdentity identity = new ToolIdentity(call.producerId(), call.toolName(), call.toolRevision());
        pendingCalls.add(new ModelToolCall(call.callId(), identity, call.arguments()));
    }

    private static void appendPayload(
            List<ModelMessage> messages, Map<String, CorePayloads.ToolCall> calls, ItemPayload payload) {
        if (payload instanceof CorePayloads.Message message) {
            appendMessage(messages, calls, message);
        } else if (payload instanceof CorePayloads.ToolResult result) {
            CorePayloads.ToolCall call = requireCall(calls, result.callId());
            messages.add(ModelMessage.tool(
                    result.callId(), call.toolName(), result.output().json()));
        }
    }

    private static void appendMessage(
            List<ModelMessage> messages, Map<String, CorePayloads.ToolCall> calls, CorePayloads.Message message) {
        if (message.role() == MessageRole.TOOL) {
            String callId = message.toolCallId().orElseThrow(() -> new PersistenceException("Tool 消息缺少 call ID"));
            messages.add(ModelMessage.tool(callId, requireCall(calls, callId).toolName(), message.text()));
            return;
        }
        messages.add(new ModelMessage(message.role(), message.text(), List.of(), Optional.empty(), Optional.empty()));
    }

    private static CorePayloads.ToolCall requireCall(Map<String, CorePayloads.ToolCall> calls, String callId) {
        CorePayloads.ToolCall call = calls.get(callId);
        if (call == null) {
            throw new PersistenceException("Tool 结果缺少对应调用");
        }
        return call;
    }

    private static void flushCalls(List<ModelMessage> messages, List<ModelToolCall> pendingCalls) {
        if (!pendingCalls.isEmpty()) {
            messages.add(ModelMessage.assistant("", List.copyOf(pendingCalls)));
            pendingCalls.clear();
        }
    }

    private void verifyCurrentInput(TurnExecutionCommand command, List<ItemEnvelope> items) {
        boolean found = items.stream()
                .filter(item -> item.turnId().equals(command.turn().id()))
                .map(this::knownPayload)
                .filter(CorePayloads.Message.class::isInstance)
                .map(CorePayloads.Message.class::cast)
                .anyMatch(message ->
                        message.role() == MessageRole.USER && message.text().equals(command.userMessage()));
        if (!found) {
            throw new PersistenceException("Turn 的持久用户消息与调度命令不一致");
        }
    }

    private static long estimate(List<ModelMessage> messages) {
        return messages.stream()
                .mapToLong(message ->
                        estimate(message.text()) + message.toolCalls().size() * 32L)
                .sum();
    }

    private static long estimate(String text) {
        return Math.max(1, (text.length() + 3L) / 4L);
    }

    private record IgnoredPayload() implements ItemPayload {}
}
