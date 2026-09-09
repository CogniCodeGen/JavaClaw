package com.javaclaw.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageDataProtectionTest {
    private static final List<String> EXECUTIONS = List.of("protect-package-data-before-clean", "reset-package-output");

    @TempDir
    Path temporary;

    @Test
    void 清理与重建发现运行数据时先拒绝且保留数据库与其余产物() throws Exception {
        for (String execution : EXECUTIONS) {
            Path fixture = Files.createDirectory(temporary.resolve(execution));
            Path target = Files.createDirectories(fixture.resolve("target"));
            Path database = write(target.resolve("distribution/data-v6/javaclaw.mv.db"), "database-sentinel");
            Path artifact = write(target.resolve("distribution/lib/application.jar"), "artifact-sentinel");
            Path archive = write(target.resolve("release/application.zip"), "archive-sentinel");
            BuildException failure = assertThrows(BuildException.class, () -> execute(execution, fixture));
            assertTrue(failure.getMessage().contains(database.getParent().toString()));
            assertTrue(failure.getMessage().contains("备份"));
            assertTrue(failure.getMessage().contains("IDEA"));
            assertEquals("database-sentinel", Files.readString(database));
            assertEquals("artifact-sentinel", Files.readString(artifact));
            assertEquals("archive-sentinel", Files.readString(archive));
        }
    }

    @Test
    void 无运行数据时正常清理并保留构建目录外的稳定数据() throws Exception {
        for (String execution : EXECUTIONS) {
            Path fixture = Files.createDirectory(temporary.resolve(execution));
            Path stable = write(fixture.resolve("data-v6/javaclaw.mv.db"), "stable-database");
            Path artifact = write(fixture.resolve("target/distribution/lib/application.jar"), "stale-artifact");
            execute(execution, fixture);
            assertFalse(Files.exists(artifact));
            assertEquals("stable-database", Files.readString(stable));
        }
    }

    @Test
    void 发行归档的真实排除规则不会收录运行数据() throws Exception {
        Path distribution = Files.createDirectories(temporary.resolve("distribution"));
        write(distribution.resolve("data-v6/javaclaw.mv.db"), "private-database");
        write(distribution.resolve("data-v6/logs/app-server.log"), "private-log");
        write(distribution.resolve("lib/application.jar"), "application");
        Document assembly = parse(repository().resolve("javaclaw-packaging/src/assembly/distribution.xml"));
        Element files = (Element) assembly.getElementsByTagName("fileSet").item(0);
        NodeList excluded = files.getElementsByTagName("exclude");
        String[] patterns = new String[excluded.getLength()];
        for (int index = 0; index < excluded.getLength(); index++) {
            patterns[index] = excluded.item(index).getTextContent();
        }
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(distribution.toFile());
        scanner.setExcludes(patterns);
        scanner.scan();
        assertEquals(List.of(Path.of("lib", "application.jar").toString()), Arrays.asList(scanner.getIncludedFiles()));
        assertFalse(Arrays.asList(scanner.getIncludedDirectories()).contains("data-v6"));
    }

    private static void execute(String executionId, Path fixture) throws Exception {
        Element execution = execution(executionId);
        String phase = execution.getElementsByTagName("phase").item(0).getTextContent();
        boolean clean = executionId.equals(EXECUTIONS.getFirst());
        assertEquals(clean ? "pre-clean" : "initialize", phase);
        Document build = documentFactory().newDocumentBuilder().newDocument();
        Element project = build.createElement("project");
        build.appendChild(project);
        Element target = (Element)
                build.importNode(execution.getElementsByTagName("target").item(0), true);
        target.setAttribute("name", "guarded-operation");
        project.appendChild(target);
        if (clean) {
            // Maven 的 clean 位于 pre-clean 之后；保留真实护栏，并模拟紧随其后的默认 target 删除。
            Element delete = build.createElement("delete");
            delete.setAttribute("dir", "${project.build.directory}");
            target.appendChild(delete);
        }
        Path buildFile = fixture.resolve("build.xml");
        TransformerFactory transformers = TransformerFactory.newInstance();
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        transformers.newTransformer().transform(new DOMSource(build), new StreamResult(buildFile.toFile()));
        Project ant = new Project();
        ant.init();
        ant.setBaseDir(fixture.toFile());
        ant.setUserProperty("project.build.directory", fixture.resolve("target").toString());
        ProjectHelper.configureProject(ant, buildFile.toFile());
        ant.executeTarget("guarded-operation");
    }

    private static Element execution(String id) throws Exception {
        Document pom = parse(repository().resolve("javaclaw-packaging/pom.xml"));
        NodeList executions = pom.getElementsByTagName("execution");
        for (int index = 0; index < executions.getLength(); index++) {
            Element candidate = (Element) executions.item(index);
            if (candidate.getElementsByTagName("id").item(0).getTextContent().equals(id)) {
                return candidate;
            }
        }
        throw new IllegalStateException("发行构建缺少数据保护步骤：" + id);
    }

    private static Path write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text);
    }

    private static Document parse(Path path) throws Exception {
        return documentFactory().newDocumentBuilder().parse(path.toFile());
    }

    private static DocumentBuilderFactory documentFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static Path repository() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null && !Files.isDirectory(candidate.resolve(".run"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("无法定位 JavaClaw 仓库");
        }
        return candidate;
    }
}
