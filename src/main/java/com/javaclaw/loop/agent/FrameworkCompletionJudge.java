package com.javaclaw.loop.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.goal.SuccessCriterion;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.loop.CompletionJudge;
import com.javaclaw.loop.LoopConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Loop critic implemented through the unified auxiliary-model gateway. */
public final class FrameworkCompletionJudge implements CompletionJudge {
    private static final Logger log = LoggerFactory.getLogger(FrameworkCompletionJudge.class);

    private final ModelTaskGateway models;
    private final Supplier<RunId> ownerRun;
    private final BooleanSupplier cancelled;
    private final String workDir;
    private final AtomicLong usedTokens = new AtomicLong();

    public FrameworkCompletionJudge(
            ModelTaskGateway models,
            Supplier<RunId> ownerRun,
            BooleanSupplier cancelled,
            String workDir) {
        this.models = Objects.requireNonNull(models, "models");
        this.ownerRun = Objects.requireNonNull(ownerRun, "ownerRun");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.workDir = workDir == null ? "" : workDir;
    }

    @Override
    public Verdict goalMet(String goal, String transcript) {
        ObjectNode input = base("goal-completion");
        input.put("goal", value(goal));
        input.put("transcript", value(transcript));
        input.put("instruction", "Judge whether the goal is truly complete. Evidence missing means false.");
        return judge("loop.goal-completion", ModelTier.HIGH, input);
    }

    @Override
    public Verdict criterionMet(SuccessCriterion criterion, String transcript) {
        ObjectNode input = base("criterion-completion");
        input.put("criterionType", criterion == null ? "" : criterion.normalizedType());
        input.put("criterion", criterion == null ? "" : value(criterion.predicate));
        input.put("transcript", value(transcript));
        input.put("instruction", "Judge this criterion only. Evidence missing means false.");
        return judge("loop.criterion-completion", ModelTier.HIGH, input);
    }

    @Override
    public Verdict progressMade(String goal, String previousOutput, String currentOutput) {
        ObjectNode input = base("progress-arbitration");
        input.put("goal", value(goal));
        input.put("previousOutput", value(previousOutput));
        input.put("currentOutput", value(currentOutput));
        input.put("instruction", "True only when the current output is materially closer to the goal.");
        return judge("loop.progress-arbitration", ModelTier.LIGHT, input);
    }

    private Verdict judge(String purpose, ModelTier tier, ObjectNode input) {
        RunId owner = ownerRun.get();
        if (owner == null) return new Verdict(false, "尚无可归属的执行 Run，保守判未达成");
        try {
            CancellationToken token = cancelled::getAsBoolean;
            ModelTaskResult result = models.execute(new ModelTaskRequest(
                    purpose, tier, input, verdictSchema(), owner, "loop-critic",
                    Duration.ofSeconds(LoopConstants.JUDGE_TIMEOUT_SECONDS), 1, token, false))
                    .toCompletableFuture().get(
                            LoopConstants.JUDGE_TIMEOUT_SECONDS + 2L, TimeUnit.SECONDS);
            usedTokens.addAndGet(result.inputTokens() + result.outputTokens());
            return new Verdict(result.output().path("met").asBoolean(false),
                    result.output().path("reason").asText("模型未给出理由"));
        } catch (Exception failure) {
            log.warn("循环验收任务 {} 失败，保守判未达成: {}", purpose, failure.toString());
            return new Verdict(false, "验收异常，保守判未达成：" + failure.getMessage());
        }
    }

    private ObjectNode base(String kind) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("kind", kind);
        input.put("workDir", workDir);
        return input;
    }

    private static ObjectNode verdictSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("met").put("type", "boolean");
        properties.putObject("reason").put("type", "string");
        schema.putArray("required").add("met").add("reason");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long drainUsedTokens() {
        return usedTokens.getAndSet(0L);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }
}
