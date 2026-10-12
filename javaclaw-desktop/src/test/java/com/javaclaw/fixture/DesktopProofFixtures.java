package com.javaclaw.fixture;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 为测试中明确构造的当前帧正证添加同内容的显式裁决，不改变帧身份或几何。 */
public final class DesktopProofFixtures {
    private DesktopProofFixtures() { }

    public static ObjectNode withTrueResults(ObjectNode observation) {
        var decisions = observation.putArray("conditionResults");
        for (var value : observation.path("conditionEvidence")) {
            var decision = ((ObjectNode) value).deepCopy();
            decision.put("outcome", "TRUE").put("complete", true);
            decisions.add(decision);
        }
        return observation;
    }

    public static String withTrueDecisions(ObjectNode proof) {
        if (proof.path("schemaVersion").asInt() != 2) {
            throw new IllegalArgumentException("正证夹具必须显式声明当前协议");
        }
        for (var value : proof.path("conditions")) {
            var condition = (ObjectNode) value;
            var decision = condition.putObject("decision").put("outcome", "TRUE")
                    .put("complete", true).put("region", "main-content");
            decision.set("confidence", condition.path("confidence").deepCopy());
            decision.set("content", condition.path("content").deepCopy());
        }
        return proof.toString();
    }
}
