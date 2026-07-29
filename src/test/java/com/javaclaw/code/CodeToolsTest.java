package com.javaclaw.code;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.ToolReviewMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link CodeTools} 只读工具（code_read 行区间 / code_grep / code_glob）测试。
 *
 * <p>只覆盖不触发确认闸门的只读工具（写类 code_edit/code_insert 依赖 UI 确认，不在纯逻辑单测范围）。
 * 均用绝对路径、不设项目根，规避 code_set_project_root 的确认交互。</p>
 *
 * @author JavaClaw
 */
class CodeToolsTest {

    private CodeTools tools() {
        return new CodeTools(ToolCallOrigin.UNKNOWN);
    }

    @Test
    void code_read_行区间只返回指定行且带行号(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.txt");
        Files.writeString(f, "line one\nline two\nline three\nline four\nline five\n");

        String out = tools().read(f.toString(), 2, 4);
        assertTrue(out.contains("line two"), out);
        assertTrue(out.contains("line three"), out);
        assertTrue(out.contains("line four"), out);
        assertFalse(out.contains("line one"), "区间外的首行不应出现: " + out);
        assertFalse(out.contains("line five"), "区间外的末行不应出现: " + out);
        assertTrue(out.contains("总行数: 5"), out);
    }

    @Test
    void code_read_不传区间整读(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.txt");
        Files.writeString(f, "alpha\nbeta\n");
        String out = tools().read(f.toString(), 0, 0);
        assertTrue(out.contains("alpha") && out.contains("beta"), out);
    }

    @Test
    void code_read_不存在文件报错(@TempDir Path dir) {
        String out = tools().read(dir.resolve("missing.txt").toString(), 0, 0);
        assertTrue(out.contains("失败") && out.contains("不存在"), out);
    }

    @Test
    void code_grep_命中内容并跳过构建目录(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Main.java"), "class Main { void foo() {} }\n");
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("target/Gen.java"), "class Gen { void foo() {} }\n"); // 应被跳过

