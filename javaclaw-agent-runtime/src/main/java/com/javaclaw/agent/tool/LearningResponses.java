package com.javaclaw.agent.tool;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.model.ResponseContract;

/** 学习候选的有界输出契约；模型不能指定脚本、权限、自动确认或持久化目标。 */
public final class LearningResponses {
    private LearningResponses() {}

    /**
     * 候选数据；来源必须由服务器再校验，不因符合 JSON Schema 就成为可信事实。
     *
     * @param kind Memory 类别；Skill 固定为 SKILL
     * @param subject 事实主体或 Skill 名称
     * @param attribute 事实属性；Skill 为空
     * @param content 有证据的正文或完整 Skill 指令
     * @param sources 来源 Item 标识
     * @param reason 提取理由
     */
    public record Candidate(
            String kind, String subject, String attribute, String content, List<String> sources, String reason) {
        /** 固定来源列表，禁止后续修改审阅对象。 */
        public Candidate {
            sources = List.copyOf(sources);
        }
    }

    /** Memory 允许无新增；最多五条，不接受敏感属性与证据外推，代码仍复核来源。 */
    public static ResponseContract<List<Candidate>> memory() {
        return StructuredResponses.contract("memory-proposals-v1", """
                {"type":"object","additionalProperties":false,"required":["candidates"],"properties":{
                 "candidates":{"type":"array","maxItems":5,"items":{"type":"object","additionalProperties":false,
                  "required":["kind","subject","attribute","content","sources","reason"],"properties":{
                   "kind":{"enum":["FACT","EPISODE","ENTITY","RELATION","CORRECTION","PERSONA"]},
                   "subject":{"type":"string","maxLength":240},"attribute":{"type":"string","maxLength":240},
                   "content":{"type":"string","minLength":1,"maxLength":4000},
                   "sources":{"type":"array","minItems":1,"maxItems":5,"items":{"type":"string","maxLength":80}},
                   "reason":{"type":"string","maxLength":1000}}}}}}
                """, root -> {
            var results = new ArrayList<Candidate>();
            for (var value : root.path("candidates")) {
                var sources = new ArrayList<String>();
                value.path("sources").forEach(source -> sources.add(source.asText()));
                results.add(new Candidate(
                        value.path("kind").asText(),
                        value.path("subject").asText(),
                        value.path("attribute").asText(),
                        value.path("content").asText(),
                        sources,
                        value.path("reason").asText()));
            }
            return List.copyOf(results);
        });
    }

    /** Skill 只提炼最多两份文字指令，不接受资源、权限或可执行入口。 */
    public static ResponseContract<List<Candidate>> skill() {
        return StructuredResponses.contract("skill-proposals-v1", """
                {"type":"object","additionalProperties":false,"required":["candidates"],"properties":{
                 "candidates":{"type":"array","maxItems":2,"items":{"type":"object","additionalProperties":false,
                  "required":["name","instructions","sources","reason"],"properties":{
                   "name":{"type":"string","minLength":1,"maxLength":200},
                   "instructions":{"type":"string","minLength":1,"maxLength":8000},
                   "sources":{"type":"array","minItems":1,"maxItems":5,"items":{"type":"string","maxLength":80}},
                   "reason":{"type":"string","maxLength":1000}}}}}}
                """, root -> {
            var results = new ArrayList<Candidate>();
            for (var value : root.path("candidates")) {
                var sources = new ArrayList<String>();
                value.path("sources").forEach(source -> sources.add(source.asText()));
                results.add(new Candidate(
                        "SKILL",
                        value.path("name").asText(),
                        "",
                        value.path("instructions").asText(),
                        sources,
                        value.path("reason").asText()));
            }
            return List.copyOf(results);
        });
    }

    /** 只构造正文声明，剥离模型指定权限的可能性。 */
    public static String skillManifest(String instructions) {
        return new ObjectMapper()
                .createObjectNode()
                .put("instructions", instructions)
                .toString();
    }
}
