package com.javaclaw.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.ProviderInfo;

/** 保存 Provider 默认模型后，同步内置 Profile 和仍沿用旧默认值的 Profile。 */
final class ProviderProfileSynchronizer {
    private static final Set<String> BUILTIN_PROFILE_IDS = Set.of(
            "profile_chat",
            "profile_plan",
            "profile_loop",
            "profile_workflow",
            "profile_sdd",
            "profile_schedule",
            "profile_subagent");

    private ProviderProfileSynchronizer() {}

    static CompletableFuture<Result> configure(
            JavaClawClient client, ProviderInfo previous, Map<String, String> configuration) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(previous, "previous");
        Map<String, String> values = Map.copyOf(configuration);
        String selectedModel = Objects.requireNonNull(values.get("model"), "model");
        return client.models()
                .configureProvider(
                        previous.id(), values, previous.configRevision(), ManagementViewModel.key("provider-config"))
                .thenCompose(saved -> client.models()
                        .listProfiles()
                        .thenCompose(profiles -> synchronize(client, previous, saved, selectedModel, profiles))
                        .exceptionally(failure -> new Result(saved, 0, List.of("Profile 列表"))));
    }

    static List<ProfileInfo> candidates(ProviderInfo previous, String selectedModel, List<ProfileInfo> profiles) {
        return profiles.stream()
                .filter(profile -> previous.id().equals(profile.provider()))
                .filter(profile -> !selectedModel.equals(profile.model()))
                .filter(profile -> BUILTIN_PROFILE_IDS.contains(profile.id())
                        || profile.model().isBlank()
                        || profile.model().equals(previous.model()))
                .toList();
    }

    static ProfileInfo withModel(ProfileInfo profile, String selectedModel) {
        return new ProfileInfo(
                profile.id(),
                profile.name(),
                profile.kind(),
                profile.provider(),
                selectedModel,
                profile.systemPrompt(),
                profile.enabledTools(),
                profile.requestedSandboxMode(),
                profile.maxIterations(),
                profile.maxModelCalls(),
                profile.attributes(),
                profile.revision(),
                profile.updatedAt());
    }

    private static CompletableFuture<Result> synchronize(
            JavaClawClient client,
            ProviderInfo previous,
            ProviderInfo saved,
            String selectedModel,
            List<ProfileInfo> profiles) {
        List<ProfileInfo> candidates = candidates(previous, selectedModel, profiles);
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(new Result(saved, 0, List.of()));
        }
        List<CompletableFuture<ProfileUpdate>> updates = candidates.stream()
                .map(profile -> client.models()
                        .putProfile(
                                withModel(profile, selectedModel),
                                profile.revision(),
                                ManagementViewModel.key("provider-profile-sync-" + profile.id()))
                        .handle((value, failure) -> new ProfileUpdate(profile.name(), failure == null)))
                .toList();
        return CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    int synchronizedProfiles = 0;
                    ArrayList<String> failedProfiles = new ArrayList<>();
                    for (CompletableFuture<ProfileUpdate> update : updates) {
                        ProfileUpdate result = update.join();
                        if (result.success()) {
                            synchronizedProfiles++;
                        } else {
                            failedProfiles.add(result.name());
                        }
                    }
                    return new Result(saved, synchronizedProfiles, failedProfiles);
                });
    }

    /**
     * Provider 保存及 Profile 同步结果。
     *
     * @param provider 已确认保存的 Provider 配置
     * @param synchronizedProfiles 成功更新的 Profile 数量
     * @param failedProfiles 因 revision 冲突或服务失败而未更新的 Profile 展示名称
     */
    record Result(ProviderInfo provider, int synchronizedProfiles, List<String> failedProfiles) {
        Result {
            failedProfiles = List.copyOf(failedProfiles);
        }

        boolean complete() {
            return failedProfiles.isEmpty();
        }
    }

    /** 单个 Profile 的同步结果；失败原因由管理页统一归类，不回显底层请求内容。 */
    private record ProfileUpdate(String name, boolean success) {}
}
