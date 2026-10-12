package com.javaclaw.task.sdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.FileContentProof;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.task.sdd.spec.*;
import com.javaclaw.task.sdd.verify.VerificationOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SddAcceptanceEvidenceTest {
    @TempDir Path temp;

    @Test
    void 真实引擎终态证据快照关联已批准计划并重读实际文件() throws Exception {
        try (Fixture fixture = new Fixture(temp)) {
            var evidence = fixture.evidence(fixture.engine);
            var before = fixture.engine.get(fixture.child);
            assertTrue(evidence.record(fixture.item, fixture.basis, fixture.child.value()));
            TaskResult result = evidence.accept(fixture.outcomes(), () -> false);
            assertEquals(TaskOutcome.VERIFIED_COMPLETE, result.outcome(), result.stopReason());
            assertEquals(List.of("core.tool.completed:" + fixture.child.value() + ":write"), result.evidenceRefs());
            assertEquals(before, fixture.engine.get(fixture.child), "证据查询不得推进任务或修改持久化版本");
        }
    }

    @Test
    void 文件在完成后改变不能沿用已核验结论() throws Exception {
        try (Fixture fixture = new Fixture(temp)) {
            var evidence = fixture.evidence(fixture.engine);
            assertTrue(evidence.record(fixture.item, fixture.basis, fixture.child.value()));
            Files.writeString(fixture.artifact, "changed after receipt");
            assertEquals(TaskOutcome.UNVERIFIED, evidence.accept(fixture.outcomes(), () -> false).outcome());
        }
    }

    @Test
    void 宿主未提供证据快照时终态及持久化收据均不能自行晋升验收() throws Exception {
        try (Fixture fixture = new Fixture(temp)) {
            assertTrue(fixture.evidence(fixture.engine).record(fixture.item, fixture.basis, fixture.child.value()));
            AgentClient unavailable = (AgentClient) Proxy.newProxyInstance(AgentClient.class.getClassLoader(),
                    new Class<?>[]{AgentClient.class}, (proxy, method, args) -> {
                        if (method.getName().equals("taskEvidence")) return Optional.empty();
                        try { return method.invoke(fixture.engine, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            var evidence = fixture.evidence(unavailable);
            assertFalse(evidence.record(fixture.item, fixture.basis, fixture.child.value()));
            assertEquals(TaskOutcome.UNVERIFIED, evidence.accept(fixture.outcomes(), () -> false).outcome());
        }
    }

    @Test
    void 父线程删除标记及批准计划修订都会使既有关联失效() throws Exception {
        try (Fixture fixture = new Fixture(temp)) {
            var evidence = fixture.evidence(fixture.engine);
            assertTrue(evidence.record(fixture.item, fixture.basis, fixture.child.value()));
            fixture.runs.threads().markDeleting(fixture.parentScope);
            assertEquals(TaskOutcome.UNVERIFIED, evidence.accept(fixture.outcomes(), () -> false).outcome());
            assertFalse(evidence.record(fixture.item, fixture.basis, fixture.child.value()));
        }
        try (Fixture fixture = new Fixture(temp.resolve("revision"))) {
            var evidence = fixture.evidence(fixture.engine);
            assertTrue(evidence.record(fixture.item, fixture.basis, fixture.child.value()));
            assertTrue(fixture.specs.writeDesign(fixture.context.slug(), "different approved design required"));
            assertEquals(TaskOutcome.UNVERIFIED, evidence.accept(fixture.outcomes(), () -> false).outcome());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Clock clock = Clock.systemUTC();
        private final ObjectMapper json;
        private final JdbcRunStore runs;
        private final ExtensionManager extensions;
        private final AgentEngine engine;
        private final SpecStore specs;
        private final TaskContext context;
        private final TaskItem item;
        private final Scenario scenario;
        private final Path artifact;
        private final String basis;
        private final RunId child;
        private final RunScope parentScope;

        Fixture(Path root) throws Exception {
            Path target = Files.createDirectories(com.javaclaw.util.ProjectAccessPolicy.projectRoot().resolve("target"));
            Path work = Files.createTempDirectory(target, "sdd-acceptance-").toAbsolutePath();
            artifact = work.resolve("ready.txt");
            SddTestDatabase database = new SddTestDatabase(root.resolve("database"));
            json = database.json().mapper();
            runs = new JdbcRunStore(database.jdbc(), database.transactions(), json, clock);
            var definitions = new JdbcAgentDefinitionStore(database.jdbc(), database.transactions(), json, clock);
            var plans = new JdbcExecutionPlanStore(database.jdbc(), json, clock);
            var agent = new AgentDefinitionDraft("sdd.test", "SDD test", "test:model", Map.of(), Map.of(),
                    json.createObjectNode(), json.createObjectNode(), RunBudget.UNBOUNDED,
                    json.createObjectNode(), Map.of());
            definitions.saveAgentDraft("workspace", agent, false);
            definitions.publishAgent("workspace", agent.id());
            var profile = new RunProfileDraft("sdd.test", "SDD test", PermissionSet.UNRESTRICTED,
                    RunBudget.UNBOUNDED, Map.of(), json.createObjectNode());
            definitions.saveProfileDraft("workspace", profile, false);
            definitions.publishProfile("workspace", profile.id());
            context = new TaskContext("acceptance-test", "文件验收", "写入 ready.txt", work.toString(), "system");
            item = new TaskItem(1, "写入文件", List.of(artifact.toString()), "文件内容已核验", true);
            scenario = new Scenario("产物存在", "", "", "ready.txt exists",
                    new Criterion(Criterion.ARTIFACT_EXISTS, artifact.toString()));
            specs = new SpecStore(work.toString(), database.jdbc(), "workspace", json);
            assertTrue(specs.writeProposal(context.slug(), context.title(), new Proposal("需要产物", "写入文件", "")));
            assertTrue(specs.writeCapabilitySpecs(context.slug(), List.of(new Capability("artifact",
                    List.of(new Requirement("产物", List.of(scenario)))))));
            assertTrue(specs.writeDesign(context.slug(), "写入并验证内容"));
            assertTrue(specs.writeTasks(context.slug(), List.of(item)));
            for (var stage : SpecStore.PreparationStage.values())
                assertTrue(specs.completePreparation(context.slug(), stage, specs.preparationRevision(context.slug(), stage)));
            basis = specs.implementationBasis(context.slug(), context.description());
            assertFalse(basis.isBlank());
            extensions = new ExtensionManager(new ExtensionContext(clock, Runnable::run,
                    request -> CompletableFuture.failedFuture(new AssertionError("不得调用外部模型"))));
            engine = new AgentEngine(new AgentCompiler(definitions, extensions, json), runs, plans,
                    request -> {
                        if (!request.runRequest().attributes().containsKey("sdd.phase"))
                            return CompletableFuture.completedFuture(ReasoningResult.waitingForInput(
                                    json.createObjectNode().put("question", "coordinator"), "wait"));
                        try { Files.writeString(artifact, "ready"); }
                        catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
                        var receipt = json.createObjectNode().put("tool", "sys_file_write").put("operation", "write")
                                .put("invocationId", "write").put("target", artifact.toString()).put("status", "VERIFIED")
                                .put("observedAt", clock.instant().toString())
                                .put("evidenceRef", "core.tool.completed:" + request.runId().value() + ":write");
                        receipt.set("metadata", json.valueToTree(FileContentProof.metadata("ready")));
                        request.events().emit("core.tool.receipt", 1, "framework.core", receipt);
                        submitClaim(request, json);
                        return CompletableFuture.completedFuture(ReasoningResult.completed(json.createObjectNode().put("text", "ready")));
                    }, Runnable::run, json, clock, new RunUsageLedger(),
                    request -> CompletableFuture.failedFuture(new AssertionError("显式合同不调用规划模型")));
            parentScope = new RunScope("workspace", "local-user", SddThreadGuard.coordinator("workspace", context.id(), false));
            var parent = engine.start(request(parentScope).withAttribute(TaskContractCompiler.ATTRIBUTE,
                    json.valueToTree(new TaskContractV3(3, "coordination", List.of(), false, true, "definition"))));
            assertEquals(RunState.WAITING_INPUT, engine.get(parent.id()).state());
            var contract = new TaskContractV3(3, context.description(), List.of(new TaskCriterionV3("file", "文件已写入",
                    "file.write", CapabilityMetadata.TargetKind.FILE, artifact.toString(), EffectReceiptV1.Status.VERIFIED, "")),
                    true, true, "definition");
            var childRequest = request(new RunScope("workspace", "local-user", "sdd:" + context.id() + ":implement"));
            childRequest = new RunRequest(childRequest.agent(), childRequest.profile(), InvocationSource.sdd(context.id()),
                    childRequest.scope(), childRequest.inputs(), new RunLinkage(parent.id(), null, "sdd-acceptance"),
                    childRequest.permissionCeiling(), childRequest.budget(), null, Map.of(
                            TaskContractCompiler.ATTRIBUTE, json.valueToTree(contract), "framework.disableTools", json.valueToTree(false),
                            "sdd.phase", json.valueToTree("implement"), "sdd.implementationBasis", json.valueToTree(basis),
                            "sdd.implementationItem", json.valueToTree(1), "workDir", json.valueToTree(work.toString())));
            child = engine.start(childRequest).id();
            assertEquals(RunState.COMPLETED, engine.get(child).state(), engine.get(child).error());
            assertEquals(TaskOutcome.VERIFIED_COMPLETE, engine.taskEvidence(child).orElseThrow().result().outcome());
        }

        private RunRequest request(RunScope scope) {
            return RunRequest.builder().agent(AgentDefinitionRef.latest("sdd.test"))
                    .profile(RunProfileRef.latest("sdd.test")).source(InvocationSource.sdd(context.id()))
                    .scope(scope).input(InputBlock.text(context.description())).permissionCeiling(PermissionSet.UNRESTRICTED)
                    .budget(RunBudget.UNBOUNDED).build();
        }

        SddAcceptanceEvidence evidence(AgentClient client) {
            return new SddAcceptanceEvidence(context, specs, runs, json, "workspace", client);
        }
        List<VerificationOutcome> outcomes() { return List.of(VerificationOutcome.pass(scenario, true, "exists")); }
        @Override public void close() throws Exception {
            engine.close();
            extensions.close();
            Files.deleteIfExists(artifact);
            Files.delete(artifact.getParent());
        }
    }

    private static void submitClaim(ReasoningRequest request, ObjectMapper json) {
        StepId model = StepId.random();
        StepEvents.started(request.events(), model, AgentStep.Kind.MODEL, json.createObjectNode(), null);
        StepEvents.completed(request.events(), model, json.createObjectNode(), null);
        String invocation = "decision";
        StepId control = StepId.tool(request.runId(), invocation);
        var input = json.createObjectNode().put("phase", "harness.decision")
                .put("modelStepId", model.value()).put("invocationId", invocation);
        StepEvents.started(request.events(), control, AgentStep.Kind.ORCHESTRATION, input, model.value());
        var submitted = json.createObjectNode().put("modelStepId", model.value()).put("invocationId", invocation);
        submitted.set("value", new ModelDecisionV1(ModelDecisionV1.Decision.CLAIM_DONE, "ready", List.of()).toJson());
        request.events().emit("core.harness.decision_submitted", 1, "framework.springai", submitted);
        StepEvents.completed(request.events(), control, json.createObjectNode(), null);
    }
}
