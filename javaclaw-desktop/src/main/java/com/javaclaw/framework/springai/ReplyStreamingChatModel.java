package com.javaclaw.framework.springai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Internal provider capability; raw tool deltas never become tool executions. */
interface ReplyStreamingChatModel extends ChatModel {
    Flux<ChatResponse> stream(Prompt prompt, DecisionReplyStream reply);
}
