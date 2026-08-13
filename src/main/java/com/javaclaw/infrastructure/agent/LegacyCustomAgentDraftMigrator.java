package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionDraft;
import com.javaclaw.framework.api.AgentStudioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Imports legacy {@code custom_agents} rows as non-executable Agent Studio drafts.
 * The source table is retained for audit; users must review and publish each migrated draft.
 */
public final class LegacyCustomAgentDraftMigrator {
    private static final Logger log = LoggerFactory.getLogger(LegacyCustomAgentDraftMigrator.class);

    public LegacyCustomAgentDraftMigrator(
            String workspaceId,
            JdbcTemplate jdbc,
            AgentStudioClient studio,
            ObjectMapper json) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(studio, "studio");
        Objects.requireNonNull(json, "json");
        migrate(workspaceId, jdbc, studio, json);
    }

    private static void migrate(
            String workspaceId,
            JdbcTemplate jdbc,
            AgentStudioClient studio,
            ObjectMapper json) {
        try {
            List<LegacyAgent> legacy = jdbc.query("""
                    SELECT id, name, tool_name, description, sys_prompt, max_iters, enabled
                    FROM custom_agents WHERE workspace_id = ? ORDER BY id
                    """, (row, index) -> new LegacyAgent(
                    row.getString("id"), row.getString("name"), row.getString("tool_name"),
                    row.getString("description"), row.getString("sys_prompt"),
                    row.getInt("max_iters"), row.getBoolean("enabled")), workspaceId);
            if (legacy.isEmpty()) return;

            HashSet<String> existing = new HashSet<>();
            studio.agentDrafts(workspaceId).forEach(draft -> existing.add(draft.id()));
            AgentDefinitionDraft base = json.convertValue(
                    studio.agentDraft(workspaceId, "system.default").document(),
                    AgentDefinitionDraft.class);
            int migrated = 0;
            for (LegacyAgent source : legacy) {
                String id = "legacy." + source.id();
                if (existing.contains(id)) continue;
                LinkedHashMap<String, String> prompts = new LinkedHashMap<>(base.promptSections());
                prompts.put("description", Objects.requireNonNullElse(source.description(), ""));
                prompts.put("system", Objects.requireNonNullElse(source.systemPrompt(), ""));
                ObjectNode toolPolicy = base.toolPolicy() instanceof ObjectNode object
                        ? object.deepCopy() : json.createObjectNode();
                toolPolicy.put("toolName", Objects.requireNonNullElse(source.toolName(), ""));
                toolPolicy.put("enabled", source.enabled());
                toolPolicy.put("maxIterations", Math.max(1, source.maxIterations()));
                toolPolicy.put("migrationStatus", "REQUIRES_REVIEW_AND_PUBLISH");
                toolPolicy.put("legacyId", source.id());
                studio.saveAgentDraft(workspaceId, new AgentDefinitionDraft(
                        id, Objects.requireNonNullElse(source.name(), "迁移智能体"),
                        base.modelPolicyRef(), prompts, base.capabilityBindings(), toolPolicy,
                        base.permissionPolicy(), base.budgetPolicy(), base.outputContract(),
                        base.compatibleExtensionRanges()));
                migrated++;
            }
            if (migrated > 0) {
                log.info("已将 {} 个旧自定义智能体转换为待重新发布的 Agent Studio 草稿", migrated);
            }
        } catch (DataAccessException failure) {
            log.warn("读取旧 custom_agents 迁移源失败，已跳过: {}", failure.getMessage());
        }
    }

    private record LegacyAgent(
            String id,
            String name,
            String toolName,
            String description,
            String systemPrompt,
            int maxIterations,
            boolean enabled) {}
}
