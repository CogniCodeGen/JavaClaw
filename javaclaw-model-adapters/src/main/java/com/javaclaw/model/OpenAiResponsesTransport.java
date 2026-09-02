package com.javaclaw.model;

import java.util.function.Consumer;

import com.openai.models.responses.CompactedResponse;
import com.openai.models.responses.ResponseCompactParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;

/** 官方 OpenAI SDK 与可重复本地契约测试之间的窄边界。 */
interface OpenAiResponsesTransport extends AutoCloseable {
    void stream(ResponseCreateParams request, Consumer<ResponseStreamEvent> consumer);

    CompactedResponse compact(ResponseCompactParams request);

    @Override
    void close();
}
