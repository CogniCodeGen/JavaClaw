package com.javaclaw.agent.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.javaclaw.agent.prompt.PromptCatalog;
import com.javaclaw.agent.prompt.PromptCompiler;
import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.runtime.ThreadUseCases;
import com.javaclaw.agent.runtime.TurnUseCases;
import com.javaclaw.agent.runtime.WorkspaceUseCases;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 复用正常 Turn 调度的提示词管理服务；优化输入与主任务历史隔离，执行结果不能自动覆盖配置。 */
public final class ProfilePromptService implements ProfilePromptUseCases {
    private final ProfileUseCases profiles;
    private final WorkspaceUseCases workspaces;
    private final ThreadUseCases threads;
    private final TurnUseCases turns;
    private final Supplier<List<ToolDescriptor>> catalog;
    private final PromptCompiler compiler = new PromptCompiler(new PromptCatalog());

    /** 绑定窄用例及非联网能力目录；不持有 Provider、数据库或 UI 对象。 */
    public ProfilePromptService(
            ProfileUseCases profiles,
            WorkspaceUseCases workspaces,
            ThreadUseCases threads,
            TurnUseCases turns,
            Supplier<List<ToolDescriptor>> catalog) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public Preview preview(String profileId, WorkspaceId workspaceId) {
        var workspace = workspaces
                .readWorkspace(workspaceId)
                .orElseThrow(() -> new NoSuchElementException("workspace not found"));
        var resolved = profiles.resolve(profileId, workspace, ApprovalPolicy.NEVER, null);
        List<ToolDescriptor> available = catalog.get().stream()
                .filter(tool -> resolved.profile().enabledTools().isEmpty()
                        || resolved.profile().enabledTools().contains(tool.name()))
                .toList();
        var purpose = PromptCompiler.forProfile(resolved.turnConfig());
        var compiled = compiler.compile(
                purpose,
                resolved.turnConfig(),
                available,
                List.of(),
                com.javaclaw.agent.prompt.AgentsInstructionResolution.empty(workspace.root()),
                List.of(new ModelMessage(ModelMessage.Role.USER, "预览提示词构成，不执行任务。", null)));
        var warnings = new ArrayList<String>();
        warnings.add("预览只显示已知目录；实际 Turn 会重新确认工具、插件版本与沙箱状态，预览不授予权限。");
        for (String requested : resolved.profile().enabledTools()) {
            if (available.stream().noneMatch(tool -> tool.name().equals(requested))) {
                warnings.add("目录中未确认可用的工具：" + requested);
            }
        }
        return new Preview(
                profileId,
                resolved.profile().revision(),
                resolved.profile().systemPrompt(),
                purpose.name(),
                compiled.snapshot().templates().stream()
                        .map(layer -> new Layer(layer.id(), layer.version(), layer.sha256()))
                        .toList(),
                available.stream().map(ToolDescriptor::name).sorted().toList(),
                warnings);
    }

    @Override
    public AgentTurn optimize(
            ThreadId threadId, String profileId, String draft, long expectedRevision, String idempotencyKey) {
        if (draft == null
                || draft.length() > 50_000
                || expectedRevision < 1
                || idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 256) {
            throw new IllegalArgumentException("bounded draft, revision and idempotencyKey are required");
        }
        String requestHash =
                PromptHashes.sequence(List.of(threadId.value(), profileId, draft, Long.toString(expectedRevision)));
        String scopedKey = "prompt-optimize-" + PromptHashes.sha256(idempotencyKey);
        var previous = threads.readTurnByIdempotencyKey(threadId, scopedKey);
        if (previous.isPresent()) {
            if (!requestHash.equals(previous.get().config().attributes().get("promptRequestHash"))) {
                throw new IllegalStateException("idempotency key was reused with different prompt input");
            }
            return previous.get();
        }
        var snapshot = threads.readThread(threadId).orElseThrow(() -> new NoSuchElementException("thread not found"));
        var workspace = workspaces
                .readWorkspace(new WorkspaceId(snapshot.thread().workspaceId()))
                .orElseThrow();
        var resolved = profiles.resolve(profileId, workspace, ApprovalPolicy.NEVER, null);
        var profile = resolved.profile();
        if (profile.revision() != expectedRevision) {
            throw new IllegalStateException("Profile revision conflict; reload before optimizing");
        }
        var preview = preview(profileId, workspace.id());
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("profileId", profile.id());
        attributes.put("profileRevision", Long.toString(profile.revision()));
        attributes.put("profileKind", "CHAT");
        attributes.put("invocationPurpose", "PROMPT_OPTIMIZATION");
        attributes.put("promptRequestHash", requestHash);
        attributes.put(
                "maxModelCalls",
                Integer.toString(Math.min(2, profile.maxModelCalls() == 0 ? 2 : profile.maxModelCalls())));
        attributes.put(
                "maxTokens",
                Long.toString(
                        Math.min(100_000, positiveLimit(profile.attributes().get("maxTokens"), 100_000))));
        attributes.put("maxDurationSeconds", "300");
        attributes.put("maxOutputTokens", "4096");
        var config = new TurnConfig(
                profile.model(),
                profile.provider(),
                resolved.turnConfig().reasoningEffort(),
                snapshot.thread().workingDirectory(),
                SandboxPolicy.readOnly(
                        Set.of(snapshot.thread().workingDirectory()),
                        resolved.turnConfig().sandboxPolicy().protectedRoots()),
                ApprovalPolicy.NEVER,
                Set.of(),
                attributes);
        String input = "名称：" + profile.name() + "\n用途：" + profile.kind() + "\n已知能力名称（不是执行授权）："
                + String.join(", ", preview.tools()) + "\n仅可编辑人设、业务流程与回复风格。保留用户目标，未配置能力需提示。\n用户草稿：\n" + draft;
        return turns.startTurn(new TurnStartCommand(threadId, List.of(new TurnInput.Text(input)), config, scopedKey));
    }

    private static long positiveLimit(String value, long inherited) {
        if (value == null || "0".equals(value)) {
            return inherited;
        }
        long parsed = Long.parseLong(value);
        if (parsed < 1) {
            throw new IllegalArgumentException("token budget must be finite and positive");
        }
        return parsed;
    }
}
