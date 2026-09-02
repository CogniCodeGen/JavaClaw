package com.javaclaw.model;

import java.util.Objects;
import java.util.function.Consumer;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.CompactedResponse;
import com.openai.models.responses.ResponseCompactParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;

/** 使用官方 SDK 管理 Responses SSE 与连接资源。 */
final class OfficialOpenAiResponsesTransport implements OpenAiResponsesTransport {
    private final OpenAIClient client;

    OfficialOpenAiResponsesTransport(OpenAIClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void stream(ResponseCreateParams request, Consumer<ResponseStreamEvent> consumer) {
        try (StreamResponse<ResponseStreamEvent> response = client.responses().createStreaming(request)) {
            response.stream().forEach(consumer);
        }
    }

    @Override
    public CompactedResponse compact(ResponseCompactParams request) {
        return client.responses().compact(request);
    }

    @Override
    public void close() {
        client.close();
    }
}
