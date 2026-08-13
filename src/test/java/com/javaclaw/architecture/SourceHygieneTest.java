package com.javaclaw.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceHygieneTest {

    private static final Path PROJECT = Path.of(System.getProperty("user.dir"));
    private static final Path JAVA_ROOT = PROJECT.resolve("src/main/java");
    private static final Path RESOURCE_ROOT = PROJECT.resolve("src/main/resources");
    private static final Pattern EMPTY_CATCH =
            Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}", Pattern.DOTALL);
    private static final Pattern NEW_THREAD = Pattern.compile("\\bnew\\s+Thread\\s*\\(");
    private static final Pattern NEW_OBJECT_MAPPER =
            Pattern.compile("\\bnew\\s+ObjectMapper\\s*\\(");
    private static final Pattern SINGLETON_DECLARATION =
            Pattern.compile("\\bstatic\\s+[\\w<>, ?.$\\[\\]]+\\s+getInstance\\s*\\(");
    private static final Pattern HISTORICAL_COMMENT =
            Pattern.compile(
                    "(?m)^\\s*(?://|\\*)\\s*(?:P\\d+|v\\d+|第[一二三四五六七八九十]+阶段)");

    @Test
    void sourceFilesStayWithinReviewableSizeLimits() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files(JAVA_ROOT, ".java")) {
            long nonEmpty = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).count();
            int limit = file.getFileName().toString().endsWith("Controller.java") ? 350 : 800;
            if (nonEmpty > limit) {
                violations.add(relative(file) + " has " + nonEmpty + " non-empty lines (limit " + limit + ")");
            }
        }
        assertNoViolations("Java source size", violations);
    }

    @Test
    void fxmlFilesStayWithinReviewableSizeLimit() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files(RESOURCE_ROOT, ".fxml")) {
            long nonEmpty = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).count();
            if (nonEmpty > 600) {
                violations.add(relative(file) + " has " + nonEmpty + " non-empty lines (limit 600)");
            }
        }
        assertNoViolations("FXML source size", violations);
    }

    @Test
    void directThreadsAreConfinedToApprovedLifecycleBoundaries() throws IOException {
        Map<String, Integer> maximumOccurrences =
                Map.of(
                        "com/javaclaw/app/JavaClawApp.java", 2,
                        "com/javaclaw/app/SingleInstanceCoordinator.java", 1);
        List<String> violations = new ArrayList<>();
        for (Path file : files(JAVA_ROOT, ".java")) {
            String source = Files.readString(file);
            int count = occurrences(NEW_THREAD, source);
            if (count == 0) continue;
            String sourcePath = JAVA_ROOT.relativize(file).toString().replace('\\', '/');
            int allowed = maximumOccurrences.getOrDefault(sourcePath, 0);
            if (count > allowed) {
                violations.add(relative(file) + " creates " + count + " direct threads (allowed " + allowed + ")");
            }
        }
        assertNoViolations("direct thread creation", violations);
    }

    @Test
    void fxDispatchAndSpringContextAccessStayAtTheirBoundaries() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files(JAVA_ROOT, ".java")) {
            String source = Files.readString(file);
            String path = relative(file);
            boolean devtool = path.contains("/devtools/");
            if (source.contains("Platform.runLater")
                    && !path.endsWith("/platform/fx/FxDispatcher.java")
                    && !devtool) {
                violations.add(path + " calls Platform.runLater outside FxDispatcher");
            }
            if (source.contains("ApplicationContext")
                    && !path.contains("/platform/spring/")
                    && !path.contains("/app/")
                    && !devtool) {
                violations.add(path + " accesses Spring ApplicationContext outside composition code");
            }
        }
        assertNoViolations("framework access", violations);
    }

    @Test
    void objectMapperIsCreatedOnlyByTheRootCompositionContext() throws IOException {
        String factory = "com/javaclaw/platform/spring/RootConfiguration.java";
        List<String> violations = new ArrayList<>();
        int factoryOccurrences = 0;
        for (Path file : files(JAVA_ROOT, ".java")) {
            String source = Files.readString(file);
            int count = occurrences(NEW_OBJECT_MAPPER, source);
            if (count == 0) continue;
            String sourcePath = JAVA_ROOT.relativize(file).toString().replace('\\', '/');
            if (!sourcePath.equals(factory)) {
                violations.add(relative(file) + " creates a private ObjectMapper");
            } else {
                factoryOccurrences += count;
            }
        }
        if (factoryOccurrences != 1) {
            violations.add(factory + " must create exactly one shared ObjectMapper (found "
                    + factoryOccurrences + ")");
        }
        assertNoViolations("shared JSON configuration", violations);
    }

    @Test
    void sourceContainsNoSilentCatchesSingletonsOrDamagedText() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files(JAVA_ROOT, ".java")) {
            String source = Files.readString(file);
            String path = relative(file);
            if (EMPTY_CATCH.matcher(source).find()) violations.add(path + " contains an empty catch block");
            if (SINGLETON_DECLARATION.matcher(source).find()) {
                violations.add(path + " declares getInstance singleton access");
            }
            if (source.contains("AppDatabase.getInstance(")
                    || source.contains("DataManager.getInstance(")
                    || source.contains("WorkspaceManager.getInstance(")) {
                violations.add(path + " uses a removed static manager singleton");
            }
            if (source.indexOf('\uFFFD') >= 0) violations.add(path + " contains U+FFFD replacement text");
            if (!path.contains("/devtools/") && HISTORICAL_COMMENT.matcher(source).find()) {
                violations.add(path + " contains a historical phase/version comment");
            }
        }
        assertNoViolations("source hygiene", violations);
    }

    @Test
    void removedAgentLibraryLeavesNoSourceOrBuildReferences() throws IOException {
        String forbidden = "agent" + "scope";
        List<String> violations = new ArrayList<>();
        List<Path> roots = List.of(PROJECT.resolve("pom.xml"), PROJECT.resolve("src/main"),
                PROJECT.resolve("src/test"));
        for (Path root : roots) {
            if (Files.isRegularFile(root)) {
                if (Files.readString(root).toLowerCase().contains(forbidden)) {
                    violations.add(relative(root) + " still references the removed agent library");
                }
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile)
                        .filter(SourceHygieneTest::isReviewableText).toList()) {
                    if (Files.readString(file).toLowerCase().contains(forbidden)) {
                        violations.add(relative(file) + " still references the removed agent library");
                    }
                }
            }
        }
        assertNoViolations("removed agent library", violations);
    }

    @Test
    void conversationCompatibilityProjectionCannotReintroduceObjectPayloads() throws IOException {
        String source = Files.readString(JAVA_ROOT.resolve(
                "com/javaclaw/api/conversation/ConversationEvent.java"));
        List<String> violations = new ArrayList<>();
        if (source.contains("sealed interface ConversationEvent")) {
            violations.add("ConversationEvent must remain open for adapter-side projections");
        }
        if (source.contains("Object payload")) {
            violations.add("ConversationEvent.Custom must use JsonNode instead of Object payload");
        }
        assertNoViolations("conversation event projection", violations);
    }

    private static boolean isReviewableText(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".fxml")
                || name.endsWith(".properties") || name.endsWith(".css") || name.endsWith(".md")
                || name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml")
                || name.endsWith(".txt") || name.endsWith(".sql");
    }

    private static List<Path> files(Path root, String suffix) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static int occurrences(Pattern pattern, String source) {
        int count = 0;
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) count++;
        return count;
    }

    private static String relative(Path path) {
        return PROJECT.relativize(path).toString().replace('\\', '/');
    }

    private static void assertNoViolations(String rule, List<String> violations) {
        assertTrue(violations.isEmpty(), () -> rule + " violations:\n" + String.join("\n", violations));
    }
}
