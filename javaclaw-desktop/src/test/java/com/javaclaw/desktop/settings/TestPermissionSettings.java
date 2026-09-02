package com.javaclaw.desktop.settings;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionLayerKind;
import com.javaclaw.api.PermissionLayerResult;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PermissionSection;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.WorkspaceId;

/** 管理中心测试中的 PermissionProfile 与安全授权内存状态。 */
final class TestPermissionSettings {
    private static final Instant NOW = Instant.parse("2026-09-01T01:00:00Z");

    final List<PermissionProfile> profiles = new ArrayList<>();
    final List<PrivateNetworkGrant> privateNetworkGrants = new ArrayList<>();
    final List<UnattendedToolGrantStatus> unattendedToolGrants = new ArrayList<>();
    private final List<PermissionProfile> history = new ArrayList<>();

    TestPermissionSettings() {
        profiles.add(standard());
        history.add(standard());
    }

    List<PermissionProfile> history(String id) {
        return history.stream().filter(profile -> profile.id().equals(id)).toList();
    }

    PermissionProfileDiff diff(String id, long beforeVersion, long afterVersion) {
        PermissionProfile before = version(id, beforeVersion);
        PermissionProfile after = version(id, afterVersion);
        Set<PermissionSection> changed = new HashSet<>();
        addChangedSections(before, after, changed);
        return new PermissionProfileDiff(before, after, changed);
    }

    PermissionProfile cloneProfile(PermissionProfileRef source, String newId) {
        PermissionProfile template = profiles.stream()
                .filter(profile -> profile.id().equals(source.id()) && profile.version() == source.version())
                .findFirst()
                .orElseThrow();
        PermissionProfile clone = new PermissionProfile(
                newId,
                1,
                template.files(),
                template.network(),
                template.processes(),
                template.tools(),
                template.resources());
        profiles.add(clone);
        history.add(clone);
        return clone;
    }

    PermissionProfile update(PermissionProfile profile) {
        profiles.removeIf(candidate -> candidate.id().equals(profile.id()));
        profiles.add(profile);
        history.add(profile);
        return profile;
    }

    EffectivePermissionPreview preview(
            PermissionProfileRef profile,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration) {
        PermissionProfile resolved = profiles.stream()
                .filter(candidate -> candidate.id().equals(profile.id()) && candidate.version() == profile.version())
                .findFirst()
                .orElseThrow();
        List<PermissionLayerResult> layers = java.util.Arrays.stream(PermissionLayerKind.values())
                .map(layer -> new PermissionLayerResult(
                        layer,
                        layerReference(layer, profile, turnGrant, toolDeclaration),
                        layerApplied(layer, turnGrant, toolDeclaration),
                        resolved,
                        List.of()))
                .toList();
        return new EffectivePermissionPreview(resolved, layers, List.of());
    }

    private static Optional<PermissionProfileRef> layerReference(
            PermissionLayerKind layer,
            PermissionProfileRef profile,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration) {
        return switch (layer) {
            case PROFILE -> Optional.of(profile);
            case TURN_GRANT -> turnGrant.map(value -> new PermissionProfileRef(value.id(), value.version()));
            case TOOL_DECLARATION ->
                toolDeclaration.map(value -> new PermissionProfileRef(value.id(), value.version()));
            case SYSTEM_CEILING, WORKSPACE -> Optional.empty();
        };
    }

    private static boolean layerApplied(
            PermissionLayerKind layer,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration) {
        return switch (layer) {
            case TURN_GRANT -> turnGrant.isPresent();
            case TOOL_DECLARATION -> toolDeclaration.isPresent();
            case SYSTEM_CEILING, WORKSPACE, PROFILE -> true;
        };
    }

    PrivateNetworkGrantPreview previewPrivateNetwork(
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> dnsAddresses,
            Optional<Duration> validity) {
        return new PrivateNetworkGrantPreview(
                workspaceId,
                purpose,
                origin,
                dnsAddresses,
                NOW.plus(validity.orElse(Duration.ofHours(1))),
                "0".repeat(64));
    }

