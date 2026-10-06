package com.javaclaw.task.sdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.framework.core.TaskEvidenceCollector;
import com.javaclaw.framework.core.TaskResultEvaluator;
import com.javaclaw.framework.core.TrustedCapabilityRegistry;
import com.javaclaw.framework.spi.FileContentProof;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;
import com.javaclaw.task.sdd.spec.Criterion;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.SpecStore;
import com.javaclaw.task.sdd.spec.TaskItem;
import com.javaclaw.task.sdd.verify.VerificationOutcome;
import com.javaclaw.util.ProjectAccessPolicy;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Bridges approved SDD artifacts to real Framework file receipts, never model summaries. */
final class SddAcceptanceEvidence {
    private static final int MAX_CONTENT_CHARACTERS = 4 * 1024 * 1024;
    private final TaskContext context;
    private final SpecStore specs;
    private final RunStore runs;
    private final ObjectMapper json;
    private final String workspaceId;
    private final TrustedCapabilityRegistry capabilities = TrustedCapabilityRegistry.builtins();

    SddAcceptanceEvidence(TaskContext context, SpecStore specs, RunStore runs,
                          ObjectMapper json, String workspaceId) {
        this.context = context;
        this.specs = specs;
        this.runs = Objects.requireNonNull(runs, "runs");
        this.json = json;
        this.workspaceId = workspaceId;
    }

