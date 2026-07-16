package com.javaclaw.task.sdd.agent;

import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.agent.model.ModelTier;
import com.javaclaw.agent.model.StructuredCalls;
import com.javaclaw.prompt.SddPrompts;
import com.javaclaw.task.TaskTokenHook;
import com.javaclaw.task.ValidationInspectionTools;
import com.javaclaw.task.sdd.SddTokenSink;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.verify.CriticJudge;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@link CriticJudge} 的 AgentScope 实现 —— 描述性场景（freeform/external_check）的判定器。
 *
 * <p>用一个带<b>只读</b>自检工具（{@link ValidationInspectionTools}）的 critic 智能体，
 * 进工作目录核查后结构化裁定通过/不通过。取代 v5 ChallengerAgent 作为"阶段"的角色——
 * 收敛为统一验证机制（{@code ScenarioVerifier}）下的一个判定后端。</p>
 *
 * <p>判定异常/超时一律保守判为<b>不通过</b>，绝不默认放行。</p>
 *
 * @author JavaClaw
 */
public final class AgentScopeCriticJudge implements CriticJudge {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeCriticJudge.class);

    private final String workDir;
    private final ModelFactory modelFactory;
    private final SddTokenSink tokenSink;
    private long timeoutSec = 120;

    public AgentScopeCriticJudge(String workDir, ModelFactory modelFactory, SddTokenSink tokenSink) {
        this.workDir = workDir;
        this.modelFactory = modelFactory;
        this.tokenSink = tokenSink == null ? SddTokenSink.NOOP : tokenSink;
    }

    private AutoContextMemory buildMemory() {
        return modelFactory.defaultAutoContextMemory();
    }

    public AgentScopeCriticJudge timeoutSec(long s) { this.timeoutSec = s; return this; }

    @Override
    public Verdict judge(Scenario s) {
        try {
            Toolkit toolkit = new Toolkit();
            if (workDir != null && !workDir.isBlank()) {
                toolkit.registerTool(new ValidationInspectionTools(workDir));
            }
            ReActAgent critic = ReActAgent.builder()
                    .name("验收-critic")
                    .sysPrompt(SddPrompts.CRITIC_SYS_PROMPT)
                    .model(modelFactory.createStructuredChatModel(ModelTier.HIGH))
                    .maxIters(5)
                    .toolkit(toolkit)
                    .memory(buildMemory())
                    .hooks(List.of(new TaskTokenHook((i, o) -> tokenSink.record("verify", i, o))))
                    .enablePendingToolRecovery(true)
                    .build();

            String user = "请判定以下验收场景是否成立：\n"
                    + "场景：" + s.title() + "\n"
                    + "Given：" + nz(s.given()) + "\n"
                    + "When：" + nz(s.when()) + "\n"
                    + "Then：" + nz(s.then()) + "\n"
                    + "判据：" + (s.criterion() == null ? "" : s.criterion().predicate()) + "\n"
                    + "工作目录：" + workDir;

            Msg result = StructuredCalls.blockingCall(critic, user,
                    SddDrafts.CriticVerdictDraft.class, timeoutSec, "critic 判定");
            SddDrafts.CriticVerdictDraft d =
                    StructuredCalls.extractStructured(result, SddDrafts.CriticVerdictDraft.class);
            if (d == null) return new Verdict(false, "critic 未产出结构化判定（保守判不通过）");
            return new Verdict(d.pass, nz(d.reason));
        } catch (Exception e) {
            log.warn("[Verify] critic 判定异常（保守判不通过）：{}", e.getMessage());
            return new Verdict(false, "critic 判定异常：" + e.getMessage());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
