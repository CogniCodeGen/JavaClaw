package com.javaclaw.server.coding;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定决策模型只发起下一工具；NPM、摘要、Diff 和退出码全部来自真实 Harness 工具回填。 */
final class PublishedCodingHarnessModel implements ModelGateway {
    static final String INITIAL_CHAT = "我可以查看项目，准备依赖，修改代码并在断网环境测试。";
    static final String FINAL_CHAT = "依赖准备完成；首次断网测试失败，修复后的断网测试通过，文件 Diff 已保留。";
    static final String FOLLOWUP_CHAT = "刚才失败来自错误的测试预期。修复复用了已准备的依赖，没有重新安装。";
    static final String SOURCE = "test.js";
    static final String MANIFEST = """
        {"name":"coding-harness-acceptance","version":"1.0.0","private":true,
         "scripts":{"postinstall":"node prepare-check.js","test":"node test.js"},
         "dependencies":{"is-number":"7.0.0"}}
        """;
    static final String GOOD_SOURCE = """
        const assert = require('node:assert/strict');
        const fs = require('node:fs');
        const expected = true;
        assert.equal(require('is-number')(42), expected, 'bad expectation');
        assert.equal(fs.readFileSync('prepared-by-lifecycle', 'utf8'), 'prepared');
        for (const key of ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy',
                           'npm_config_proxy', 'npm_config_https_proxy']) {
            assert.ok(!process.env[key], 'prepare proxy leaked into offline test: ' + key);
        }
        console.log('CODING_OFFLINE_PASS');
        """;
    private static final String BAD_SOURCE = GOOD_SOURCE.replace("expected = true", "expected = false");
    private final CanonicalJson json = new CanonicalJson();
    private final CodingTestFixture base;
    private int invocations;
    private String brokenDigest;
    CodingResults.PreparationResult preparation;
    CodingResults.PatchResult brokenPatch;
    CodingResults.PatchResult repairedPatch;
    CodingResults.CommandResult failedCommand;
    CodingResults.CommandResult successfulCommand;

    PublishedCodingHarnessModel(CodingTestFixture base) {
        this.base = base;
    }

    @Override
    public ModelCapabilities capabilities(String modelId) {
        return new ModelCapabilities(false, true, false, false, false, false, false);
    }

    @Override
    public ModelInvocationResult invoke(
            TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation)
            throws Exception {
        cancellation.throwIfCancelled();
        int step = invocations++;
        if (step == 0) {
            return result(INITIAL_CHAT, List.of());
        }
        if (step == 11) {
            return continueChat(invocation);
        }
        return coding(turnId, invocation, step);
    }

    private ModelInvocationResult coding(TurnId turnId, ModelInvocation invocation, int step) throws Exception {
        return switch (step) {
            case 1 -> discover(invocation);
            case 2 -> call("list", "file_list", new CodingContracts.FileList(".", Optional.empty(), 100));
            case 3 -> readManifest(invocation);
            case 4 -> prepare(invocation);
            case 5 -> afterPreparation(turnId, invocation);
            case 6 -> breakTest(invocation);
            case 7 -> runBrokenTest(invocation);
            case 8 -> repair(invocation);
            case 9 -> runRepairedTest(invocation);
            case 10 -> complete(invocation);
            default -> throw new AssertionError("unexpected published Harness model invocation");
        };
    }

    private ModelInvocationResult discover(ModelInvocation invocation) {
        assertTrue(hasText(invocation, INITIAL_CHAT));
        return result(
                "",
                List.of(new ModelToolCall(
                        "coding-search",
                        CoreTools.search().identity(),
                        json.encode(Map.of("query", "coding", "limit", 20)))));
    }

    private ModelInvocationResult readManifest(ModelInvocation invocation) {
        var listing = output(invocation, "list", CodingResults.FileListResult.class);
        assertTrue(listing.entries().stream().anyMatch(entry -> "package.json".equals(entry.path())));
        return call("manifest", "file_read", new CodingContracts.FileRead("package.json", 0, 65_536));
    }

    private ModelInvocationResult prepare(ModelInvocation invocation) {
        var manifest = output(invocation, "manifest", CodingResults.FileReadResult.class);
        assertEquals(MANIFEST, manifest.text());
        return call(
                "prepare",
                "dependencies_prepare",
                new CodingContracts.DependenciesPrepare(CodingContracts.PackageManager.NPM, ".", List.of()));
    }

