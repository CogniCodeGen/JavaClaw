package com.javaclaw.builtin.extensions;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.builtin.contracts.KnowledgeContracts;

/** Knowledge Generation Job 的冻结输入与恢复指针。 */
final class KnowledgeJobContracts {
    private KnowledgeJobContracts() {}

    record FrozenImport(
            KnowledgeContracts.ImportRequest request, long expectedSourceRevision, PermissionProfile permissions) {
        FrozenImport {
            Objects.requireNonNull(request, "request");
            if (expectedSourceRevision < 0) {
                throw new IllegalArgumentException("expectedSourceRevision must not be negative");
            }
            Objects.requireNonNull(permissions, "permissions");
        }
    }

    record Checkpoint(Optional<String> activeGenerationId) {
        Checkpoint {
            activeGenerationId = Objects.requireNonNull(activeGenerationId, "activeGenerationId");
        }

        static Checkpoint pending() {
            return new Checkpoint(Optional.empty());
        }

        static Checkpoint completed(String generationId) {
            return new Checkpoint(Optional.of(generationId));
        }
    }

    record BuildIntent(String generationId, String attachmentDigest) {
        BuildIntent {
            generationId = text(generationId, "generationId");
            attachmentDigest = Objects.requireNonNull(attachmentDigest, "attachmentDigest");
            if (!attachmentDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("attachmentDigest must be SHA-256 hex");
            }
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }
}
