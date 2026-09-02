package com.javaclaw.server.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.McpElicitationRequest;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpSamplingRequest;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.InputRequestService;
import com.javaclaw.server.persistence.PersistenceException;

/** 把 MCP 反向交互绑定到权威父 Turn，并分别交给 InputRequest 与受预算 Harness。 */
public final class TurnMcpInteractionFactory implements McpClientInteractionFactory {
    private final InputRequestService inputs;
    private final CoreCommandService core;
    private final McpSamplingTurnService sampling;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建受治理交互工厂。
     *
     * @param inputs 持久化用户输入状态机
     * @param core Core 权威查询
     * @param sampling 受预算 sampling 子 Turn 服务
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public TurnMcpInteractionFactory(
            InputRequestService inputs,
            CoreCommandService core,
            McpSamplingTurnService sampling,
            CanonicalJson json,
            Clock clock) {
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.core = Objects.requireNonNull(core, "core");
        this.sampling = Objects.requireNonNull(sampling, "sampling");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public McpClientInteractionPort bind(TurnId turnId, WorkspaceId workspaceId, McpEndpoint endpoint) {
        TurnId checkedTurn = Objects.requireNonNull(turnId, "turnId");
        WorkspaceId checkedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        McpEndpoint checkedEndpoint = Objects.requireNonNull(endpoint, "endpoint");
        requireRunningTurn(checkedTurn, checkedWorkspace, checkedEndpoint);
        return new BoundInteractions(checkedTurn, checkedEndpoint);
    }

    private void requireRunningTurn(TurnId turnId, WorkspaceId workspaceId, McpEndpoint endpoint) {
        AgentTurn turn =
                core.findTurn(turnId).orElseThrow(() -> PersistenceException.invalidRequest("MCP 反向交互所属 Turn 不存在"));
        WorkspaceId turnWorkspace = core.workspaceForThread(turn.threadId()).id();
        if (turn.status() != TurnStatus.RUNNING
                || !turnWorkspace.equals(workspaceId)
                || !endpoint.spec().workspaceId().equals(workspaceId)) {
            throw PersistenceException.invalidRequest("MCP 反向交互必须绑定当前运行中的同 Workspace Turn");
        }
    }

    private String inputId(TurnId turnId, McpElicitationRequest request) {
        String digest = json.encode(Map.of(
                        "turnId", turnId.toString(),
                        "endpointId", request.endpointId(),
                        "requestId", request.id()))
                .sha256();
        return "mcp-elicit-" + digest.substring(0, 32);
    }

    private final class BoundInteractions implements McpClientInteractionPort {
        private final TurnId turnId;
        private final McpEndpoint endpoint;

        private BoundInteractions(TurnId turnId, McpEndpoint endpoint) {
            this.turnId = turnId;
            this.endpoint = endpoint;
        }

        @Override
        public Optional<CanonicalPayload> elicit(McpElicitationRequest request, CancellationToken cancellation)
                throws Exception {
            Instant now = clock.instant();
            InputRequest input = new InputRequest(
                    inputId(turnId, request),
                    turnId,
                    "mcp." + endpoint.id(),
                    request.prompt(),
                    request.responseSchema(),
                    now,
                    request.expiresAt());
            Optional<CanonicalPayload> response = inputs.await(input, cancellation);
            response.ifPresent(value -> json.requireFlatPrimitiveObjectValue(request.responseSchema(), value));
            return response;
        }

        @Override
        public Optional<CanonicalPayload> sample(McpSamplingRequest request, CancellationToken cancellation)
                throws Exception {
            return sampling.sample(turnId, endpoint, request, cancellation);
        }
    }
}
