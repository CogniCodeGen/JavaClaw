package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenProjectLaunchTest {
    @TempDir
    Path root;

    @Test
    void 多模块工作目录使用最近祖先配置且参数和摘要来自同一读取() throws Exception {
        var files = new ProjectFiles();
        files.directory(".mvn");
        files.configuration(".mvn/jvm.config", "-Xmx256m\r\n-Dproject.marker=old\n");
        var result = MavenProjectLaunch.resolve(files, root, root.resolve("module/nested"), List.of("mvn", "verify"));
        String hash = result.evidence().sha256();
        files.configuration(".mvn/jvm.config", "-Dproject.marker=new");
        assertEquals(root, result.baseDirectory());
        assertEquals(List.of("-Xmx256m", "-Dproject.marker=old"), result.jvmArguments());
        assertEquals(hash, result.evidence().sha256());
        assertEquals(List.of(".mvn/jvm.config"), files.reads);
        assertEquals(".", result.evidence().relativeBaseDirectory());
        assertTrue(result.evidence().exists());
    }

    @Test
    void 显式文件选项支持目录文件和等号形式且不会误认失败策略() throws Exception {
        var files = new ProjectFiles();
        files.directory("other");
        files.directory("other/.mvn");
        files.configuration("other/pom.xml", "<project/>");
        files.configuration("other/.mvn/jvm.config", "-Dselected=other");
        for (List<String> arguments : List.of(
                List.of("mvn", "-fae", "-f", "../other/pom.xml"),
                List.of("mvn", "-ff", "--file", "../other"),
                List.of("mvn", "-fn", "--file=../other/pom.xml"),
                List.of("mvn", "-f../other/pom.xml"),
                List.of("mvn", "-f=../other/pom.xml"))) {
            var result = MavenProjectLaunch.resolve(files, root, root.resolve("module"), arguments);
            assertEquals(root.resolve("other"), result.baseDirectory());
            assertEquals(List.of("-Dselected=other"), result.jvmArguments());
        }
    }

    @Test
    void 无配置保持所选项目目录并且祖先查找不越过冻结根() throws Exception {
        var files = new ProjectFiles();
        var result = MavenProjectLaunch.resolve(files, root, root.resolve("module"), List.of("mvn", "-fae"));
        assertEquals(root.resolve("module"), result.baseDirectory());
        assertFalse(result.evidence().exists());
        assertEquals("", result.evidence().sha256());
        assertEquals(0, result.evidence().bytes());
        assertEquals(List.of("module/.mvn", ".mvn"), files.stats);
    }

    @Test
    void 越界缺失和权限失败不能被降级为没有配置() {
        var files = new ProjectFiles();
        assertThrows(
                SecurityException.class,
                () -> MavenProjectLaunch.resolve(files, root, root, List.of("mvn", "-f", "../pom.xml")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MavenProjectLaunch.resolve(files, root, root, List.of("mvn", "-f", "missing.xml")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MavenProjectLaunch.resolve(files, root, root, List.of("mvn", "--file=")));
        files.denied.add(".mvn");
        assertThrows(AccessDeniedException.class, () -> MavenProjectLaunch.resolve(files, root, root, List.of("mvn")));
    }

    @Test
    void JVM配置支持显式双参数选项而不重新解释环境变量() throws Exception {
        String text = "--add-opens java.base/java.lang=ALL-UNNAMED\n-Dliteral=$HOME -Xmx512m";
        assertEquals(
                List.of("--add-opens", "java.base/java.lang=ALL-UNNAMED", "-Dliteral=$HOME", "-Xmx512m"),
                MavenJvmArguments.parse(text.getBytes(StandardCharsets.UTF_8)));
        MavenJvmArguments.requireProperties(List.of("-Dhttp.proxyPort=81"), Map.of("http.proxyPort", "81"));
    }

    @Test
    void 配置展开入口替换非UTF8以及过多参数均明确拒绝() {
        for (String text : List.of(
                "-Dmessage=\"hello world\"",
                "-Dfiles=*.jar",
                "-jar other.jar",
                "@args.txt",
                "--class-path=other",
                "-XX:VMOptionsFile=other",
                "--add-opens",
                "Main")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> MavenJvmArguments.parse(text.getBytes(StandardCharsets.UTF_8)),
                    text);
        }
        assertThrows(
                java.nio.charset.CharacterCodingException.class,
                () -> MavenJvmArguments.parse(new byte[] {(byte) 0xc3, (byte) 0x28}));
        assertThrows(
                IllegalArgumentException.class,
                () -> MavenJvmArguments.parse("-Xmx1m ".repeat(129).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 受控代理属性不能通过JVM或Maven双参数形式覆盖() {
        for (List<String> arguments : List.of(
                List.of("-Dhttps.proxyHost=outside"), List.of("-D", "https.proxyHost=outside"),
                List.of("-Dhttp.nonProxyHosts=*"), List.of("-Djava.net.useSystemProxies=true"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> MavenJvmArguments.requireProperties(
                            arguments,
                            Map.of(
                                    "https.proxyHost",
                                    "127.0.0.1",
                                    "http.nonProxyHosts",
                                    "",
                                    "java.net.useSystemProxies",
                                    "false")));
        }
    }

    private static final class ProjectFiles implements MavenProjectLaunch.ProjectFiles {
        private final Set<String> directories = new HashSet<>();
        private final Map<String, WorkspaceFileAccess.Snapshot> content = new HashMap<>();
        private final Set<String> denied = new HashSet<>();
        private final List<String> reads = new ArrayList<>();
        private final List<String> stats = new ArrayList<>();

        void directory(String path) {
            directories.add(path);
        }

        void configuration(String path, String text) throws Exception {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            content.put(path, new WorkspaceFileAccess.Snapshot(path, true, digest, bytes));
        }

        @Override
        public Optional<WorkspaceFileAccess.Entry> stat(String path) throws Exception {
            stats.add(path);
            if (denied.contains(path)) {
                throw new AccessDeniedException(path);
            }
            if (directories.contains(path)) {
                return Optional.of(new WorkspaceFileAccess.Entry(path, true, 0));
            }
            return Optional.ofNullable(content.get(path))
                    .map(snapshot -> new WorkspaceFileAccess.Entry(path, false, snapshot.content().length));
        }

        @Override
        public WorkspaceFileAccess.Snapshot read(String path) {
            reads.add(path);
            return content.getOrDefault(path, new WorkspaceFileAccess.Snapshot(path, false, "", new byte[0]));
        }
    }
}