    private ModelInvocationResult afterPreparation(TurnId turnId, ModelInvocation invocation) throws Exception {
        preparation = output(invocation, "prepare", CodingResults.PreparationResult.class);
        assertEquals(Optional.of(0), preparation.command().command().exitCode());
        // 在发出任何后续项目工具之前检查撤销，不能只在整个 Turn 结束后观察最终状态。
        PublishedCodingHarnessAssertions.revokedPreparation(base, turnId, preparation);
        return call("source", "file_read", new CodingContracts.FileRead(SOURCE, 0, 65_536));
    }

    private ModelInvocationResult breakTest(ModelInvocation invocation) {
        var source = output(invocation, "source", CodingResults.FileReadResult.class);
        assertEquals(GOOD_SOURCE, source.text());
        return patch("break", source.sha256(), BAD_SOURCE);
    }

    private ModelInvocationResult runBrokenTest(ModelInvocation invocation) {
        brokenPatch = output(invocation, "break", CodingResults.PatchResult.class);
        assertTrue(brokenPatch.complete());
        var change = brokenPatch.changes().getFirst();
        assertTrue(change.diff().contains("-const expected = true;"));
        assertTrue(change.diff().contains("+const expected = false;"));
        brokenDigest = change.afterSha256().orElseThrow();
        return command("failure");
    }

    private ModelInvocationResult repair(ModelInvocation invocation) {
        failedCommand = output(invocation, "failure", CodingResults.CommandResult.class);
        assertEquals(Optional.of(1), failedCommand.command().exitCode());
        assertTrue(failedCommand.output().stderr().contains("bad expectation"));
        return patch("repair", brokenDigest, GOOD_SOURCE);
    }

    private ModelInvocationResult runRepairedTest(ModelInvocation invocation) {
        repairedPatch = output(invocation, "repair", CodingResults.PatchResult.class);
        assertTrue(repairedPatch.complete());
        var change = repairedPatch.changes().getFirst();
        assertEquals(Optional.of(brokenDigest), change.beforeSha256());
        assertTrue(change.diff().contains("-const expected = false;"));
        assertTrue(change.diff().contains("+const expected = true;"));
        return command("success");
    }

    private ModelInvocationResult complete(ModelInvocation invocation) {
        successfulCommand = output(invocation, "success", CodingResults.CommandResult.class);
        assertEquals(Optional.of(0), successfulCommand.command().exitCode());
        assertTrue(successfulCommand.output().stdout().contains("CODING_OFFLINE_PASS"));
        return result(FINAL_CHAT, List.of());
    }

    private ModelInvocationResult continueChat(ModelInvocation invocation) {
        assertTrue(hasText(invocation, INITIAL_CHAT));
        assertTrue(hasText(invocation, FINAL_CHAT));
        assertTrue(invocation.messages().stream()
                .anyMatch(message ->
                        message.role() == MessageRole.USER && message.text().contains("为什么失败")));
        return result(FOLLOWUP_CHAT, List.of());
    }

    private static boolean hasText(ModelInvocation invocation, String expected) {
        return invocation.messages().stream().anyMatch(message -> expected.equals(message.text()));
    }

    private ModelInvocationResult patch(String id, String digest, String source) {
        return call(
                id,
                "file_apply_patch",
                new CodingContracts.ApplyPatch(List.of(new CodingContracts.FileEdit(
                        SOURCE, Optional.of(digest), Optional.of(source), Optional.empty()))));
    }

    private ModelInvocationResult command(String id) {
        return call(id, "command_run", new CodingContracts.CommandRun(List.of("npm", "test"), ".", 120, 65_536));
    }

    private ModelInvocationResult call(String id, String name, Object arguments) {
        return result(
                "",
                List.of(new ModelToolCall(
                        id,
                        new ToolIdentity(CodingContracts.EXTENSION_ID, name, CodingContracts.REVISION),
                        json.encode(arguments))));
    }

    private <T> T output(ModelInvocation invocation, String id, Class<T> type) {
        var message = invocation.messages().stream()
                .filter(value ->
                        value.role() == MessageRole.TOOL && value.toolCallId().equals(Optional.of(id)))
                .reduce((first, last) -> last)
                .orElseThrow();
        return json.decode(json.parse(message.text()), type);
    }

    private static ModelInvocationResult result(String text, List<ModelToolCall> calls) {
        return new ModelInvocationResult(
                text,
                calls,
                new ModelUsage(10, 2, 0, 0),
                Optional.empty(),
                Optional.empty(),
                calls.isEmpty() ? ModelFinishReason.COMPLETE : ModelFinishReason.TOOL_CALLS);
    }
}
