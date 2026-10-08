package com.javaclaw.framework.springai;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.javaclaw.framework.springai.OnDemandContextSession.digest;
import static com.javaclaw.framework.springai.OnDemandContextSession.pause;

/** Builds a fresh per-step view of durable state for both host routing and optional planning. */
final class StepContextAssembler {
    private final String scope;
    private final StepContextProjector projector;
    private final List<Message> stableInstructions;
    private final java.util.function.Supplier<Message> contractContext;

    StepContextAssembler(String scope, StepContextProjector projector) {
        this(scope, projector, null);
    }

    StepContextAssembler(String scope, StepContextProjector projector, List<Message> stableInstructions) {
        this(scope, projector, stableInstructions, null);
    }

    StepContextAssembler(String scope, StepContextProjector projector, List<Message> stableInstructions,
            java.util.function.Supplier<Message> contractContext) {
        this.scope = scope;
        this.projector = projector;
        this.stableInstructions = stableInstructions == null ? null : List.copyOf(stableInstructions);
        this.contractContext = contractContext;
    }

    List<Message> base(List<Message> incoming) {
        // Dynamic snapshots are rebuilt from host state. Their previous model projection
        // is never a source of current permissions, identifiers, or evidence.
        if (stableInstructions != null) return new ArrayList<>(stableInstructions);
        return new ArrayList<>(incoming.stream().filter(SystemMessage.class::isInstance)
                .filter(message -> !HostContextBlock.owned(message)).toList());
    }

    Message dynamic(HostContextBlock.Kind kind, Message message, boolean required, List<String> refs) {
        return block(kind.name(), kind, message, required, refs);
    }

    private Message block(String slot, HostContextBlock.Kind kind, Message message,
                          boolean required, List<String> refs) {
        var content = StepMessageCodec.message(message);
        content.remove("hostContextBlock");
        return HostContextBlock.mark(message, new HostContextBlock.Metadata(scope + "/" + slot,
                kind, digest(content.toString()), scope, required, refs));
    }

    List<Message> exchange(List<Message> messages, HostContextBlock.Kind kind,
                           boolean required, List<String> refs) {
        if (messages.isEmpty()) return List.of();
        try {
            messages = ComputerUseEvidenceProjection.project(messages, projector.toolResultCharacterLimit());
            messages = BrowserObservationProjection.project(messages, projector.toolResultCharacterLimit());
        } catch (LocalContextBudgetExceededException budget) {
            throw pause(budget.getMessage(), budget);
        }
        if (messages.size() != 2 || !(messages.getFirst() instanceof AssistantMessage assistant)
                || !(messages.getLast() instanceof ToolResponseMessage)) {
            throw new IllegalStateException("host evidence requires a complete tool exchange");
        }
        String identity = assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::id)
                .collect(java.util.stream.Collectors.joining("/"));
        return List.of(block("exchange/" + identity + "/call", kind, messages.getFirst(), required, refs),
                block("exchange/" + identity + "/result", kind, messages.getLast(), required, refs));
    }

    List<Message> selected(List<Message> messages) {
        var result = new ArrayList<Message>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()
                    && i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage) {
                result.addAll(exchange(List.of(message, messages.get(++i)),
                        HostContextBlock.Kind.TOOL_EXCHANGE, false, List.of()));
            } else {
                var content = StepMessageCodec.message(message);
                content.remove("hostContextBlock");
                boolean workflow = com.javaclaw.framework.spi.DeferredContextUse.USER_WORKFLOW.name()
                        .equals(message.getMetadata().get(OnDemandContextSession.CONTEXT_USE_METADATA));
                result.add(block("selected/" + digest(content.toString()), HostContextBlock.Kind.SELECTED_CONTEXT,
                        message, workflow, List.of()));
            }
        }
        return List.copyOf(result);
    }

    List<Message> project(List<Message> assembled, List<ToolCallback> callbacks) {
        List<Message> current = assembled;
        if (contractContext != null) {
            current = new ArrayList<>(assembled.stream()
                    .filter(message -> !TaskRepairContext.isContract(message, scope)).toList());
            Message contract = contractContext.get();
            if (contract != null) current.add(contract);
        }
        List<Message> manifested = new ArrayList<>(ProviderToolManifest.replace(current, callbacks));
        int last = manifested.size() - 1;
        manifested.set(last, dynamic(HostContextBlock.Kind.TOOL_MANIFEST, manifested.get(last), true, List.of()));
        // 最新工具交换也可能直接加入 assembled，统一在最终入口保留有界浏览器正文。
        List<Message> source = ComputerUseEvidenceProjection.compactForModel(BrowserObservationProjection.project(
                normalize(manifested), projector.toolResultCharacterLimit()));
        try {
            var projected = projector.project(source);
            projector.validateRequiredEvidence(source, projected.messages());
            return projected.messages();
        } catch (LocalContextBudgetExceededException budget) {
            throw pause(budget.getMessage(), budget);
        } catch (ContextPlanningRequiredException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw pause("invalid model context projection: "
                    + com.javaclaw.util.SensitiveDataRedactor.redactText(invalid.getMessage()), invalid);
        }
    }

    private List<Message> normalize(List<Message> messages) {
        Map<String, List<Message>> units = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            List<Message> unit = List.of(message);
            String key;
            if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()
                    && i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage) {
                unit = List.of(message, messages.get(++i));
                key = "exchange/" + assistant.getToolCalls().stream()
                        .map(call -> call.id() + ":" + call.name()).toList();
            } else {
                HostContextBlock.Metadata metadata = HostContextBlock.metadata(message);
                key = metadata == null ? "content/" + StepMessageCodec.message(message)
                        : "host/" + metadata.id();
            }
            List<Message> previous = units.get(key);
            if (previous == null || unit.stream().anyMatch(HostContextBlock::required)
                    || previous.stream().noneMatch(HostContextBlock::required)) units.put(key, unit);
        }
        return units.values().stream().flatMap(List::stream).toList();
    }
}
