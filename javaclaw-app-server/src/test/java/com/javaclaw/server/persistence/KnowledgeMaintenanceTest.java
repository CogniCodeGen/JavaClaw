package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.context.AttachmentInputResolver;
import com.javaclaw.agent.kernel.AgentLoopKernel;
import com.javaclaw.agent.knowledge.KnowledgeMaintenanceExecution;
import com.javaclaw.agent.knowledge.KnowledgeMaintenanceScheduler;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeMaintenanceTest {
    @TempDir
    Path temporary;

    @Test
    void learnsOnlyWithEvidenceThroughOneBoundedTurnAndNeverRecursesOrRetriesAfterCompletion() throws Exception {
        var calls = new AtomicInteger();
        var userSource = new AtomicReference<String>();
        var commandSource = new AtomicReference<String>();
        try (var store = new H2Persistence(temporary.resolve("data"))) {
            var knowledge = new H2KnowledgeRepository(store.database());
            var jobs = new H2KnowledgeMaintenanceRepository(store.database());
            var kernel = new AgentLoopKernel(
                    request -> {
                        int invocation = calls.incrementAndGet();
                        assertTrue(request.tools().isEmpty());
                        assertFalse(request.messages().stream()
                                .anyMatch(message -> message.role() == ModelMessage.Role.SYSTEM
                                        && message.content().contains("简体中文回答")));
                        assertTrue(
                                Integer.parseInt(request.config().attributes().get("maxModelCalls")) <= 4);
                        String result =
                                invocation == 1 ? """
                          {"candidates":[{"kind":"FACT","subject":"用户","attribute":"language","content":"简体中文回答",
                            "sources":["%s"],"reason":"用户明确要求"}]}
                          """.formatted(userSource.get()) : """
                          {"candidates":[{"name":"核验改动","instructions":"先执行测试，再报告退出码。",
                            "sources":["%s"],"reason":"已有成功执行证据"}]}
                          """.formatted(commandSource.get());
                        return new ModelResponse(result, "", List.of(), new ModelUsage(100, 50, 0));
                    },
                    (context, sink) -> {
                        throw new AssertionError("维护不能打开工具会话");
                    },
                    List.of(),
                    AttachmentInputResolver.UNAVAILABLE,
                    null,
                    null,
                    new KnowledgeMaintenanceExecution(knowledge, jobs));
            try (var runtime = new DefaultAgentRuntime(
                            store.runtime(),
                            (context, sink) -> {
                                if (context.turn().config().attributes().containsKey("maintenanceSourceTurn")) {
                                    kernel.execute(context, sink);
                                } else {
                                    commandSource.set(sink.append(new ThreadItem.CommandExecution(
                                                    List.of("test"), 0, "all tests passed", "", false, false))
                                            .id()
                                            .value());
                                    sink.append(new ThreadItem.AgentMessage("助手建议不能变成用户事实"));
                                    sink.append(new ThreadItem.CommandExecution(
                                            List.of("bad"), 1, "invalid", "", false, false));
                                }
                            },
                            new RuntimeEventBus());
                    var scheduler = new KnowledgeMaintenanceScheduler(jobs, knowledge, runtime, runtime, runtime)) {
                var workspace = runtime.createWorkspace("workspace", temporary.resolve("workspace"), "workspace");
                var thread = runtime.startThread(workspace.id(), "user");
                var config = new TurnConfig(
                        "fake",
                        "openai",
                        "low",
                        workspace.root(),
                        SandboxPolicy.readOnly(Set.of(workspace.root()), Set.of()),
                        ApprovalPolicy.NEVER,
                        Set.of(),
                        Map.of("profileKind", "CHAT", "maxModelCalls", "10", "maxTokens", "100000"));
                var source = runtime.startTurn(
                        new TurnStartCommand(thread.id(), List.of(new TurnInput.Text("请始终用简体中文回答")), config, "source"));
                await(runtime, source.id());
                var evidence = jobs.evidence(source.id().value(), workspace.id().value());
                assertEquals(2, evidence.size(), "助手自述与失败命令不作为学习来源");
                userSource.set(evidence.stream()
                        .filter(item -> item.kind().equals("userMessage"))
                        .findFirst()
                        .orElseThrow()
                        .itemId());
                scheduler.tick();
                var job = jobs.pending(10).getFirst();
                var maintenance = runtime.readTurnByIdempotencyKey(
                                new com.javaclaw.core.api.ThreadId(job.maintenanceThreadId()),
                                "knowledge-maintenance-" + source.id().value())
                        .orElseThrow();
                await(runtime, maintenance.id());
                assertEquals(
                        com.javaclaw.core.api.TurnStatus.COMPLETED,
                        runtime.readTurn(maintenance.id()).orElseThrow().status());
                scheduler.tick();
                scheduler.tick();
                assertEquals(2, calls.get());
                assertTrue(jobs.pending(10).isEmpty());
                assertEquals(1, knowledge.listMemories(workspace.id().value()).size());
                var proposal = knowledge.skillProposals(workspace.id().value()).getFirst();
                assertEquals("PENDING", proposal.state(), "默认建议模式不能自动安装技能");
                assertTrue(knowledge.listSkills().isEmpty());
                knowledge.reviewSkillProposal(proposal.id(), true, proposal.revision(), "accept");
                assertEquals(1, knowledge.listSkills().size());
                knowledge.saveLearningSettings(workspace.id().value(), "OFF", false, 0, "disable");
                var next = runtime.startTurn(
                        new TurnStartCommand(thread.id(), List.of(new TurnInput.Text("关闭学习之后")), config, "second"));
                await(runtime, next.id());
                scheduler.tick();
                assertEquals(2, calls.get(), "关闭后不应生成额外模型费用");
            }
        }
    }

    private static void await(DefaultAgentRuntime runtime, TurnId turn) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!runtime.readTurn(turn).orElseThrow().status().terminal() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(runtime.readTurn(turn).orElseThrow().status().terminal());
    }
}