    boolean record(TaskItem item, String basis, String executionId) {
        if (basis.isBlank() || executionId == null || executionId.isBlank()) return false;
        try {
            StoredRun child = boundChild(new RunId(executionId), null, item.index(), basis);
            if (child == null || !verified(child).outcome().equals(TaskOutcome.VERIFIED_COMPLETE)) return false;
            return specs.recordImplementationEvidence(context.slug(), context.description(), basis,
                    item.index(), executionId, child.request().linkage().parentRunId().value());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    TaskResult accept(List<VerificationOutcome> outcomes, BooleanSupplier cancelled) {
        try {
            checkCancelled(cancelled);
            String basis = specs.implementationBasis(context.slug(), context.description());
            OpenSpecChange change = specs.readChange(context.slug(), context.id(), context.title());
            if (basis.isBlank() || !change.allTasksDone()) return unverified("已批准实现计划缺失或已变更");
            if (outcomes.isEmpty() || outcomes.size() != change.allScenarios().size()
                    || !outcomes.stream().map(VerificationOutcome::scenario).toList().equals(change.allScenarios())
                    || outcomes.stream().anyMatch(outcome -> !outcome.passed() || !outcome.deterministic()
                    || outcome.scenario().criterion() == null
                    || !Criterion.ARTIFACT_EXISTS.equals(outcome.scenario().criterion().normalizedType()))) {
                // Critic/command conclusions remain workflow gates; they are not file effect receipts.
                return unverified("当前场景缺少可关联的宿主文件收据，不能由评审结论晋升为已核验");
            }
            JsonNode associations = specs.implementationEvidence(context.slug(), basis).path("items");
            Set<String> usedRuns = new LinkedHashSet<>();
            Set<String> refs = new LinkedHashSet<>();
            Set<Path> provenFiles = new LinkedHashSet<>();
            for (TaskItem item : change.tasks()) {
                checkCancelled(cancelled);
                JsonNode association = associations.path(Integer.toString(item.index()));
                String id = association.path("runId").asText();
                String parentId = association.path("parentRunId").asText();
                if (id.isBlank() || parentId.isBlank() || !usedRuns.add(id)) {
                    return unverified("实现项 #" + item.index() + " 缺少唯一的持久执行收据关联");
                }
                StoredRun child = boundChild(new RunId(id), new RunId(parentId), item.index(), basis);
                if (child == null) return unverified("实现项 #" + item.index() + " 的执行身份或批准指纹不匹配");
                TaskResult result = verified(child);
                if (result.outcome() != TaskOutcome.VERIFIED_COMPLETE || result.evidenceRefs().isEmpty()) {
                    return unverified("实现项 #" + item.index() + " 尚无可信完成结论");
                }
                List<RunEventEnvelope> events = TaskEvidenceCollector.collect(runs, child.snapshot().id());
                var contract = TaskResultEvaluator.latestContractV3(events.stream()
                        .filter(event -> event.runId().equals(id)).toList(), json).orElse(null);
                if (contract == null || contract.criteria().isEmpty()
                        || contract.criteria().stream().anyMatch(criterion ->
                        criterion.targetType() != CapabilityMetadata.TargetKind.FILE
                                || !(criterion.capabilityId().equals("file.write")
                                || criterion.capabilityId().equals("file.read")))) {
                    return unverified("实现项 #" + item.index() + " 含尚未支持关联的非文件验收条件");
                }
                Set<Path> itemFiles = new LinkedHashSet<>();
                for (var criterion : contract.criteria()) {
                    // Frozen FILE targets are absolute; never reinterpret a historical relative target.
                    if (!Path.of(criterion.target()).isAbsolute()) return unverified("冻结文件目标不是绝对路径");
                    Path path = resolve(criterion.target());
                    String current = currentContent(path, cancelled);
                    if (!criterion.requiredSubject().isBlank()
                            && !FileContentProof.matches(criterion.requiredSubject(),
                            json.valueToTree(FileContentProof.metadata(current)))) {
                        return unverified("文件现状已不满足冻结内容条件：" + path.getFileName());
                    }
                    itemFiles.add(path);
                }
                Set<String> matchedRefs = new LinkedHashSet<>();
                for (RunEventEnvelope event : events) {
                    if (!event.type().equals("core.tool.receipt") || event.schemaVersion() != 1
                            || !event.producer().equals("framework.core")) continue;
                    JsonNode receipt = event.payload();
                    String ref = receipt.path("evidenceRef").asText();
                    if (!result.evidenceRefs().contains(ref)) continue;
                    var capability = capabilities.forReceipt(receipt.path("tool").asText(),
                            receipt.path("operation").asText()).orElse(null);
                    if (capability == null || !(capability.id().equals("file.write")
                            || capability.id().equals("file.read"))) return unverified("存在非文件收据引用");
                    Path path = resolve(receipt.path("target").asText());
                    if (!itemFiles.contains(path)
                            || !currentProofMatches(currentContent(path, cancelled), receipt.path("metadata"))) {
                        return unverified("文件已变化或收据缺少可复核的内容证明：" + path.getFileName());
                    }
                    matchedRefs.add(ref);
                }
                if (!matchedRefs.containsAll(result.evidenceRefs())) return unverified("可信收据引用未能完整重读");
                for (String file : item.files()) {
                    if (!itemFiles.contains(resolve(file))) return unverified("声明产物缺少冻结条件与收据覆盖");
                }
                // Recheck tombstones after journal/file reads; deletion cannot grant a positive result.
                if (boundChild(child.snapshot().id(), new RunId(parentId), item.index(), basis) == null)
                    return unverified("执行来源已不可读取");
                provenFiles.addAll(itemFiles);
                refs.addAll(matchedRefs);
            }
            for (VerificationOutcome outcome : outcomes) {
                if (!provenFiles.contains(resolve(outcome.scenario().criterion().predicate())))
                    return unverified("场景产物未被实际实现收据覆盖：" + outcome.scenario().title());
            }
            checkCancelled(cancelled);
            if (!basis.equals(specs.implementationBasis(context.slug(), context.description())))
                return unverified("验收过程中批准计划已变更");
            return new TaskResult(TaskOutcome.VERIFIED_COMPLETE, List.of(), "", List.copyOf(refs),
                    outcomes.stream().map(outcome -> outcome.scenario().title()).toList());
        } catch (Exception ignored) {
            return unverified("关联收据或文件现状无法可靠复核");
        }
    }

    private TaskResult verified(StoredRun child) {
        if (TaskEvidenceCollector.hasNonTerminalDescendant(runs, child.snapshot().id()))
            return unverified("实现子轮次仍有未结束的后代");
        List<RunEventEnvelope> own = runs.eventsAfter(child.snapshot().id(), 0);
        TaskResult durable = TaskResultEvaluator.latestOutcome(own, json).orElse(null);
        var contract = TaskResultEvaluator.latestContractV3(own, json).orElse(null);
        if (durable == null || durable.outcome() != TaskOutcome.VERIFIED_COMPLETE
                || durable.evidenceRefs().isEmpty() || contract == null) return unverified("实际执行尚未核验");
        TaskResult checked = TaskResultEvaluator.evaluateV3(contract,
                TaskEvidenceCollector.collect(runs, child.snapshot().id()), "", capabilities);
        if (checked.outcome() != TaskOutcome.VERIFIED_COMPLETE) return checked;
        return Set.copyOf(durable.evidenceRefs()).equals(Set.copyOf(checked.evidenceRefs()))
                ? durable : unverified("完成结论的收据与冻结条件无法精确关联");
    }

    private StoredRun boundChild(RunId id, RunId recordedParent, int index, String basis) {
        StoredRun child = runs.find(id).orElse(null);
        RunScope expected = new RunScope(workspaceId, "local-user", "sdd:" + context.id() + ":implement");
        if (child == null || child.snapshot().state() != RunState.COMPLETED
                || !child.request().scope().equals(expected) || !runs.readable(expected)
                || !child.request().source().equals(InvocationSource.sdd(context.id()))) return null;
        var attrs = child.request().attributes();
        if (!attrs.containsKey("framework.disableTools")
                || !attrs.get("framework.disableTools").isBoolean()
                || attrs.get("framework.disableTools").asBoolean(true)
                || !attrs.getOrDefault("sdd.implementationBasis", json.nullNode()).isTextual()
                || !basis.equals(attrs.getOrDefault("sdd.implementationBasis", json.nullNode()).asText())
                || !attrs.getOrDefault("sdd.implementationItem", json.nullNode()).isIntegralNumber()
                || !attrs.getOrDefault("sdd.implementationItem", json.nullNode()).canConvertToInt()
                || index != attrs.getOrDefault("sdd.implementationItem", json.nullNode()).asInt()
                || !"implement".equals(attrs.getOrDefault("sdd.phase", json.nullNode()).asText())
                || !resolve(attrs.getOrDefault("workDir", json.nullNode()).asText()).equals(resolve(context.workDir()))) return null;
        RunId parentId = child.request().linkage().parentRunId();
        if (parentId == null || recordedParent != null && !recordedParent.equals(parentId)) return null;
        StoredRun parent = runs.find(parentId).orElse(null);
        if (parent == null || !parent.request().scope().workspaceId().equals(workspaceId)
                || !parent.request().scope().userId().equals("local-user")
                || !SddThreadGuard.coordinators(workspaceId, context.id())
                        .contains(parent.request().scope().sessionId())
                || !runs.readable(parent.request().scope())) return null;
        return child;
    }

    private Path resolve(String target) {
        if (target == null || target.isBlank()) throw new IllegalArgumentException("missing file target");
        Path path = Path.of(target.strip());
        if (!path.isAbsolute()) path = Path.of(context.workDir()).resolve(path);
        return ProjectAccessPolicy.requireProjectFilePath(path.toAbsolutePath().normalize());
    }

    private String currentContent(Path path, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IllegalArgumentException("not a regular file");
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
        if (before.size() > MAX_CONTENT_CHARACTERS) throw new IllegalArgumentException("file proof too large");
        StringBuilder content = new StringBuilder();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            for (int length; (length = reader.read(buffer)) >= 0;) {
                checkCancelled(cancelled);
                if (content.length() + length > MAX_CONTENT_CHARACTERS) throw new IllegalArgumentException("file proof too large");
                content.append(buffer, 0, length);
            }
        }
        BasicFileAttributes after = Files.readAttributes(resolve(path.toString()), BasicFileAttributes.class);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) throw new IllegalStateException("file changed while observing");
        return content.toString();
    }

    private static boolean currentProofMatches(String current, JsonNode metadata) {
        // Even an intentionally empty file has a concrete host hash; it is not a wildcard subject.
        return FileContentProof.metadata(current).entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(metadata.path(entry.getKey()).asText()));
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IllegalStateException("SDD cancelled");
    }

    private static TaskResult unverified(String reason) {
        return TaskResult.unverified("SDD 关联验收未确认：" + reason);
    }
}
