package com.javaclaw.infrastructure.memory;

import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.spi.FixedContextSnapshot;
import com.javaclaw.framework.spi.FixedContextSource;
import com.javaclaw.memory.MemoryGraphScope;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.model.Persona;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 从当前会话与工作区个人习惯图谱读取一次版本化 persona。 */
public final class MemoryPersonaFixedContextSource implements FixedContextSource {
    private final MemoryService memory;

    public MemoryPersonaFixedContextSource(MemoryService memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    @Override public String id() { return "memory.persona"; }

    @Override public String group() { return "memory"; }

    @Override public PermissionSet requiredPermissions() { return PermissionSet.of("tool.read"); }

    @Override
    public FixedContextSnapshot read(RunRequest request) {
        MemoryGraphScope scope = MemoryGraphScope.thread(request.scope());
        StringBuilder body = new StringBuilder();
        Persona thread = memory.inScope(scope).getPersona();
        Persona habits = memory.inScope(scope.habits()).getPersona();
        append(body, thread);
        if (thread == null || habits == null
                || !Objects.equals(thread.content, habits.content)) append(body, habits);
        String content = body.toString();
        return new FixedContextSnapshot(digest(content), content);
    }

    private static void append(StringBuilder body, Persona persona) {
        if (persona != null && persona.content != null && !persona.content.isBlank()) {
            body.append("<persona>\n").append(persona.content).append("\n</persona>\n");
        }
    }

    private static String digest(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
