package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Explicit wire codec keeps provider-independent conversations replayable across Spring versions. */
final class StepMessageCodec {
    private StepMessageCodec() { }
    static ArrayNode messages(List<Message> messages) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        messages.forEach(message -> result.add(message(message)));
        return result;
    }
    static ObjectNode message(Message message) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("role", message.getMessageType().getValue());
        result.put("text", message.getText() == null ? "" : message.getText());
        if (message instanceof AssistantMessage assistant) {
            ArrayNode calls = result.putArray("toolCalls");
            assistant.getToolCalls().forEach(call -> calls.addObject()
                    .put("id", call.id()).put("type", call.type())
                    .put("name", call.name()).put("arguments", call.arguments()));
        } else if (message instanceof ToolResponseMessage response) {
            ArrayNode values = result.putArray("responses");
            response.getResponses().forEach(value -> values.addObject()
                    .put("id", value.id()).put("name", value.name()).put("data", value.responseData()));
        } else if (message instanceof UserMessage user) {
            ArrayNode media = result.putArray("media");
            for (Media value : user.getMedia()) {
                ObjectNode item = media.addObject().put("mimeType", value.getMimeType().toString());
                if (value.getName() != null) item.put("name", value.getName());
                if (value.getData() instanceof byte[] bytes) item.put("base64", Base64.getEncoder().encodeToString(bytes));
                else if (value.getData() != null) item.put("uri", value.getData().toString());
            }
        }
        return result;
    }
    static List<Message> messages(JsonNode values) {
        List<Message> result = new ArrayList<>();
        values.forEach(value -> result.add(message(value)));
        return result;
    }
    static Message message(JsonNode value) {
        String text = value.path("text").asText("");
        return switch (value.path("role").asText()) {
            case "system" -> new SystemMessage(text);
            case "assistant" -> {
                List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                value.path("toolCalls").forEach(call -> calls.add(new AssistantMessage.ToolCall(
                        call.path("id").asText(), call.path("type").asText("function"),
                        call.path("name").asText(), call.path("arguments").asText())));
                yield AssistantMessage.builder().content(text).toolCalls(calls).build();
            }
            case "tool" -> {
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                value.path("responses").forEach(response -> responses.add(new ToolResponseMessage.ToolResponse(
                        response.path("id").asText(), response.path("name").asText(), response.path("data").asText())));
                yield ToolResponseMessage.builder().responses(responses).build();
            }
            case "user" -> {
                List<Media> media = new ArrayList<>();
                value.path("media").forEach(item -> {
                    Media.Builder builder = Media.builder().mimeType(MimeTypeUtils.parseMimeType(item.path("mimeType").asText()));
                    if (item.has("name")) builder.name(item.path("name").asText());
                    if (item.has("base64")) builder.data(Base64.getDecoder().decode(item.path("base64").asText()));
                    else builder.data(URI.create(item.path("uri").asText()));
                    media.add(builder.build());
                });
                yield UserMessage.builder().text(text).media(media).build();
            }
            default -> throw new IllegalStateException("unknown persisted message role: " + value.path("role"));
        };
    }
    static ObjectNode response(ChatResponse response) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode generations = result.putArray("generations");
        response.getResults().forEach(generation -> generations.add(message(generation.getOutput())));
        if (response.getResult() != null) result.set("message", message(response.getResult().getOutput()));
        if (response.getMetadata() != null) result.put("model", response.getMetadata().getModel());
        return result;
    }
    static ObjectNode usage(ChatResponse response) {
        ObjectNode usage = JsonNodeFactory.instance.objectNode();
        var value = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        usage.put("inputTokens", value == null || value.getPromptTokens() == null ? 0 : value.getPromptTokens());
        usage.put("outputTokens", value == null || value.getCompletionTokens() == null ? 0 : value.getCompletionTokens());
        if (response.getMetadata() != null && response.getMetadata().getModel() != null) {
            usage.put("estimatedCostCny", com.javaclaw.agent.PricingTable.estimateCostCny(
                    response.getMetadata().getModel(), usage.path("inputTokens").asLong(), usage.path("outputTokens").asLong()));
        }
        return usage;
    }
    static ObjectNode failureUsage(ManagedInferenceChatModel.ManagedInferenceModelException failure) {
        return JsonNodeFactory.instance.objectNode()
                .put("inputTokens", failure.usage().promptTokens())
                .put("outputTokens", failure.usage().completionTokens())
                .put("estimatedCostCny", com.javaclaw.agent.PricingTable.estimateCostCny(
                        failure.model(), failure.usage().promptTokens(), failure.usage().completionTokens()));
    }
}
