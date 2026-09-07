package com.javaclaw.server.turn;

import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.TurnFailureException;
import com.javaclaw.server.persistence.CoreCommandService;

/** 在实际 Skill 工具边界执行 Role 的白名单；提示词与模型自行构造的 Skill ID 不能绕过。 */
final class SkillTurnCeiling {
    private SkillTurnCeiling() {}

    static void requireAllowed(CoreCommandService core, CanonicalJson json, ToolCallRequest request) {
        Optional<Set<String>> allowed = ceiling(core, request);
        if (allowed.isEmpty()) {
            return;
        }
        String id =
                switch (request.tool().name()) {
                    case "skill_read" ->
                        json.decode(request.arguments(), SkillContracts.PublishedReadRequest.class)
                                .id();
                    case "skill_execute_resource" ->
                        json.decode(request.arguments(), SkillContracts.ResourceExecutionRequest.class)
                                .skill()
                                .id();
                    default -> "";
                };
        if (!id.isEmpty() && !allowed.orElseThrow().contains(id)) {
            throw new TurnFailureException("SKILL_NOT_ALLOWED", "Skill 不在本 Turn 冻结的角色能力上限内");
        }
    }

    static ExtensionResponse filter(
            CoreCommandService core, CanonicalJson json, ToolCallRequest request, ExtensionResponse response) {
        Optional<Set<String>> allowed = ceiling(core, request);
        if (allowed.isEmpty() || !"skill_search".equals(request.tool().name())) {
            return response;
        }
        SkillContracts.SearchResult result = json.decode(response.payload(), SkillContracts.SearchResult.class);
        var matches = result.matches().stream()
                .filter(value -> allowed.orElseThrow().contains(value.id()))
                .toList();
        return new ExtensionResponse(
                json.encode(new SkillContracts.SearchResult(matches, result.catalogDigest())), response.revision());
    }

    private static Optional<Set<String>> ceiling(CoreCommandService core, ToolCallRequest request) {
        return BuiltinExtensionIds.SKILL.equals(request.tool().producerId())
                ? core.resolvedConfig(request.turnId()).effectiveSkills()
                : Optional.empty();
    }
}
