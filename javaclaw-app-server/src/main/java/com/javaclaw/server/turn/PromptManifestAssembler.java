package com.javaclaw.server.turn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.InstructionSourceResolution;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.PromptSourceKind;
import com.javaclaw.api.PromptSourceMetadata;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.instructions.ResolvedInstructions;
import com.javaclaw.server.persistence.TurnPromptSnapshot;

/** 统一构造 Turn Prompt 快照和不泄露项目正文的管理预览。 */
final class PromptManifestAssembler {
    static final String TOKEN_ESTIMATOR = "UTF8_BYTES_DIV_4_V1";

    private final String coreInstruction;

    PromptManifestAssembler(String coreInstruction) {
        this.coreInstruction = text(coreInstruction, "coreInstruction");
    }

    TurnPromptSnapshot snapshot(AgentProfile profile, ResolvedInstructions instructions) {
        AgentProfile checkedProfile = Objects.requireNonNull(profile, "profile");
        ResolvedInstructions checkedInstructions = Objects.requireNonNull(instructions, "instructions");
        return new TurnPromptSnapshot(
                CoreSystemInstruction.REVISION,
                new AgentProfileRef(checkedProfile.id(), checkedProfile.revision()),
                checkedProfile.spec().provider(),
                checkedInstructions.resolution(),
                systemInstruction(checkedProfile, checkedInstructions.promptContent()));
    }

    PromptManifestPreview preview(AgentProfile profile, ResolvedInstructions instructions, CanonicalJson json) {
        TurnPromptSnapshot snapshot = snapshot(profile, instructions);
        return new PromptManifestPreview(
                snapshot.profile(),
                snapshot.provider(),
                profile.spec().permissionProfile(),
                sources(profile, instructions),
                Objects.requireNonNull(json, "json").encode(snapshot).sha256(),
                estimatedTokens(snapshot.systemInstruction()),
                TOKEN_ESTIMATOR,
                coreInstruction,
                profile.spec().systemInstruction());
    }

    private String systemInstruction(AgentProfile profile, String projectInstructions) {
        StringBuilder prompt = new StringBuilder(coreInstruction);
        if (!profile.spec().systemInstruction().isEmpty()) {
            prompt.append("\n\nAgent Profile：\n").append(profile.spec().systemInstruction());
        }
        if (!projectInstructions.isEmpty()) {
            prompt.append("\n\n").append(projectInstructions);
        }
        return prompt.toString();
    }

    private List<PromptSourceMetadata> sources(AgentProfile profile, ResolvedInstructions instructions) {
        List<PromptSourceMetadata> result = new ArrayList<>();
        result.add(source(
                PromptSourceKind.CORE_TEMPLATE,
                "javaclaw/core-system",
                Optional.of(CoreSystemInstruction.REVISION),
                coreInstruction));
        result.add(source(
                PromptSourceKind.AGENT_PROFILE,
                profile.id(),
                Optional.of(Long.toString(profile.revision())),
                profile.spec().systemInstruction()));
        instructions.resolution().sources().stream()
                .map(PromptManifestAssembler::instructionSource)
                .forEach(result::add);
        return List.copyOf(result);
    }

    private static PromptSourceMetadata source(
            PromptSourceKind kind, String sourceId, Optional<String> revision, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new PromptSourceMetadata(kind, sourceId, revision, Optional.of(digest(bytes)), bytes.length, List.of());
    }

    private static PromptSourceMetadata instructionSource(InstructionSourceResolution source) {
        List<String> warnings = new ArrayList<>();
        source.errorCode().ifPresent(warnings::add);
        if (source.truncated()) {
            warnings.add(source.scope() + "_TRUNCATED");
        }
        return new PromptSourceMetadata(
                PromptSourceKind.PROJECT_INSTRUCTION,
                source.scope() + ":" + source.relativePath(),
                Optional.empty(),
                source.digest(),
                source.includedBytes(),
                warnings);
    }

    private static long estimatedTokens(String prompt) {
        long bytes = prompt.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, Math.floorDiv(Math.addExact(bytes, 3), 4));
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
