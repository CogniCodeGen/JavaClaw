package com.javaclaw.protocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.GraphBrowsing;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;
import com.javaclaw.protocol.jpms.ViewSchemaModuleProbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaModulePathTest {
    @TempDir
    Path temporary;

    @Test
    void decodesClasspathServerViewsInNamedClientModuleWithoutLauncherOverrides() throws Exception {
        CanonicalJson json = new CanonicalJson();
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(json);
        ViewSchema schema = schema();
        Path legacy = temporary.resolve("view.json");
        Path browsing = temporary.resolve("browsing-view.json");
        Files.writeString(legacy, codec.encode(schema).json());
        Files.writeString(
                browsing,
                codec.encode(new ViewSchema(
                                schema.schemaVersion(),
                                schema.viewId(),
                                schema.title(),
                                schema.dataSources(),
                                schema.nodes(),
                                Map.of(
                                        "graph",
                                        new GraphBrowsing("neighbors/query", "window", "query", "inactive", "ids"))))
                        .json());
        Path log = temporary.resolve("module-probe.log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String probeClasses = Path.of(ViewSchemaModuleProbe.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        // Surefire 使用 classpath；另起命名模块 JVM 才能复现 IDE Desktop 的反射边界。
        Process process = new ProcessBuilder(
                        javaExecutable(),
                        "--module-path",
                        classpath,
                        "--add-modules",
                        "com.javaclaw.protocol",
                        "--class-path",
                        probeClasses,
                        ViewSchemaModuleProbe.class.getName(),
                        legacy.toString(),
                        browsing.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "命名模块页面探针应在 30 秒内完成：" + Files.readString(log));
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static ViewSchema schema() {
        ViewStructuredItemField item = new ViewStructuredItemField(
                "title",
                "标题",
                ViewStructuredItemType.TEXT,
                Optional.empty(),
                List.of(),
                ViewStructuredItemValidation.required(true),
                List.of(),
                Optional.empty());
        ViewStructuredListField field = new ViewStructuredListField(
                "steps",
                "步骤",
                new ViewBinding("editor", "steps"),
                0,
                10,
                "id",
                List.of(item),
                List.of(),
                Optional.empty());
        ViewAction save = new ViewAction(
                "保存", "save", Map.of(), Map.of(), new ExpectedRevisionBinding.SourceRevision("editor"), false);
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "module.view",
                "模块页面",
                List.of(),
                List.of(
                        new ViewSchema.Form("form", "编辑", List.of(field), save),
                        new ViewSchema.Graph("graph", "图谱", "nodes", "edges", "id", "label", "kind", "from", "to")));
    }
}
