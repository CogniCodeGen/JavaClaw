package com.javaclaw.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttachmentUploadSessionTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final String DIGEST = "a".repeat(64);

    @Test
    void activeAndCompletedStatesEnforceAttachmentInvariants() {
        AttachmentUploadSession active = session(AttachmentUploadState.ACTIVE, 0, Optional.empty(), Optional.empty());
        AttachmentMetadata metadata = new AttachmentMetadata(DIGEST, "text/plain", 4, NOW);
        AttachmentUploadSession completed =
                session(AttachmentUploadState.COMPLETED, 4, Optional.of(metadata), Optional.empty());

        assertEquals(AttachmentUploadState.ACTIVE, active.state());
        assertEquals(metadata, completed.attachment().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> session(AttachmentUploadState.COMPLETED, 4, Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> session(AttachmentUploadState.ACTIVE, 0, Optional.of(metadata), Optional.empty()));
    }

    @Test
    void failureStatesRequireBoundedReason() {
        AttachmentUploadSession failed =
                session(AttachmentUploadState.FAILED, 2, Optional.empty(), Optional.of("摘要不一致"));

        assertEquals("摘要不一致", failed.failureReason().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> session(AttachmentUploadState.FAILED, 2, Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> session(AttachmentUploadState.FAILED, 2, Optional.empty(), Optional.of("x".repeat(501))));
    }

    @Test
    void attachmentScopeRequiresWorkspaceExactlyForWorkspaceKind() {
        WorkspaceId workspaceId = new WorkspaceId(new UUID(0, 42));

        assertEquals(AttachmentScope.global(), new AttachmentScope(AttachmentScope.Kind.GLOBAL, Optional.empty()));
        assertEquals(
                AttachmentScope.workspace(workspaceId),
                new AttachmentScope(AttachmentScope.Kind.WORKSPACE, Optional.of(workspaceId)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AttachmentScope(AttachmentScope.Kind.GLOBAL, Optional.of(workspaceId)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AttachmentScope(AttachmentScope.Kind.WORKSPACE, Optional.empty()));
    }

    private static AttachmentUploadSession session(
            AttachmentUploadState state,
            long received,
            Optional<AttachmentMetadata> attachment,
            Optional<String> failure) {
        return new AttachmentUploadSession(
                ID,
                AttachmentScope.global(),
                state,
                "text/plain",
                DIGEST,
                4,
                received,
                received == 0 ? 0 : 1,
                1,
                NOW,
                NOW,
                NOW.plusSeconds(60),
                attachment,
                failure);
    }
}
