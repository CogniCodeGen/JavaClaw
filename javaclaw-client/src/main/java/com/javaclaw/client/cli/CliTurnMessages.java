package com.javaclaw.client.cli;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.client.extension.CodingToolResultIndex;
import com.javaclaw.client.extension.CodingTranscriptFormatter;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.protocol.CanonicalJson;

/** 按 sequence 去重输出当前 Turn 已提交的模型消息；保留活动 Item 游标，避免原序号更新后漏掉正文。 */
final class CliTurnMessages {
    private static final int PAGE_SIZE = 200;
    private final JavaClawClient client;
    private final PrintStream output;
    private final CliCodingOutput live;
    private final CanonicalJson json = new CanonicalJson();
    private final CodingTranscriptFormatter coding = new CodingTranscriptFormatter(json);
    private final CodingToolResultIndex codingCalls = new CodingToolResultIndex(json);
    private final Set<Long> printed = new HashSet<>();
    private final Map<Long, CodingTranscriptFormatter.Fact> facts = new HashMap<>();
    private long cursor;

    CliTurnMessages(JavaClawClient client, PrintStream output) {
        this.client = client;
        this.output = output;
        live = new CliCodingOutput(client, output);
    }

    void read(AgentTurn turn) {
        long pageCursor = cursor;
        long earliestPending = Long.MAX_VALUE;
        while (true) {
            var page = client.items().list(turn.threadId(), pageCursor, PAGE_SIZE);
            for (ItemEnvelope item : page.items()) {
                if (item.turnId().equals(turn.id())) {
                    if (item.status() == ItemStatus.IN_PROGRESS) {
                        earliestPending = Math.min(earliestPending, item.sequence());
                    }
                    print(item);
                }
            }
            if (page.nextSequence() < pageCursor || (!page.items().isEmpty() && page.nextSequence() == pageCursor)) {
                throw new IllegalStateException("Item 分页游标未推进");
            }
            pageCursor = page.nextSequence();
            if (page.items().size() < PAGE_SIZE) {
                cursor = Math.min(pageCursor, earliestPending - 1);
                live.read(
                        turn,
                        switch (turn.status()) {
                            case COMPLETED, FAILED, CANCELLED -> true;
                            default -> false;
                        });
                return;
            }
        }
    }

    private void print(ItemEnvelope item) {
        codingCalls.accept(item);
        var fact = coding.format(item, codingCalls.callFor(item));
        if (fact.isPresent()) {
            CodingTranscriptFormatter.Fact previous = facts.put(item.sequence(), fact.get());
            if (!fact.get().equals(previous)) {
                output.println(fact.get().title());
                String body = fact.get().body();
                // 同一 Item 更新时只追加共同前缀之后的正文，避免轮询重复打印累计日志。
                output.println(
                        previous != null && body.startsWith(previous.body())
                                ? body.substring(previous.body().length())
                                : body);
            }
            return;
        }
        if (item.status() != ItemStatus.COMPLETED || printed.contains(item.sequence())) {
            return;
        }
        if (!CoreSchemas.MESSAGE.equals(item.schemaId())) {
            if (CoreSchemas.TOOL_RESULT.equals(item.schemaId()) || !knownCore(item.schemaId())) {
                var fallback = coding.fallback(item);
                output.println(fallback.title());
                output.println(fallback.body());
                printed.add(item.sequence());
            }
            return;
        }
        CorePayloads.Message message = json.decode(item.payload(), CorePayloads.Message.class);
        if (message.role() == MessageRole.ASSISTANT && !message.text().isEmpty()) {
            output.println(message.text());
            printed.add(item.sequence());
        }
    }

    private static boolean knownCore(String schema) {
        return Set.of(
                        CoreSchemas.TOOL_CALL,
                        CoreSchemas.COMMAND,
                        CoreSchemas.FILE_CHANGE,
                        CoreSchemas.APPROVAL,
                        CoreSchemas.INPUT,
                        CoreSchemas.SUBAGENT,
                        CoreSchemas.COMPACTION,
                        CoreSchemas.EFFECT_RECEIPT,
                        CoreSchemas.ERROR)
                .contains(schema);
    }
}