        String out = tools().grep("foo", dir.toString(), null, false, 100);
        assertTrue(out.contains("Main.java"), out);
        assertFalse(out.contains("Gen.java"), "target/ 下的文件应被跳过: " + out);
    }

    @Test
    void code_grep_glob过滤文件类型(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.java"), "needle here\n");
        Files.writeString(dir.resolve("b.txt"), "needle here\n");

        String out = tools().grep("needle", dir.toString(), "**/*.java", false, 100);
        assertTrue(out.contains("a.java"), out);
        assertFalse(out.contains("b.txt"), "glob 限定 .java，应排除 .txt: " + out);
    }

    @Test
    void code_grep_根层glob按相对路径匹配(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.java"), "needle here\n");
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested/b.java"), "needle here\n");

        String out = tools().grep("needle", dir.toString(), "*.java", false, 100);
        assertTrue(out.contains("a.java"), out);
        assertFalse(out.contains("nested"), "*.java 只应匹配检索根层文件: " + out);
    }

    @Test
    void code_grep_忽略大小写(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "HELLO World\n");
        assertTrue(tools().grep("hello", dir.toString(), null, true, 100).contains("a.txt"));
        assertFalse(tools().grep("hello", dir.toString(), null, false, 100).contains("a.txt"));
    }

    @Test
    void code_glob_按模式查找并排除依赖目录(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Foo.java"), "x\n");
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/Bar.java"), "x\n");
        Files.createDirectories(dir.resolve("node_modules"));
        Files.writeString(dir.resolve("node_modules/Dep.java"), "x\n"); // 应被跳过

        String out = tools().glob("**/*.java", dir.toString());
        assertTrue(out.contains("Foo.java"), out);
        assertTrue(out.contains("Bar.java"), out);
        assertFalse(out.contains("Dep.java"), "node_modules 下应被跳过: " + out);
    }

    // ==================== 构建/测试 runner ====================

    @Test
    void firstToken_去路径取首词() {
        assertEquals("mvn", CodeTools.firstToken("mvn -q compile"));
        assertEquals("gradlew", CodeTools.firstToken("./gradlew test -q"));
        assertEquals("go", CodeTools.firstToken("go test ./..."));
        assertEquals("rm", CodeTools.firstToken("/bin/rm -rf /"));
    }

    @Test
    void autoDetect_按标志文件识别构建命令(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertTrue(CodeTools.autoDetect(dir, false).startsWith("mvn"));
        assertTrue(CodeTools.autoDetect(dir, true).contains("test"));
    }

    @Test
    void autoDetect_未识别返回null(@TempDir Path dir) {
        assertNull(CodeTools.autoDetect(dir, false));
    }

    @Test
    void extractIssues_抽取错误与失败行去重() {
        String out = "compiling...\n"
                + "Main.java:[12,5] cannot find symbol\n"
                + "all good here\n"
                + "Tests run: 3, Failures: 1\n"
                + "Main.java:[12,5] cannot find symbol\n"   // 重复，应去重
                + "BUILD FAILURE\n";
        List<String> issues = CodeTools.extractIssues(out);
        assertTrue(issues.stream().anyMatch(s -> s.contains("cannot find symbol")));
        assertTrue(issues.stream().anyMatch(s -> s.contains("BUILD FAILURE")));
        assertTrue(issues.stream().anyMatch(s -> s.contains("Tests run")));
        assertFalse(issues.contains("all good here"), "无错误标志的行不应入选");
        assertEquals(issues.size(), issues.stream().distinct().count(), "应去重");
    }

    @Test
    void code_build_拒绝非构建工具命令_不执行() {
        // 白名单安全线：通用命令（rm）被拒，绝不下探到执行
        String out = tools().build("rm -rf /", 0);
        assertTrue(out.contains("失败"), out);
        assertTrue(out.contains("只允许构建/测试工具") || out.contains("拒绝"), out);
    }

    @Test
    void code_build_拒绝白名单命令后的shell注入() {
        String out = tools().build("mvn -q test; touch should-not-exist", 1);
        assertTrue(out.contains("失败"), out);
        assertTrue(out.contains("不支持 shell"), out);
    }

    @Test
    void code_build_合法命令也必须先经过高风险确认(@TempDir Path dir) {
        UserInteractionPort oldPort = ToolConfirmationManager.getPort();
        boolean oldEnabled = ToolConfirmationManager.isEnabled();
        ToolReviewMode oldMode = AgentConfig.getInstance().getToolReviewMode();
        AtomicReference<ConfirmRequest> seen = new AtomicReference<>();
        try {
            AgentConfig.getInstance().setToolReviewMode(ToolReviewMode.SMART);
            ToolConfirmationManager.setEnabled(true);
            ToolConfirmationManager.setPort(new UserInteractionPort() {
                @Override
                public boolean confirm(ConfirmRequest request) {
                    seen.set(request);
                    return false;
                }

                @Override
                public void notify(ToastRequest request) {
                    // no-op
                }
            });

            CodeTools tools = tools();
            tools.setProjectRootForTest(dir);
            String out = tools.build("mvn --version", 1);

            assertTrue(out.contains("失败") && out.contains("用户拒绝"), out);
            assertEquals("code_build", seen.get().toolName());
            assertTrue(seen.get().description().contains("mvn --version"));
            assertTrue(seen.get().description().contains(dir.toString()));
        } finally {
            AgentConfig.getInstance().setToolReviewMode(oldMode);
            ToolConfirmationManager.setEnabled(oldEnabled);
            ToolConfirmationManager.setPort(oldPort);
        }
    }

    @Test
    void parseCommand_保留引号参数且不经过shell() {
        assertEquals(List.of("mvn", "-Dname=hello world", "test"),
                CodeTools.parseCommand("mvn '-Dname=hello world' test"));
        assertEquals(List.of(".\\gradlew", "test"), CodeTools.parseCommand(".\\gradlew test"));
    }

    // ==================== git（端到端，需 git 可用） ====================

    @Test
    void git_status_在临时仓库看到未跟踪文件(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "环境无 git，跳过");
        runGitInit(dir);
        Files.writeString(dir.resolve("hello.txt"), "hi\n");

        CodeTools t = new CodeTools(ToolCallOrigin.UNKNOWN);
        t.setProjectRootForTest(dir);
        String out = t.gitStatus();
        assertTrue(out.contains("成功"), out);
        assertTrue(out.contains("hello.txt"), "git status 应显示未跟踪文件: " + out);
    }

    @Test
    void git_log_空仓库无提交时返回失败而非崩溃(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "环境无 git，跳过");
        runGitInit(dir);
        CodeTools t = new CodeTools(ToolCallOrigin.UNKNOWN);
        t.setProjectRootForTest(dir);
        String out = t.gitLog(20, null);
        // 空仓库 git log 退出码非 0，应被折成错误响应而非抛异常
        assertTrue(out.contains("失败"), out);
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runGitInit(Path dir) throws Exception {
        exec(dir, "git", "init");
        exec(dir, "git", "config", "user.email", "t@example.com");
        exec(dir, "git", "config", "user.name", "t");
    }

    private static void exec(Path dir, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(dir.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }
}
