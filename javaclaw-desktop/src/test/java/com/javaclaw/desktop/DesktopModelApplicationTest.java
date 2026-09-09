package com.javaclaw.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopModelApplicationTest {
    private static final ProviderRef MODEL = new ProviderRef("new-provider", 8, "new-model");

    @Test
    void 已有对话只换模型而不清除跟随思考或其他限制() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.defaults =
                    Optional.of(ModelSelectionRpcFixture.configuration(DesktopModelPreferencesTest.constrained(), 3));
            fixture.recent = Optional.of(ModelSelectionRpcFixture.configuration(
                    DesktopModelPreferences.models(DesktopModelPreferencesTest.constrained()), 5));
            var thread = fixture.server.thread();
            var before = DesktopModelPreferences.replace(
                    DesktopModelPreferencesTest.constrained(), Optional.empty(), Optional.empty());
            fixture.configurations.put(
                    thread.id(),
                    new ExecutionConfiguration(
                            Optional.of(thread.workspaceId()),
                            Optional.of(thread.id()),
                            before,
                            2,
                            DesktopTestFixtures.NOW));
            fixture.workspaceDefaults.put(
                    thread.workspaceId(),
                    DesktopModelPreferences.replace(
                            ExecutionOverrides.empty(), Optional.empty(), Optional.of(ReasoningPreference.LOW)));
            var changes = new ArrayList<DesktopConfigurationChange>();
            var application = new DesktopModelApplication();

            var result = application.apply(
                    client,
                    new DesktopModelApplication.Target(thread.workspaceId(), Optional.of(thread.id()), MODEL),
                    changes::add);

            assertEquals(thread.id(), result);
            assertEquals(
                    DesktopModelPreferences.replace(before, Optional.of(MODEL), Optional.empty()),
                    fixture.configurations.get(thread.id()).overrides());
            assertEquals(
                    Optional.of(ReasoningPreference.HIGH),
                    fixture.defaults.orElseThrow().overrides().reasoning());
            assertEquals(
                    Optional.of(MODEL), fixture.recent.orElseThrow().overrides().provider());
            assertEquals(
                    DesktopModelPreferencesTest.constrained(),
                    fixture.defaults.orElseThrow().overrides());
            assertEquals(List.of("thread/execution/update", "execution/recent/update"), fixture.mutations);
            assertEquals(Optional.of(thread.id()), changes.getFirst().threadId());
            assertTrue(fixture.creations.isEmpty());
            changes.clear();
            application.apply(
                    client,
                    new DesktopModelApplication.Target(thread.workspaceId(), Optional.of(thread.id()), MODEL),
                    changes::add);
            assertTrue(changes.isEmpty());
        }
    }

    @Test
    void 新工作区建对话冻结最近思考且不改变当前其他工作区的对话() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.defaults =
                    Optional.of(ModelSelectionRpcFixture.configuration(DesktopModelPreferencesTest.constrained(), 3));
            fixture.recent = Optional.of(ModelSelectionRpcFixture.configuration(
                    DesktopModelPreferences.models(DesktopModelPreferencesTest.constrained()), 5));
            WorkspaceId targetWorkspace = WorkspaceId.random();
            var application = new DesktopModelApplication();
            var target = new DesktopModelApplication.Target(targetWorkspace, Optional.empty(), MODEL);

            var created = application.apply(client, target, ignored -> {});

            assertEquals(targetWorkspace, fixture.threads.get(created).workspaceId());
            assertEquals(
                    Optional.of(ReasoningPreference.HIGH),
                    fixture.configurations.get(created).overrides().reasoning());
            assertEquals(
                    Optional.of(MODEL),
                    fixture.configurations.get(created).overrides().provider());
            assertFalse(
                    fixture.configurations.containsKey(fixture.server.thread().id()));
        }
    }

    @Test
    void 应用失败重试继续原对话且只在对话提交后保存默认() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.defaults =
                    Optional.of(ModelSelectionRpcFixture.configuration(DesktopModelPreferencesTest.constrained(), 3));
            fixture.recent = Optional.of(ModelSelectionRpcFixture.configuration(
                    DesktopModelPreferences.models(DesktopModelPreferencesTest.constrained()), 5));
            fixture.failThreadSave = true;
            var target = new DesktopModelApplication.Target(
                    fixture.server.workspace().id(), Optional.empty(), MODEL);
            var application = new DesktopModelApplication();
            List<DesktopConfigurationChange> changes = new ArrayList<>();

            assertThrows(IllegalStateException.class, () -> application.apply(client, target, changes::add));
            assertEquals(List.of("thread/create"), fixture.mutations);
            assertTrue(changes.isEmpty());
            fixture.failRecentSave = true;
            assertThrows(IllegalStateException.class, () -> application.apply(client, target, changes::add));
            assertEquals(1, changes.size());
            assertTrue(changes.getFirst().threadId().isPresent());
            var result = application.apply(client, target, changes::add);

            assertEquals(1, fixture.creations.size());
            assertEquals(
                    Optional.of(MODEL),
                    fixture.configurations.get(result).overrides().provider());
            assertEquals(
                    List.of("thread/create", "thread/execution/update", "execution/recent/update"), fixture.mutations);
            application.complete(target);
        }
    }

    @Test
    void 创建回执丢失后重试模型应用不会重复建对话() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.loseCreateResponse = true;
            var target = new DesktopModelApplication.Target(
                    fixture.server.workspace().id(), Optional.empty(), MODEL);
            var application = new DesktopModelApplication();

            assertThrows(IllegalStateException.class, () -> application.apply(client, target, ignored -> {}));
            application.apply(client, target, ignored -> {});

            assertEquals(
                    fixture.creations.getFirst().idempotencyKey(),
                    fixture.creations.getLast().idempotencyKey());
            assertEquals(
                    1,
                    fixture.mutations.stream().filter("thread/create"::equals).count());
        }
    }

    @Test
    void Agent固定其他模型时预检拒绝且不创建对话或改变默认值() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.lockedModel = Optional.of(new ProviderRef("fixed-provider", 1, "fixed"));
            var target = new DesktopModelApplication.Target(
                    fixture.server.workspace().id(), Optional.empty(), MODEL);

            var failure = assertThrows(
                    IllegalStateException.class,
                    () -> new DesktopModelApplication().apply(client, target, ignored -> {}));

            assertTrue(failure.getMessage().contains("Agent"));
            assertTrue(fixture.mutations.isEmpty());
        }
    }

    @Test
    void 应用后权威选择变化时保留部分通知但不宣称成功() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.mismatchAfterSave = true;
            var target = new DesktopModelApplication.Target(
                    fixture.server.workspace().id(),
                    Optional.of(fixture.server.thread().id()),
                    MODEL);
            List<DesktopConfigurationChange> changes = new ArrayList<>();

            assertThrows(
                    IllegalStateException.class,
                    () -> new DesktopModelApplication().apply(client, target, changes::add));

            assertEquals(List.of("thread/execution/update"), fixture.mutations);
            assertEquals(1, changes.size());
            assertTrue(fixture.defaults.isEmpty());
            assertTrue(fixture.recent.isEmpty());
        }
    }

    @Test
    void 显式跨工作区对话被拒绝而不修改任何配置() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            var target = new DesktopModelApplication.Target(
                    WorkspaceId.random(), Optional.of(fixture.server.thread().id()), MODEL);

            assertThrows(
                    IllegalStateException.class,
                    () -> new DesktopModelApplication().apply(client, target, ignored -> {}));

            assertTrue(fixture.mutations.isEmpty());
        }
    }
}
