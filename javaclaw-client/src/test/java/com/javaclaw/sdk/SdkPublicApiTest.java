package com.javaclaw.sdk;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkPublicApiTest {
    @Test
    void publicSdkSignaturesContainOnlyJdkAndSdkTypes() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : publicSdkTypes()) {
            for (var constructor : type.getConstructors()) {
                check(type, constructor.toGenericString(), violations);
            }
            for (var method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                check(type, method.toGenericString(), violations);
            }
            for (var field : type.getFields()) {
                check(type, field.toGenericString(), violations);
            }
        }
        assertTrue(violations.isEmpty(), () -> "SDK API leaked an implementation type: " + violations);
    }

    @Test
    void obsoleteRawAndAuthorityExpandingTurnEntrypointsAreAbsent() {
        List<String> methods = java.util.Arrays.stream(JavaClawClient.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();
        assertFalse(methods.contains("request"));
        assertFalse(methods.contains("startTextTurn"));
        assertFalse(methods.contains("startProfileTextTurn"));
        assertFalse(methods.contains("startProfileTurn"));
        assertTrue(java.util.Arrays.stream(ThreadClient.class.getMethods())
                .anyMatch(method -> method.getName().equals("startTurn")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == com.javaclaw.sdk.model.TurnStartRequest.class));
        assertTrue(java.util.Arrays.stream(ThreadClient.class.getMethods())
                .anyMatch(method -> method.getName().equals("executionSummary")));
        assertTrue(java.util.Arrays.stream(ThreadClient.class.getMethods())
                .anyMatch(method -> method.getName().equals("retryInNewBranch")));
        assertTrue(java.util.Arrays.stream(KnowledgeClient.class.getMethods())
                .anyMatch(method -> method.getName().equals("sourceStats")));
        assertTrue(java.util.Arrays.stream(AutomationClient.class.getMethods())
                .anyMatch(method -> method.getName().equals("previewSchedule")));
    }

    @Test
    void rpcConflictHasAnSdkOnlySemanticClassifier() {
        assertTrue(new RpcException(-32009, "revision conflict", null).isConflict());
        assertFalse(new RpcException(-32602, "invalid params", null).isConflict());
    }

    private static void check(Class<?> owner, String signature, List<String> violations) {
        if (signature.contains("com.fasterxml.jackson")
                || signature.contains("com.javaclaw.protocol")
                || signature.contains("RpcConnection")
                || signature.contains("ServerNotification")) {
            violations.add(owner.getName() + ": " + signature);
        }
    }

    private static List<Class<?>> publicSdkTypes() throws Exception {
        URI location = JavaClawClient.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI();
        Path classes = Path.of(location);
        Path sdk = classes.resolve("com/javaclaw/sdk");
        List<Class<?>> result = new ArrayList<>();
        try (var files = Files.walk(sdk)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .toList()) {
                String name = classes.relativize(file)
                        .toString()
                        .replace(file.getFileSystem().getSeparator(), ".");
                name = name.substring(0, name.length() - ".class".length());
                Class<?> type = Class.forName(name, false, JavaClawClient.class.getClassLoader());
                if (Modifier.isPublic(type.getModifiers())) {
                    result.add(type);
                }
            }
        }
        return List.copyOf(result);
    }
}
