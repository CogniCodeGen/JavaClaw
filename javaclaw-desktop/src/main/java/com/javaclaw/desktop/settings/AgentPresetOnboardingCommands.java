package com.javaclaw.desktop.settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;

import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;

/** 为向导的每个可恢复写步骤生成确定性幂等键。 */
final class AgentPresetOnboardingCommands {
    private AgentPresetOnboardingCommands() {}

    static CommandOptions instantiatePermission(WorkspaceId workspaceId, String presetId) {
        return options(workspaceId, "permission." + presetId + ".instantiate", 0, presetId);
    }

    static CommandOptions updatePermission(WorkspaceId workspaceId, PermissionProfile profile) {
        String material = profile.id()
                + '\n'
                + profile.version()
                + '\n'
                + sorted(profile.tools().allowedTools());
        return options(workspaceId, "permission.tools.update", profile.version() - 1, material);
    }

    static CommandOptions createProfile(WorkspaceId workspaceId, String presetId, AgentProfileSpec spec) {
        String material = presetId
                + '\n'
                + spec.displayName()
                + '\n'
                + spec.systemInstruction()
                + '\n'
                + spec.provider().endpointId()
                + '\n'
                + spec.provider().endpointRevision()
                + '\n'
                + spec.provider().model()
                + '\n'
                + spec.permissionProfile().id()
                + '\n'
                + spec.permissionProfile().version()
                + '\n'
                + sorted(spec.visibleTools())
                + '\n'
                + spec.budget();
        return options(workspaceId, "profile." + presetId + ".create", 0, material);
    }

    static CommandOptions bindDefault(
            WorkspaceId workspaceId, String profileId, long profileRevision, long expectedRevision) {
        return options(workspaceId, "profile.default.bind", expectedRevision, profileId + '\n' + profileRevision);
    }

    private static CommandOptions options(
            WorkspaceId workspaceId, String operation, long expectedRevision, String material) {
        String key = "onboarding."
                + Objects.requireNonNull(workspaceId, "workspaceId")
                + '.'
                + Objects.requireNonNull(operation, "operation")
                + '.'
                + digest(material);
        return new CommandOptions(key, expectedRevision);
    }

    private static String sorted(Collection<String> values) {
        return Objects.requireNonNull(values, "values").stream()
                .sorted()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String digest(String material) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(material, "material").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
