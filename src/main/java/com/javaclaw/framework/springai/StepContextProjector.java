package com.javaclaw.framework.springai;

import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.StepContextPolicy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 为单个 Provider Step 构建有界且协议完整的消息投影。 */
final class StepContextProjector {
    private static final String EXECUTED_SUMMARY =
            "[工具已执行；完整结果保留在 durable tool event]";

    private final StepContextPolicy policy;

    StepContextProjector(StepContextPolicy policy) {
        this.policy = policy;
    }

    Projection project(List<Message> source) {
        if (policy == null) {
            int characters = source.stream().mapToInt(StepContextProjector::characters).sum();
            return new Projection(List.copyOf(source), new Statistics(
                    source.size(), source.size(), characters, characters, 0, false));
        }
        List<Message> normalized = source.stream().map(this::boundToolResult).toList();
        List<Message> systems = normalized.stream().filter(SystemMessage.class::isInstance).toList();
        List<Unit> units = units(normalized);
        Set<Integer> selected = requiredUnits(units);
        int used = systems.stream().mapToInt(StepContextProjector::characters).sum();
        used += selected.stream().mapToInt(index -> units.get(index).characters()).sum();
        if (used > policy.maxMessageCharacters()) {
            int latestExchange = latestToolExchange(units);
            if (latestExchange >= 0) {
                Unit previous = units.get(latestExchange);
                Unit summarized = summarizeToolResult(previous);
                units.set(latestExchange, summarized);
                used -= previous.characters() - summarized.characters();
            }
        }
        if (used > policy.maxMessageCharacters()) {
            throw new IllegalStateException("required model context exceeds message character budget");
        }
        used = retainRecentToolExchanges(units, selected, used);
        for (int index = units.size() - 1; index >= 0; index--) {
            if (selected.contains(index)) continue;
            int characters = units.get(index).characters();
            if (used + characters <= policy.maxMessageCharacters()) {
                selected.add(index);
                used += characters;
            }
        }

        List<Message> result = new ArrayList<>(systems);
        for (int index = 0; index < units.size(); index++) {
            if (selected.contains(index)) result.addAll(units.get(index).messages());
        }
        Statistics statistics = statistics(source, result, units, selected);
        return new Projection(List.copyOf(result), statistics);
    }

    /** Check the exact final Provider prompt after all Advisors have run. */
    void validate(List<Message> actual, ReasoningRequest request) {
        if (policy == null) return;
        UserMessage original = SpringAiPromptFactory.originalTaskMessage(request);
        boolean originalPresent = actual.stream().anyMatch(message ->
                message instanceof UserMessage user
                        && SpringAiPromptFactory.isOriginalTask(user)
                        && SpringAiPromptFactory.sameUserContent(user, original));
        if (!originalPresent) throw new IllegalStateException("original task is missing from provider context");

        UserMessage resume = SpringAiPromptFactory.resumeCommandMessage(request);
        if (resume != null && actual.stream().noneMatch(message ->
                message instanceof UserMessage user
                        && SpringAiPromptFactory.isResumeCommand(user)
                        && SpringAiPromptFactory.sameUserContent(user, resume))) {
            throw new IllegalStateException("latest resume command is missing from provider context");
        }

        if (!StepMessageCodec.messages(actual).equals(StepMessageCodec.messages(project(actual).messages()))) {
            throw new IllegalStateException("provider context bypasses the message budget or tool protocol");
        }
    }

    void validate(List<Message> actual, ReasoningRequest request, List<Message> expected) {
        if (policy == null) return;
        if (expected == null) {
            throw new IllegalStateException("provider context was not prepared by the tool advisor");
        }
        validate(actual, request);
        List<Unit> expectedUnits = units(expected);
        for (Unit unit : expectedUnits) {
            if (!unit.containsFixedContext()) continue;
            var essential = StepMessageCodec.messages(unit.messages());
            boolean present = units(actual).stream().anyMatch(value ->
                    StepMessageCodec.messages(value.messages()).equals(essential));
            if (!present) {
                throw new IllegalStateException("fixed context is missing from provider context");
            }
        }
        int latestExchange = latestToolExchange(expectedUnits);
        if (latestExchange < 0) return;
        var essentialExchange = StepMessageCodec.messages(
                expectedUnits.get(latestExchange).messages());
        boolean present = units(actual).stream().filter(Unit::toolExchange)
                .anyMatch(unit -> StepMessageCodec.messages(unit.messages()).equals(essentialExchange));
        if (!present) {
            throw new IllegalStateException("latest completed tool exchange is missing from provider context");
        }
    }

    private static Set<Integer> requiredUnits(List<Unit> units) {
        Set<Integer> required = new HashSet<>();
        int original = firstOriginalTask(units);
        if (original < 0) original = firstUser(units);
        if (original >= 0) required.add(original);
        int latestUser = latestUser(units);
        if (latestUser >= 0) required.add(latestUser);
        int latestResume = latestResume(units);
        if (latestResume >= 0) required.add(latestResume);
        int latestExchange = latestToolExchange(units);
        if (latestExchange >= 0) required.add(latestExchange);
        for (int index = 0; index < units.size(); index++) {
            if (units.get(index).containsFixedContext()) required.add(index);
        }
        return required;
    }

    private int retainRecentToolExchanges(
            List<Unit> units, Set<Integer> selected, int used) {
        int exchanges = 0;
        for (int index = units.size() - 1;
                index >= 0 && exchanges < policy.retainedToolExchanges(); index--) {
            Unit unit = units.get(index);
            if (!unit.toolExchange()) continue;
            exchanges++;
            if (selected.contains(index)) continue;
            int characters = unit.characters();
            if (used + characters <= policy.maxMessageCharacters()) {
                selected.add(index);
                used += characters;
            }
        }
        return used;
    }

