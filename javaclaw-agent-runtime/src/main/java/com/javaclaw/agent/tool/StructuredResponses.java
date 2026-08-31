package com.javaclaw.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.model.ResponseContract;
import com.javaclaw.core.api.ThreadItem;

/** 复用工具 Schema 校验器的输出契约；Jackson 留在 Tool 边界，Kernel 只接收领域对象。 */
public final class StructuredResponses {
    private static final String PLAN = """
            {"type":"object","additionalProperties":false,
             "required":["goal","scope","steps","dependencies","acceptanceCriteria","risks","openQuestions"],
             "properties":{"goal":{"type":"string","minLength":1,"maxLength":4000},
               "scope":{"type":"string","minLength":1,"maxLength":4000},
               "steps":{"type":"array","minItems":1,"maxItems":200,"items":{"type":"string","minLength":1,"maxLength":4000}},
               "dependencies":{"type":"array","maxItems":200,"items":{"type":"string","maxLength":4000}},
               "acceptanceCriteria":{"type":"array","minItems":1,"maxItems":200,"items":{"type":"string","minLength":1,"maxLength":4000}},
               "risks":{"type":"array","maxItems":100,"items":{"type":"string","maxLength":4000}},
               "openQuestions":{"type":"array","maxItems":100,"items":{"type":"string","maxLength":4000}}}}
            """;
    private static final String DRAFT = """
            {"type":"object","additionalProperties":false,"required":["draft","changes","warnings"],
             "properties":{"draft":{"type":"string","minLength":1,"maxLength":50000},
               "changes":{"type":"array","maxItems":100,"items":{"type":"string","minLength":1,"maxLength":2000}},
               "warnings":{"type":"array","maxItems":100,"items":{"type":"string","minLength":1,"maxLength":2000}}}}
            """;

    private StructuredResponses() {}

    /** 有限交接摘要契约；六类事实显式区分，超长或不完整输出不能成为有效压缩标记。 */
    public static ResponseContract<String> compaction() {
        String array = "{\"type\":\"array\",\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":2000}}";
        String schema =
                "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"goal\",\"decisions\",\"constraints\",\"completed\",\"remaining\",\"uncertainties\"],\"properties\":{"
                        + "\"goal\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":4000},\"decisions\":" + array
                        + ",\"constraints\":" + array
                        + ",\"completed\":" + array + ",\"remaining\":" + array + ",\"uncertainties\":" + array + "}}";
        return contract("compaction-v1", schema, root -> {
            var summary = new StringBuilder("目标：\n").append(root.path("goal").asText());
            List<String> fields = List.of("decisions", "constraints", "completed", "remaining", "uncertainties");
            List<String> titles = List.of("已确认决策", "约束", "已完成与证据", "剩余工作", "不确定事项与未知副作用");
            for (int index = 0; index < fields.size(); index++) {
                summary.append("\n\n").append(titles.get(index)).append("：\n");
                summary.append(String.join("\n", strings(root.path(fields.get(index)))));
            }
            if (summary.length() > 24_000) {
                throw new IllegalArgumentException("compaction summary exceeds context budget");
            }
            return SecretRedactor.redactReference(summary.toString());
        });
    }

    /** 返回包含目标、范围、步骤、依赖、验收、风险和待决策项的 Plan 契约。 */
    public static ResponseContract<ThreadItem.Plan> plan() {
        return contract(
                "plan-v1",
                PLAN,
                root -> new ThreadItem.Plan(
                        strings(root.path("steps")).stream()
                                .map(value ->
                                        new ThreadItem.Plan.PlanStep(value, ThreadItem.Plan.PlanStep.Status.PENDING))
                                .toList(),
                        new ThreadItem.Plan.PlanDetails(
                                root.path("goal").asText(),
                                root.path("scope").asText(),
                                strings(root.path("dependencies")),
                                strings(root.path("acceptanceCriteria")),
                                strings(root.path("risks")),
                                strings(root.path("openQuestions")))));
    }

    /** 把来源 Profile 与版本绑定在服务端，模型不能改写草稿的保存目标。 */
    public static ResponseContract<ThreadItem.PromptDraft> promptDraft(String profileId, long revision) {
        return contract(
                "prompt-draft-v1",
                DRAFT,
                root -> new ThreadItem.PromptDraft(
                        profileId,
                        revision,
                        root.path("draft").asText(),
                        strings(root.path("changes")),
                        strings(root.path("warnings"))));
    }

    static <T> ResponseContract<T> contract(String id, String schema, Function<JsonNode, T> decoder) {
        ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        BasicJsonSchema validator = new BasicJsonSchema(json, schema);
        return new ResponseContract<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String schema() {
                return schema;
            }

            @Override
            public T decode(String content) {
                if (content == null || content.length() > 128_000) {
                    throw new IllegalArgumentException("structured response exceeds size limit");
                }
                try {
                    JsonNode value = json.readTree(content);
                    validator.validate(value);
                    return decoder.apply(value);
                } catch (JsonProcessingException failure) {
                    throw new IllegalArgumentException("response is not a single JSON object");
                }
            }
        };
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }
}
