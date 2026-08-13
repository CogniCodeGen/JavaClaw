package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.*;

import java.util.List;

/** Persistence port for Agent Studio drafts and immutable published history. */
public interface AgentStudioRepository extends AgentDefinitionResolver {
    List<StudioDraft> listAgentDrafts(String workspaceId);

    List<StudioDraft> listProfileDrafts(String workspaceId);

    StudioDraft saveAgentDraft(String workspaceId, AgentDefinitionDraft draft, boolean builtinWrite);

    StudioDraft saveProfileDraft(String workspaceId, RunProfileDraft draft, boolean builtinWrite);

    StudioDraft getAgentDraft(String workspaceId, String id);

    StudioDraft getProfileDraft(String workspaceId, String id);

    AgentDefinition publishAgent(String workspaceId, String id);

    RunProfile publishProfile(String workspaceId, String id);

    List<AgentDefinition> agentHistory(String workspaceId, String id);

    List<RunProfile> profileHistory(String workspaceId, String id);

    AgentDefinition findAgentVersion(String workspaceId, String id, long version);

    RunProfile findProfileVersion(String workspaceId, String id, long version);

    boolean archiveAgent(String workspaceId, String id);
}
