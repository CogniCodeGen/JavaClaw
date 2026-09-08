package com.javaclaw.server.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;
import com.javaclaw.extension.spi.ConversationEvidencePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelStreamEvent;
import com.javaclaw.runtime.ModelUsage;

/** 只从真实冻结证据回显用户事实的固定模型；可在学习调用处设闸，验证实际取消和重叠链路。 */
final class MemoryIntegrationModel implements ModelGateway {
    static final String FACT_PREFIX = "记忆事实：";
    private final CanonicalJson json = new CanonicalJson();
    final List<ModelInvocation> learningInvocations = new CopyOnWriteArrayList<>();
    final List<TurnId> learningTurns = new CopyOnWriteArrayList<>();
    volatile boolean invalidOutput;
    volatile boolean streamConversation;
    private volatile CountDownLatch entered = new CountDownLatch(0);
    private volatile CountDownLatch release = new CountDownLatch(0);

    void blockLearning() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    void awaitLearning() throws InterruptedException {
        if (!entered.await(20, TimeUnit.SECONDS)) {
            throw new AssertionError("学习 Turn 未进入真实 ModelGateway");
        }
    }

    void releaseLearning() {
        release.countDown();
    }

    @Override
    public ModelCapabilities capabilities(String modelId) {
        return new ModelCapabilities(streamConversation, false, false, false, false, false, false);
    }

    @Override
    public ModelInvocationResult invoke(
            TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation)
            throws Exception {
        String message = invocation.messages().getLast().text();
        int evidenceOffset = message.indexOf("{\"evidence\":");
        if (evidenceOffset < 0) {
            return conversation(turnId, events, cancellation);
        }
        learningInvocations.add(invocation);
        learningTurns.add(turnId);
        entered.countDown();
        while (!release.await(20, TimeUnit.MILLISECONDS)) {
            cancellation.throwIfCancelled();
        }
        cancellation.throwIfCancelled();
        if (invalidOutput) {
            return result("模型输出格式未知");
        }
        var evidence = json.objectArrayField(json.parse(message.substring(evidenceOffset)), "evidence", 200);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int index = 0; index < evidence.size(); index++) {
            var source = json.decode(evidence.get(index), ConversationEvidencePort.Evidence.class);
            if (source.sourceKind() == ConversationEvidencePort.SourceKind.USER_TEXT
                    && source.text().startsWith(FACT_PREFIX)) {
                candidates.add(Map.of(
                        "evidenceIndex",
                        index,
                        "content",
                        source.text(),
                        "verbatim",
                        source.text(),
                        "scope",
                        "workspace",
                        "summary",
                        false));
            }
        }
        return result(json.encode(Map.of("candidates", candidates)).json());
    }

    private ModelInvocationResult conversation(TurnId turnId, ModelEventSink events, CancellationToken cancellation)
            throws InterruptedException {
        if (!streamConversation) {
            return result("已收到");
        }
        String fence = Character.toString((char) 96).repeat(3);
        String text = "## 固定模型验收\n\n这段回复通过真实 SDK 增量送达，未调用付费模型。\n\n"
                + fence + "java\nSystem.out.println(\"JavaClaw\");\n" + fence
                + "\n\n[验收文档](guide.md) · [图片](diagram.png)\n";
        int chunk = Math.max(1, (text.length() + 29) / 30);
        for (int offset = 0; offset < text.length(); offset += chunk) {
            cancellation.throwIfCancelled();
            events.publish(
                    turnId,
                    new ModelStreamEvent.TextDelta(text.substring(offset, Math.min(offset + chunk, text.length()))),
                    cancellation);
            Thread.sleep(500);
        }
        return result(text);
    }

    private static ModelInvocationResult result(String text) {
        return new ModelInvocationResult(
                text,
                List.of(),
                new ModelUsage(100, 40, 0, 0),
                Optional.empty(),
                Optional.empty(),
                ModelFinishReason.COMPLETE);
    }
}
