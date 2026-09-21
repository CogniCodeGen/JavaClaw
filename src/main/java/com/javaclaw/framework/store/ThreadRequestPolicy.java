package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.node.TextNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Freezes task configuration and inherited limits before compilation. */
final class ThreadRequestPolicy {
    private final JdbcThreadStore threads;
    private final RunStore runs;
    ThreadRequestPolicy(JdbcThreadStore threads, RunStore runs) { this.threads = threads; this.runs = runs; }

    RunRequest prepare(RunRequest request) {
        RunScope parent = null;
        RunRequest parentRequest = null;
        if (request.linkage().parentRunId() != null) {
            parentRequest = runs.find(request.linkage().parentRunId()).orElseThrow(
                    () -> new IllegalArgumentException("unknown parent turn")).request();
            parent = parentRequest.scope();
            if (!parent.workspaceId().equals(request.scope().workspaceId()) || !parent.userId().equals(request.scope().userId()))
                throw new SecurityException("cross-scope delegation is forbidden");
            if (parent.equals(request.scope())) throw new IllegalArgumentException("child agent needs its own thread");
        }
        String requestedDirectory = request.attributes().getOrDefault("workDir", TextNode.valueOf("")).asText();
        ThreadConfiguration initial = configuration(new ThreadConfiguration("", requestedDirectory, "host",
                request.permissionCeiling(), request.budget(), Map.of()));
        ThreadSnapshot thread = threads.create(new ThreadStartRequest(request.scope(), title(request), initial,
                parent, request.linkage().parentRunId() == null ? null : TurnId.from(request.linkage().parentRunId())));
        if (thread.status() != ThreadStatus.ACTIVE) throw new IllegalStateException("thread is not active: " + thread.status());
        ThreadConfiguration config = configuration(thread.configuration());
        if (!config.equals(thread.configuration())) thread = threads.configure(request.scope(), config);
        PermissionSet permissions = request.permissionCeiling().intersect(config.permissions());
        RunBudget budget = request.budget().restrictWith(config.budget());
        if (parentRequest != null) {
            permissions = permissions.intersect(parentRequest.permissionCeiling());
            if (!request.source().kind().equals("maintenance")) budget = budget.restrictWith(parentRequest.budget());
        }
        Map<String, com.fasterxml.jackson.databind.JsonNode> attributes = new LinkedHashMap<>(request.attributes());
        attributes.put("workDir", TextNode.valueOf(config.workingDirectory()));
        attributes.put("framework.threadGeneration", com.fasterxml.jackson.databind.node.LongNode.valueOf(thread.generation()));
        attributes.put("framework.threadModelPolicy", TextNode.valueOf(config.modelPolicyRef()));
        attributes.put("framework.projectInstructions", TextNode.valueOf(String.join("\n\n", config.projectInstructions().values())));
        java.util.List<InputBlock> inputs = new java.util.ArrayList<>();
        if (!request.source().kind().equals("maintenance") && request.inputs().stream().noneMatch(input -> input.type().equals("core.message"))) {
            java.util.Map<String, String> userInputs = new LinkedHashMap<>();
            java.util.List<InputBlock> history = new java.util.ArrayList<>();
            for (ThreadEvent event : threads.events(request.scope(), 0)) {
                if (event.turnId() == null) continue;
                if (event.payload().has("request")) {
                    StringBuilder user = new StringBuilder();
                    for (var input : event.payload().path("request").path("inputs"))
                        if (input.path("type").asText().equals("core.text")) user.append(input.path("data").path("text").asText());
                    userInputs.put(event.turnId().value(), user.toString());
                } else if (event.type().equals("turn/completed")) {
                    String user = userInputs.get(event.turnId().value());
                    String reply = event.payload().path("event").path("payload").path("output").path("text").asText();
                    if (user != null && !user.isBlank() && !reply.isBlank()) {
                        history.add(InputBlock.message("user", user)); history.add(InputBlock.message("assistant", reply));
                    }
                }
            }
            inputs.addAll(boundedHistory(history));
        }
        inputs.addAll(request.inputs());
        return new RunRequest(request.agent(), request.profile(), request.source(), request.scope(), inputs,
                request.linkage(), permissions, budget, request.idempotencyKey(), attributes);
    }

    /** Bound only the prompt projection; the event journal retains the complete original messages. */
    private static java.util.List<InputBlock> boundedHistory(java.util.List<InputBlock> history) {
        java.util.LinkedList<InputBlock> selected = new java.util.LinkedList<>();
        int remaining = 48_000;
        for (int index = history.size() - 1; index >= Math.max(0, history.size() - 20) && remaining > 0; index--) {
            InputBlock message = history.get(index);
            String text = message.data().path("text").asText();
            int limit = Math.min(remaining, 8_000);
            if (text.length() > limit) text = text.substring(0, limit) + "\n[历史消息已截短，可通过 memory_recall 查找完整来源]";
            selected.addFirst(InputBlock.message(message.data().path("role").asText(), text));
            remaining -= text.length();
        }
        return java.util.List.copyOf(selected);
    }

    static ThreadConfiguration configuration(ThreadConfiguration original) {
        Path root = com.javaclaw.util.ProjectAccessPolicy.projectRoot();
        Path working = original.workingDirectory().isBlank() ? root
                : com.javaclaw.util.ProjectAccessPolicy.resolveProjectPath(original.workingDirectory());
        if (!Files.isDirectory(working)) throw new IllegalArgumentException("working directory does not exist: " + working);
        LinkedHashMap<String, String> instructions = new LinkedHashMap<>();
        Path current = root;
        readInstruction(current, instructions);
        for (Path segment : root.relativize(working)) {
            if (segment.toString().isEmpty()) continue;
            current = current.resolve(segment); readInstruction(current, instructions);
        }
        return new ThreadConfiguration(original.modelPolicyRef(), working.toString(), original.sandboxPolicy(),
                original.permissions(), original.budget(), instructions);
    }
    private static void readInstruction(Path directory, Map<String, String> instructions) {
        Path path = directory.resolve("AGENTS.md");
        if (!Files.isRegularFile(path)) return;
        com.javaclaw.util.ProjectAccessPolicy.requireProjectFilePath(path);
        try { instructions.put(path.toString(), Files.readString(path)); }
        catch (java.io.IOException failure) { throw new IllegalStateException("cannot read project instructions", failure); }
    }
    private static String title(RunRequest request) {
        String text = request.inputs().stream().filter(input -> input.type().equals("core.text"))
                .map(input -> input.data().path("text").asText()).findFirst().orElse(request.source().kind());
        return text.length() > 80 ? text.substring(0, 80) : text;
    }
}
