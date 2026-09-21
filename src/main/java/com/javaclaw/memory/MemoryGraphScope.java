package com.javaclaw.memory;

import com.javaclaw.framework.api.RunScope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;

/** Explicit graph identity. Content categories never substitute for an ownership boundary. */
public record MemoryGraphScope(String workspaceId, String userId, String threadId, Kind kind) {
    public enum Kind { WORKSPACE_HABITS, THREAD, LEGACY }

    public MemoryGraphScope {
        workspaceId = required(workspaceId, "workspaceId");
        userId = required(userId, "userId");
        kind = Objects.requireNonNull(kind, "kind");
        threadId = kind == Kind.THREAD ? required(threadId, "threadId") : "";
    }

    public static MemoryGraphScope thread(RunScope scope) {
        return new MemoryGraphScope(scope.workspaceId(), scope.userId(), scope.sessionId(), Kind.THREAD);
    }

    public MemoryGraphScope habits() {
        return new MemoryGraphScope(workspaceId, userId, "", Kind.WORKSPACE_HABITS);
    }

    public Path directory(Path workspaceMemoryRoot) {
        if (kind == Kind.LEGACY) return workspaceMemoryRoot;
        Path user = workspaceMemoryRoot.resolve("graphs").resolve(encode(userId));
        return kind == Kind.WORKSPACE_HABITS ? user.resolve("habits")
                : user.resolve("threads").resolve(encode(threadId));
    }

    public String displayName() {
        return switch (kind) {
            case WORKSPACE_HABITS -> "个人习惯";
            case THREAD -> "会话 · " + threadId;
            case LEGACY -> "历史待归属（不参与召回）";
        };
    }

    @Override public String toString() { return displayName(); }

    static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String required(String value, String field) {
        String result = Objects.requireNonNull(value, field).strip();
        if (result.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return result;
    }
}
