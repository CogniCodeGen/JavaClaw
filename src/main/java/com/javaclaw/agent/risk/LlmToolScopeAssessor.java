package com.javaclaw.agent.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.TokenTracker;
import com.javaclaw.agent.model.ModelFactory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 风险评估智能体 —— 用轻量模型单次调用判定高风险工具操作的影响范围是否限于任务工作目录。
 *
 * <p>设计沿用 {@code ToolRouter} / {@code GoalManager} 范式：常驻一个轻量（LIGHT）模型实例，
 * 每次评估发起一次带硬超时的 {@code stream} 调用，解析结构化 JSON，<b>任何失败/不确定一律
 * 保守判为"超出范围"</b>（withinScope=false），把决定权交回人工确认。</p>
 *
 * <p>注意：本智能体只做"范围判定"这一件事，不调用任何工具、不读写文件，纯推理。最终是否
 * 自动放行还要经 {@code ToolConfirmationManager} 的确定性路径包含校验（智能体给出的
 * affectedPaths 必须全部落在 workDir 内），避免单凭模型一句话放行带来的注入/幻觉风险。</p>
 */
public final class LlmToolScopeAssessor implements ToolScopeAssessor {

    private static final Logger log = LoggerFactory.getLogger(LlmToolScopeAssessor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次评估硬超时：托管任务确认本就阻塞，但不应让一次范围评估拖过 12s */
    private static final Duration ASSESS_TIMEOUT = Duration.ofSeconds(12);

    private static final String SYS_PROMPT = """
            你是工具操作"影响范围"评估器。给定一个高风险工具操作和任务的工作目录(workDir)，
            判断该操作的副作用（写入/修改/移动/删除文件、命令执行产生的改动）是否**完全限定**在
            workDir 及其子目录内。

            判定为 withinScope=true 的充分必要条件：
            - 操作写入/修改/移动/删除的所有路径都位于 workDir 内部；
            - 或命令仅在 workDir 内运行、且不触及 workDir 之外的任何路径。

            必须判 withinScope=false（保守）的情形：
            - 触及 workDir 之外的绝对路径（如 /etc、/usr、其他项目目录）；
            - 含 `..` 跳出 workDir、`~` 或家目录、根目录、系统/全局路径；
            - 删除整盘、递归删大范围、改系统配置、安装/卸载全局软件；
            - 访问网络/远程、向外发送数据；
            - 任何你无法确定其落点的路径或命令。

            affectedPaths：列出该操作会写入/修改/删除的所有文件或目录路径。相对路径请相对 workDir
            解析为绝对路径。命令若仅在 workDir 内运行且无显式外部路径，可返回空数组 []。

            只输出 JSON，不要任何额外文字：
            {"withinScope": true, "affectedPaths": ["/abs/path/a", "/abs/path/b"], "reason": "简短中文理由"}
            """;

    private final ChatModelBase model;
    private final GenerateOptions generateOptions;
    private final TokenTracker tokenTracker;

    public LlmToolScopeAssessor(ModelFactory modelFactory, TokenTracker tokenTracker) {
        // 轻量档已强制关闭 thinking，适合这类一次性分类/判定任务
        this.model = modelFactory.createLightChatModel();
        this.tokenTracker = tokenTracker;
        this.generateOptions = GenerateOptions.builder().build();
    }

    @Override
    public ScopeVerdict assess(String toolName, String description, String workDir) {
        if (workDir == null || workDir.isBlank()) {
            return ScopeVerdict.outOfScope("缺少工作目录基准，无法判定范围");
        }
        try {
            String userText = "workDir: " + workDir
                    + "\n工具: " + toolName
                    + "\n操作: " + (description == null ? "" : description);

            Msg sysMsg = Msg.builder().role(MsgRole.SYSTEM).name("system").textContent(SYS_PROMPT).build();
            Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(userText).build();

            StringBuilder responseText = new StringBuilder();
            List<ChatResponse> responses = model.stream(
                    List.of(sysMsg, userMsg), List.of(), generateOptions
            ).collectList().block(ASSESS_TIMEOUT);

            if (responses != null) {
                for (ChatResponse resp : responses) {
                    if (resp.getContent() != null) {
                        for (ContentBlock block : resp.getContent()) {
                            if (block instanceof TextBlock tb && tb.getText() != null) {
                                responseText.append(tb.getText());
                            }
                        }
                    }
                }
            }
            if (tokenTracker != null) {
                long[] usage = TokenTracker.extractUsage(responses);
                tokenTracker.recordModelUsage("ToolScopeAssessor", usage[0], usage[1]);
            }

            return parse(responseText.toString().trim());
        } catch (Exception e) {
            log.warn("[风险评估] 范围评估调用失败，保守转人工: {}", e.getMessage());
            return ScopeVerdict.outOfScope("评估异常：" + e.getMessage());
        }
    }

    private ScopeVerdict parse(String raw) {
        try {
            String json = raw;
            if (json.startsWith("```")) {
                int start = json.indexOf('{');
                int end = json.lastIndexOf('}');
                if (start >= 0 && end > start) json = json.substring(start, end + 1);
            }
            JsonNode root = MAPPER.readTree(json);
            boolean withinScope = root.path("withinScope").asBoolean(false);
            String reason = root.path("reason").asText("");

            List<String> paths = new ArrayList<>();
            JsonNode pathsNode = root.get("affectedPaths");
            if (pathsNode != null && pathsNode.isArray()) {
                for (JsonNode p : pathsNode) {
                    if (p.isTextual() && !p.asText().isBlank()) paths.add(p.asText().trim());
                }
            }
            return new ScopeVerdict(withinScope, paths, reason);
        } catch (Exception e) {
            log.warn("[风险评估] 解析评估结果失败，保守转人工: {}", e.getMessage());
            return ScopeVerdict.outOfScope("评估结果解析失败");
        }
    }
}
