package com.javaclaw.server.coding;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;

/** 当前已授权操作的不可替换输入，路径只能取 Turn.executionRoot。 */
record CodingInvocation(
        String id,
        ToolCallRequest request,
        AgentTurn turn,
        WorkspaceId workspaceId,
        PermissionProfile permission,
        CancellationToken cancellation,
        CodingEnvironmentContracts.Environment environment) {}
