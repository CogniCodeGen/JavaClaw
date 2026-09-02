package com.javaclaw.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionPackageCycleTest {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");
    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+);");
    private static final Path ROOT = locateRoot();

    @Test
    void 每个模块的生产包依赖图无环() throws IOException {
        List<String> cycles = new ArrayList<>();
        try (Stream<Path> children = Files.list(ROOT)) {
            for (Path module : children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("javaclaw-"))
                    .sorted()
                    .toList()) {
                Path sourceRoot = module.resolve("src/main/java");
                if (Files.isDirectory(sourceRoot)) {
                    cycles.addAll(findCycles(module.getFileName().toString(), sourceRoot));
                }
            }
        }
        assertTrue(cycles.isEmpty(), () -> "检测到生产包 import 循环：" + cycles);
    }

    private static List<String> findCycles(String module, Path sourceRoot) throws IOException {
        Map<Path, String> sourcePackages = readSourcePackages(sourceRoot);
        Set<String> packages = Set.copyOf(sourcePackages.values());
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        packages.stream().sorted().forEach(value -> graph.put(value, new LinkedHashSet<>()));
        for (Map.Entry<Path, String> source : sourcePackages.entrySet()) {
            addImports(graph.get(source.getValue()), source.getKey(), source.getValue(), packages);
        }
        return cycles(module, graph);
    }

    private static Map<Path, String> readSourcePackages(Path sourceRoot) throws IOException {
        Map<Path, String> result = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                Matcher matcher = PACKAGE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                if (matcher.find()) {
                    result.put(file, matcher.group(1));
                }
            }
        }
        return result;
    }

    private static void addImports(Set<String> dependencies, Path source, String owner, Set<String> packages)
            throws IOException {
        Matcher imports = IMPORT.matcher(Files.readString(source, StandardCharsets.UTF_8));
        while (imports.find()) {
            String imported = imports.group(1);
            packages.stream()
                    .filter(candidate -> imported.startsWith(candidate + "."))
                    .max(Comparator.comparingInt(String::length))
                    .filter(candidate -> !candidate.equals(owner))
                    .ifPresent(dependencies::add);
        }
    }

    private static List<String> cycles(String module, Map<String, Set<String>> graph) {
        Map<String, Visit> visits = new HashMap<>();
        ArrayDeque<String> path = new ArrayDeque<>();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String node : graph.keySet()) {
            visit(module, node, graph, visits, path, result);
        }
        return List.copyOf(result);
    }

    private static void visit(
            String module,
            String node,
            Map<String, Set<String>> graph,
            Map<String, Visit> visits,
            ArrayDeque<String> path,
            Set<String> cycles) {
        Visit state = visits.get(node);
        if (state == Visit.COMPLETE) {
            return;
        }
        if (state == Visit.ACTIVE) {
            cycles.add(module + ": " + cyclePath(path, node));
            return;
        }
        visits.put(node, Visit.ACTIVE);
        path.addLast(node);
        for (String dependency :
                graph.getOrDefault(node, Set.of()).stream().sorted().toList()) {
            visit(module, dependency, graph, visits, path, cycles);
        }
        path.removeLast();
        visits.put(node, Visit.COMPLETE);
    }

    private static String cyclePath(ArrayDeque<String> path, String repeated) {
        List<String> values = new ArrayList<>(path);
        int start = values.indexOf(repeated);
        List<String> cycle = new ArrayList<>(values.subList(start, values.size()));
        cycle.add(repeated);
        return String.join(" -> ", cycle);
    }

    private static Path locateRoot() {
        Path candidate = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ""))
                .toAbsolutePath()
                .normalize();
        if (Files.isDirectory(candidate.resolve("javaclaw-api"))) {
            return candidate;
        }
        candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null && Files.notExists(candidate.resolve("javaclaw-api"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("无法定位 JavaClaw Reactor 根目录");
        }
        return candidate;
    }

    private enum Visit {
        ACTIVE,
        COMPLETE
    }
}
