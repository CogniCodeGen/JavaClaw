package com.javaclaw.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用仓库真实 Checkstyle 配置验证正反样例，防止升级工具或修改规则后门禁失效。 */
final class CodingStyleRulesTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsWildcardImports() throws Exception {
        List<Violation> violations = check("Wildcard.java", fixture("Wildcard.java.txt"));
        assertEquals(List.of("AvoidStarImportCheck"), checks(violations));
    }

    @Test
    void requiresPublicTypesConstructorsAndProtectedMethodContracts() throws Exception {
        List<Violation> violations = check("Undocumented.java", """
                public class Undocumented {
                    public Undocumented() {}

                    public void execute() {}

                    protected int budget() {
                        return 1;
                    }
                }
                """);
        assertEquals(
                List.of(
                        "MissingJavadocTypeCheck",
                        "MissingJavadocMethodCheck",
                        "MissingJavadocMethodCheck",
                        "MissingJavadocMethodCheck"),
                checks(violations));
    }

    @Test
    void requiresRecordComponentDescriptions() throws Exception {
        List<Violation> violations = check("UndocumentedRecord.java", """
                /** 有类型说明仍不能省略组件含义与单位。 */
                public record UndocumentedRecord(long timeoutMillis) {}
                """);
        assertEquals(List.of("JavadocTypeCheck"), checks(violations));
    }

    @Test
    void maintainedGeneratedTypesCannotBypassDocumentationRules() throws Exception {
        List<Violation> violations = check("GeneratedType.java", """
                @javax.annotation.processing.Generated("fixture")
                public class GeneratedType {}
                """);
        assertEquals(List.of("MissingJavadocTypeCheck"), checks(violations));
    }

    @Test
    void rejectsMultipleStatementsAndUnbracedControlFlow() throws Exception {
        List<Violation> violations = check("Compressed.java", """
                /** 压缩控制流的反例。 */
                public class Compressed {
                    /** 示例不涉及任何产品状态。 */
                    public void execute(boolean enabled) {
                        int attempts = 0; attempts++;
                        if (enabled) attempts++;
                    }
                }
                """);
        assertTrue(checks(violations).contains("OneStatementPerLineCheck"), violations::toString);
        assertTrue(checks(violations).contains("NeedBracesCheck"), violations::toString);
    }

    @Test
    void acceptsChineseRecordCompactConstructorInheritedDocsAndPatternSwitch() throws Exception {
        assertTrue(check("Documented.java", """
                /**
                 * 正例覆盖中文说明、Record 组件与 Java 25 模式匹配语法。
                 *
                 * @param name 非空展示名称
                 */
                public record Documented(String name) implements Runnable {
                    /** 构造时拒绝缺失名称，不修改外部状态。 */
                    public Documented {
                        if (name == null) {
                            throw new IllegalArgumentException("name");
                        }
                    }

                    @Override
                    public void run() {}

                    /** 对已知输入产生展示文本；null 与未知输入单独处理。 */
                    public String describe(Object value) {
                        return switch (value) {
                            case String text when !text.isBlank() -> text;
                            case Integer number -> number.toString();
                            case null -> "空值";
                            default -> name;
                        };
                    }
                }
                """).isEmpty());
    }

    @Test
    void acceptsJpmsDescriptor() throws Exception {
        assertTrue(check("module-info.java", """
                /** 检查器必须支持命名模块，不能把 requires 当作非法 import。 */
                module example.documented {
                    requires java.logging;
                    exports example.api;
                }
                """).isEmpty());
    }

    @Test
    void testSourcesOnlyRelaxDocumentationAndStillRejectUnsafeFormatting() throws Exception {
        // 使用含 src/test/java 的真实路径，直接验证唯一的测试豁免没有吞掉格式规则。
        List<Violation> violations = check("src/test/java/TestFixture.java", fixture("TestFixture.java.txt"));
        assertEquals(List.of("AvoidStarImportCheck", "OneStatementPerLineCheck"), checks(violations));
    }

    private List<Violation> check(String relativeName, String source) throws Exception {
        Path sample = temporary.resolve(relativeName);
        Files.createDirectories(sample.getParent());
        Files.writeString(sample, source);
        Path configurationRoot = repositoryRoot().resolve("config/checkstyle");
        Properties properties = new Properties();
        properties.setProperty(
                "checkstyle.suppressions.file",
                configurationRoot.resolve("suppressions.xml").toString());
        var configuration = ConfigurationLoader.loadConfiguration(
                configurationRoot.resolve("checkstyle.xml").toString(),
                new PropertiesExpander(properties),
                ConfigurationLoader.IgnoredModulesOptions.EXECUTE);
        List<Violation> violations = new ArrayList<>();
        Checker checker = new Checker();
        try {
            checker.setModuleClassLoader(Checker.class.getClassLoader());
            checker.addListener(new AuditListener() {
                @Override
                public void auditStarted(AuditEvent event) {}

                @Override
                public void auditFinished(AuditEvent event) {}

                @Override
                public void fileStarted(AuditEvent event) {}

                @Override
                public void fileFinished(AuditEvent event) {}

                @Override
                public void addError(AuditEvent event) {
                    String sourceName = event.getSourceName();
                    violations.add(new Violation(
                            sourceName.substring(sourceName.lastIndexOf('.') + 1),
                            event.getLine(),
                            event.getMessage()));
                }

                @Override
                public void addException(AuditEvent event, Throwable failure) {
                    throw new AssertionError("Checkstyle 无法解析规则样例：" + event.getFileName(), failure);
                }
            });
            checker.configure(configuration);
            checker.process(List.of(sample.toFile()));
            return List.copyOf(violations);
        } finally {
            // Checker 自身持有缓存及模块状态，每个样例结束后销毁，避免规则状态跨样例泄漏。
            checker.destroy();
        }
    }

    private static List<String> checks(List<Violation> violations) {
        return violations.stream().map(Violation::check).toList();
    }

    private static String fixture(String name) throws IOException {
        // 故意违规的源码放在文本夹具中，避免 Spotless 把负例本身修正或误认成生产 import。
        try (InputStream input = CodingStyleRulesTest.class.getResourceAsStream("/coding-style/" + name)) {
            assertNotNull(input, () -> "缺少格式规则夹具：" + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("config/checkstyle/checkstyle.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位 JavaClaw 格式规则目录");
    }

    private record Violation(String check, int line, String message) {}
}
