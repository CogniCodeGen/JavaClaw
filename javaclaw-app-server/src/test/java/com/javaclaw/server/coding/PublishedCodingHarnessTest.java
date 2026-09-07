package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.runtime.TurnExecutionCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 显式公网发行验收；固定模型通过真实 Host/Harness 在同一 Thread 连续执行三个 Turn，不调用付费模型。 */
@EnabledIfSystemProperty(named = "javaclaw.codingHarnessNetworkAcceptance", matches = "true")
class PublishedCodingHarnessTest {
    @TempDir
    Path temporary;

    @Test
    void 同一会话聊天准备真实NPM依赖修改断网失败修复成功保留Diff再继续聊天() throws Exception {
        requireArchives();
        var toolchains = new FixtureToolchains();
        var base = new CodingTestFixture(temporary, toolchains, Set.of("registry.npmjs.org"));
        var model = new PublishedCodingHarnessModel(base);
        try (var fixture = new CodingHarnessFixture(base, model)) {
            PublishedToolchainFixture.install(base, toolchains, Set.of(ToolchainKind.NODE, ToolchainKind.NPM));
            project(base.root);
            assertFalse(Files.exists(base.database.dataRoot().resolve("coding/caches")), "必须从隔离的空依赖缓存开始");
            var chat = fixture.queued("published-chat", "你能帮助我理解项目并修改测试吗？");
            execute(fixture, chat, PublishedCodingHarnessModel.INITIAL_CHAT, 0);
            var coding = fixture.queued("published-coding", "查看项目，准备 NPM 依赖，再修改并运行测试，失败后修复重跑。", Duration.ofMinutes(10));
            execute(fixture, coding, PublishedCodingHarnessModel.FINAL_CHAT, 9);
            assertEquals(PublishedCodingHarnessModel.GOOD_SOURCE, Files.readString(base.root.resolve("test.js")));
            assertEquals("prepared", Files.readString(base.root.resolve("prepared-by-lifecycle")));
            byte[] beforeChat = Files.readAllBytes(base.root.resolve("test.js"));
            var followup = fixture.queued("published-followup", "刚才为什么失败？继续解释，不需要修改文件。");
            execute(fixture, followup, PublishedCodingHarnessModel.FOLLOWUP_CHAT, 0);
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    beforeChat, Files.readAllBytes(base.root.resolve("test.js")));
            for (var command : List.of(chat, coding, followup)) {
                assertEquals(fixture.thread.id(), command.turn().threadId());
                assertEquals(chat.turn().role(), command.turn().role());
                assertEquals(chat.turn().permissionProfile(), command.turn().permissionProfile());
            }
            PublishedCodingHarnessAssertions.completed(
                    fixture, model, coding.turn().id());
        }
    }

    private static void requireArchives() {
        String configured = System.getProperty("javaclaw.toolchainArchives");
        assertTrue(configured != null && !configured.isBlank(), "显式验收必须配置 javaclaw.toolchainArchives");
        assertTrue(Files.isDirectory(Path.of(configured)), "官方制品目录不存在");
    }

    private static void execute(CodingHarnessFixture fixture, TurnExecutionCommand turn, String expected, int calls)
            throws Exception {
        System.out.println("Published Coding Harness Turn: " + turn.turn().id());
        var result = fixture.harness.execute(turn, new CancellationSource());
        assertEquals(TurnStatus.COMPLETED, result.status(), result.errorCode().toString());
        assertEquals(expected, result.assistantText());
        assertEquals(calls, result.toolCalls());
    }

    private static void project(Path root) throws Exception {
        Files.writeString(root.resolve("package.json"), PublishedCodingHarnessModel.MANIFEST);
        Files.writeString(root.resolve("test.js"), PublishedCodingHarnessModel.GOOD_SOURCE);
        Files.writeString(
                root.resolve("prepare-check.js"),
                "require('node:fs').writeFileSync('prepared-by-lifecycle', 'prepared');\n");
        assertFalse(Files.exists(root.resolve("node_modules")));
        assertFalse(Files.exists(root.resolve("package-lock.json")));
    }
}