    private static int firstOriginalTask(List<Unit> units) {
        for (int index = 0; index < units.size(); index++) {
            if (units.get(index).containsOriginalTask()) return index;
        }
        return -1;
    }

    private static int firstUser(List<Unit> units) {
        for (int index = 0; index < units.size(); index++) {
            if (units.get(index).containsUser()) return index;
        }
        return -1;
    }

    private static int latestUser(List<Unit> units) {
        for (int index = units.size() - 1; index >= 0; index--) {
            if (units.get(index).containsUser()) return index;
        }
        return -1;
    }

    private static int latestResume(List<Unit> units) {
        for (int index = units.size() - 1; index >= 0; index--) {
            if (units.get(index).containsResumeCommand()) return index;
        }
        return -1;
    }

    private static int latestToolExchange(List<Unit> units) {
        for (int index = units.size() - 1; index >= 0; index--) {
            if (units.get(index).toolExchange()) return index;
        }
        return -1;
    }

    private static Unit summarizeToolResult(Unit exchange) {
        if (!exchange.toolExchange()) return exchange;
        ToolResponseMessage response = (ToolResponseMessage) exchange.messages().get(1);
        List<ToolResponseMessage.ToolResponse> summarized = response.getResponses().stream()
                .map(value -> new ToolResponseMessage.ToolResponse(
                        value.id(), value.name(), EXECUTED_SUMMARY))
                .toList();
        return new Unit(List.of(exchange.messages().getFirst(),
                ToolResponseMessage.builder().responses(summarized).build()), true);
    }

    private Statistics statistics(
            List<Message> source, List<Message> result, List<Unit> units, Set<Integer> selected) {
        int sourceExchanges = (int) units.stream().filter(Unit::toolExchange).count();
        int keptExchanges = (int) selected.stream()
                .filter(index -> units.get(index).toolExchange()).count();
        int before = source.stream().mapToInt(StepContextProjector::characters).sum();
        int after = result.stream().mapToInt(StepContextProjector::characters).sum();
        return new Statistics(source.size(), result.size(), before, after,
                sourceExchanges - keptExchanges, result.size() < source.size() || after < before);
    }

    private List<Unit> units(List<Message> messages) {
        List<Unit> result = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message instanceof SystemMessage) continue;
            if (isToolCall(message)) {
                if (index + 1 >= messages.size()
                        || !(messages.get(index + 1) instanceof ToolResponseMessage response)
                        || !matches((AssistantMessage) message, response)) {
                    throw new IllegalStateException("incomplete assistant tool call/response exchange");
                }
                result.add(new Unit(List.of(message, response), true));
                index++;
            } else if (message instanceof ToolResponseMessage) {
                throw new IllegalStateException("orphan tool response in provider context");
            } else {
                result.add(new Unit(List.of(message), false));
            }
        }
        return result;
    }

    private Message boundToolResult(Message message) {
        if (!(message instanceof ToolResponseMessage response)) return message;
        List<ToolResponseMessage.ToolResponse> bounded = new ArrayList<>();
        for (var value : response.getResponses()) {
            String data = value.responseData();
            if (data != null && data.length() > policy.maxToolResultCharacters()) {
                data = EXECUTED_SUMMARY;
            }
            bounded.add(new ToolResponseMessage.ToolResponse(
                    value.id(), value.name(), data == null ? "" : data));
        }
        return ToolResponseMessage.builder().responses(bounded).build();
    }

    private static boolean matches(
            AssistantMessage assistant, ToolResponseMessage response) {
        if (assistant.getToolCalls().size() != response.getResponses().size()) return false;
        java.util.Map<String, String> callNames = new java.util.HashMap<>();
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            if (callNames.putIfAbsent(call.id(), call.name()) != null) return false;
        }
        Set<String> responseIds = new HashSet<>();
        for (ToolResponseMessage.ToolResponse value : response.getResponses()) {
            if (!responseIds.add(value.id())
                    || !value.name().equals(callNames.get(value.id()))) return false;
        }
        return !callNames.isEmpty();
    }

    private static boolean isToolCall(Message message) {
        return message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty();
    }

    static int characters(Message message) {
        int count = message.getText() == null ? 0 : message.getText().length();
        if (message instanceof AssistantMessage assistant) {
            for (var call : assistant.getToolCalls()) {
                count += call.id().length() + call.name().length() + call.arguments().length();
            }
        } else if (message instanceof ToolResponseMessage response) {
            for (var value : response.getResponses()) {
                count += value.id().length() + value.name().length()
                        + (value.responseData() == null ? 0 : value.responseData().length());
            }
        }
        return count;
    }

    record Projection(List<Message> messages, Statistics statistics) { }

    record Statistics(
            int messagesBefore,
            int messagesAfter,
            int charactersBefore,
            int charactersAfter,
            int evictedToolExchanges,
            boolean compacted) { }

    private record Unit(List<Message> messages, boolean toolExchange) {
        int characters() {
            return messages.stream().mapToInt(StepContextProjector::characters).sum();
        }

        boolean containsUser() {
            return messages.stream().anyMatch(UserMessage.class::isInstance);
        }

        boolean containsOriginalTask() {
            return messages.stream().anyMatch(message -> message instanceof UserMessage user
                    && SpringAiPromptFactory.isOriginalTask(user));
        }

        boolean containsResumeCommand() {
            return messages.stream().anyMatch(message -> message instanceof UserMessage user
                    && SpringAiPromptFactory.isResumeCommand(user));
        }

        boolean containsFixedContext() {
            return messages.stream().anyMatch(message -> message instanceof UserMessage user
                    && FixedContextSession.isFixed(user));
        }
    }
}
