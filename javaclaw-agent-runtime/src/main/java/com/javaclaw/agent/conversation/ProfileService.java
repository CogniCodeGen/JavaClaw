package com.javaclaw.agent.conversation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.agent.model.CompactionThresholds;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ExecutionProfile;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.ResolvedTurnConfig;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** Versioned Profile management and server-side Turn policy resolution. */
public final class ProfileService implements ProfileUseCases {
    private final ProfileRepository repository;
    private final Set<Path> globallyProtectedRoots;

    /** 绑定 Profile 仓库并固定全局保护根；null 保护集合视为空，但工作区内置保护仍会追加。 */
    public ProfileService(ProfileRepository repository, Set<Path> globallyProtectedRoots) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.globallyProtectedRoots = globallyProtectedRoots == null ? Set.of() : Set.copyOf(globallyProtectedRoots);
    }

    @Override
    public List<ExecutionProfile> list() {
        return repository.list();
    }

    @Override
    public ExecutionProfile read(String id) {
        return repository.find(id).orElseThrow(() -> new NoSuchElementException("profile not found: " + id));
    }

    @Override
    public ExecutionProfile put(ProfileRepository.ProfileDraft draft, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(draft, "draft");
        CompactionThresholds.validate(draft.attributes());
        if (draft.kind() == ProfileKind.PLAN && draft.requestedSandboxMode() != SandboxMode.READ_ONLY) {
            throw new IllegalArgumentException("PLAN profiles must be read-only");
        }
        if (draft.requestedSandboxMode() == SandboxMode.HOST_FULL_ACCESS) {
            throw new IllegalArgumentException(
                    "HOST_FULL_ACCESS is a per-Turn interactive approval, not a Profile setting");
        }
        return repository.put(draft, expectedRevision, idempotencyKey);
    }

    @Override
    public boolean delete(String id, long expectedRevision, String idempotencyKey) {
        return repository.delete(id, expectedRevision, idempotencyKey);
    }

    @Override
    public ResolvedTurnConfig resolve(
            String profileId, Workspace workspace, ApprovalPolicy approvalPolicy, String reasoningEffort) {
        ExecutionProfile profile = read(profileId);
        Path root = workspace.root();
        LinkedHashSet<Path> protectedRoots = new LinkedHashSet<>(globallyProtectedRoots);
        protectedRoots.add(root.resolve(".git"));
        protectedRoots.add(root.resolve(".javaclaw"));
        SandboxPolicy policy = profile.requestedSandboxMode() == SandboxMode.WORKSPACE_WRITE
                ? new SandboxPolicy(
                        SandboxMode.WORKSPACE_WRITE,
                        Set.of(root),
                        Set.of(root),
                        protectedRoots,
                        NetworkPolicy.disabled(),
                        Set.of("PATH", "LANG", "LC_ALL", "TERM"),
                        Duration.ofMinutes(5),
                        SandboxPolicy.DEFAULT_OUTPUT_LIMIT)
                : SandboxPolicy.readOnly(Set.of(root), protectedRoots);
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>(profile.attributes());
        CompactionThresholds.validate(attributes);
        attributes.remove("invocationPurpose");
        attributes.remove("promptRequestHash");
        attributes.put("profileId", profile.id());
        attributes.put("profileKind", profile.kind().name());
        attributes.put("profileRevision", Long.toString(profile.revision()));
        boolean automation = Set.of(ProfileKind.LOOP, ProfileKind.WORKFLOW, ProfileKind.SDD, ProfileKind.SCHEDULE)
                .contains(profile.kind());
        attributes.put(
                "maxIterations",
                Integer.toString(profile.maxIterations() == 0 ? automation ? 25 : 16 : profile.maxIterations()));
        attributes.put(
                "maxModelCalls",
                Integer.toString(profile.maxModelCalls() == 0 ? automation ? 100 : 16 : profile.maxModelCalls()));
        attributes.put("systemPrompt", profile.systemPrompt());
        TurnConfig config = new TurnConfig(
                profile.model(),
                profile.provider(),
                reasoningEffort == null ? "medium" : reasoningEffort,
                root,
                policy,
                approvalPolicy == null ? ApprovalPolicy.ON_RISK : approvalPolicy,
                profile.enabledTools(),
                attributes);
        return new ResolvedTurnConfig(profile, config);
    }
}