    PrivateNetworkGrant createPrivateNetwork(PrivateNetworkGrantPreview preview) {
        PrivateNetworkGrant grant = new PrivateNetworkGrant(
                "network-grant",
                1,
                SecurityGrantState.ACTIVE,
                preview.workspaceId(),
                preview.purpose(),
                preview.origin(),
                preview.dnsAddresses(),
                preview.expiresAt(),
                NOW,
                NOW);
        privateNetworkGrants.add(grant);
        return grant;
    }

    PrivateNetworkGrant revokePrivateNetwork(PrivateNetworkGrant grant) {
        PrivateNetworkGrant revoked = new PrivateNetworkGrant(
                grant.id(),
                grant.revision() + 1,
                SecurityGrantState.REVOKED,
                grant.workspaceId(),
                grant.purpose(),
                grant.origin(),
                grant.dnsAddresses(),
                grant.expiresAt(),
                grant.createdAt(),
                NOW.plusSeconds(1));
        privateNetworkGrants.remove(grant);
        privateNetworkGrants.add(revoked);
        return revoked;
    }

    UnattendedToolGrant createUnattended(UnattendedToolGrantDraft draft) {
        UnattendedToolGrant grant = new UnattendedToolGrant(
                "unattended-grant",
                1,
                SecurityGrantState.ACTIVE,
                draft.workspaceId(),
                draft.scheduleId(),
                draft.scheduleRevision(),
                draft.tool(),
                draft.catalogRevision(),
                draft.schemaHash(),
                draft.fixedArguments(),
                draft.variableStringFields(),
                draft.maximumUses(),
                NOW.plus(draft.validity()),
                NOW,
                NOW);
        unattendedToolGrants.add(new UnattendedToolGrantStatus(grant, 0, grant.maximumUses()));
        return grant;
    }

    UnattendedToolGrant revokeUnattended(UnattendedToolGrant grant) {
        UnattendedToolGrant revoked = new UnattendedToolGrant(
                grant.id(),
                grant.revision() + 1,
                SecurityGrantState.REVOKED,
                grant.workspaceId(),
                grant.scheduleId(),
                grant.scheduleRevision(),
                grant.tool(),
                grant.catalogRevision(),
                grant.schemaHash(),
                grant.fixedArguments(),
                grant.variableStringFields(),
                grant.maximumUses(),
                grant.expiresAt(),
                grant.createdAt(),
                NOW.plusSeconds(1));
        unattendedToolGrants.removeIf(status -> status.grant().id().equals(grant.id()));
        unattendedToolGrants.add(new UnattendedToolGrantStatus(revoked, 0, revoked.maximumUses()));
        return revoked;
    }

    private PermissionProfile version(String id, long version) {
        return history.stream()
                .filter(profile -> profile.id().equals(id) && profile.version() == version)
                .findFirst()
                .orElseThrow();
    }

    private static PermissionProfile standard() {
        return new PermissionProfile(
                "standard",
                1,
                new FilePermission(List.of(Path.of("/tmp")), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of("core/tool/search"), ToolRisk.READ_ONLY, ApprovalRequirement.RISKY),
                new ResourceLimits(256L * 1024 * 1024, 16L * 1024 * 1024, 1, 32));
    }

    private static void addChangedSections(
            PermissionProfile before, PermissionProfile after, Set<PermissionSection> changed) {
        if (!before.files().equals(after.files())) {
            changed.add(PermissionSection.FILE);
        }
        if (!before.network().equals(after.network())) {
            changed.add(PermissionSection.NETWORK);
        }
        if (!before.processes().equals(after.processes())) {
            changed.add(PermissionSection.PROCESS);
        }
        if (!before.tools().equals(after.tools())) {
            changed.add(PermissionSection.TOOL);
        }
        if (!before.resources().equals(after.resources())) {
            changed.add(PermissionSection.RESOURCE);
        }
    }
}
