package com.javaclaw.task.sdd.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.task.sdd.SddTokenSink;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.verify.CriticJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** SDD descriptive-scenario critic using the unified ModelTaskGateway. */
public final class FrameworkCriticJudge implements CriticJudge {
    private static final Logger log = LoggerFactory.getLogger(FrameworkCriticJudge.class);

    private final String workDir;
    private final ModelTaskGateway models;
    private final Supplier<RunId> ownerRun;
    private final BooleanSupplier cancelled;
    private final SddTokenSink tokens;
    private volatile long timeoutSec = 120;

    public FrameworkCriticJudge(
            String workDir,
            ModelTaskGateway models,
            Supplier<RunId> ownerRun,
            BooleanSupplier cancelled,
            SddTokenSink tokens) {
        this.workDir = workDir == null ? "" : workDir;
        this.models = Objects.requireNonNull(models, "models");
        this.ownerRun = Objects.requireNonNull(ownerRun, "ownerRun");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.tokens = tokens == null ? SddTokenSink.NOOP : tokens;
    }

    public FrameworkCriticJudge timeoutSec(long seconds) {
        if (seconds > 0) timeoutSec = seconds;
        return this;
    }

    @Override
    public Verdict judge(Scenario scenario) {
        RunId owner = ownerRun.get();
        if (owner == null) return new Verdict(false, "尚无可归属 Run，保守判不通过");
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("title", scenario.title());
        input.put("given", value(scenario.given()));
        input.put("when", value(scenario.when()));
        input.put("then", value(scenario.then()));
        input.put("criterion", scenario.criterion() == null
                ? "" : value(scenario.criterion().predicate()));
        input.put("workDir", workDir);
        input.put("instruction", "Judge only from supplied evidence. Missing evidence means false.");
        try {
            CancellationToken token = cancelled::getAsBoolean;
            ModelTaskResult result = models.execute(new ModelTaskRequest(
                    "sdd.scenario-critic", ModelTier.HIGH, input, schema(), owner,
                    "sdd-verify", Duration.ofSeconds(timeoutSec), 1, token, false))
                    .toCompletableFuture().get(timeoutSec + 2, TimeUnit.SECONDS);
            tokens.record("verify", result.inputTokens(), result.outputTokens());
            return new Verdict(result.output().path("pass").asBoolean(false),
                    result.output().path("reason").asText("critic 未给出理由"));
        } catch (Exception failure) {
            log.warn("[SDD] critic 判定异常，保守判不通过: {}", failure.toString());
            return new Verdict(false, "critic 判定异常：" + failure.getMessage());
        }
    }

    private static ObjectNode schema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("pass").put("type", "boolean");
        properties.putObject("reason").put("type", "string");
        schema.putArray("required").add("pass").add("reason");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
