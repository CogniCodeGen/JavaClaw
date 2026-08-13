package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Sole write API for user Agent/Profile definitions. */
public interface AgentStudioClient {
    List<CapabilityForm> capabilityForms();

    List<StudioDraft> agentDrafts(String workspaceId);

    List<StudioDraft> profileDrafts(String workspaceId);

    StudioDraft agentDraft(String workspaceId, String definitionId);

    StudioDraft profileDraft(String workspaceId, String profileId);

    StudioDraft saveAgentDraft(String workspaceId, AgentDefinitionDraft draft);

    StudioDraft saveProfileDraft(String workspaceId, RunProfileDraft draft);

    DefinitionValidationResult validateAgent(String workspaceId, AgentDefinitionDraft draft);

    DefinitionValidationResult validateProfile(String workspaceId, RunProfileDraft draft);

    AgentDefinition publishAgent(String workspaceId, String definitionId);

    RunProfile publishProfile(String workspaceId, String profileId);

    List<AgentDefinition> agentHistory(String workspaceId, String definitionId);

    List<RunProfile> profileHistory(String workspaceId, String profileId);

    StudioDraft rollbackAgentToDraft(String workspaceId, String definitionId, long version);

    StudioDraft rollbackProfileToDraft(String workspaceId, String profileId, long version);

    /** Audit export only; importing it always creates/updates a draft and never publishes. */
    JsonNode exportAgent(String workspaceId, String definitionId, long version);

    JsonNode exportProfile(String workspaceId, String profileId, long version);

    StudioDraft importAgentDraft(String workspaceId, JsonNode auditExport);

    StudioDraft importProfileDraft(String workspaceId, JsonNode auditExport);

    /** Archives a user definition while retaining immutable published history for audit/recovery. */
    boolean archiveAgent(String workspaceId, String definitionId);
}
