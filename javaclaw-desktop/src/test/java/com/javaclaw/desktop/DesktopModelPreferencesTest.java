package com.javaclaw.desktop;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.client.CommandOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopModelPreferencesTest {
    private static final ProviderRef MODEL = new ProviderRef("provider", 4, "model");

    @Test
    void 没有最近模型偏好时直接创建并沿用项目设置() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.defaults = Optional.of(ModelSelectionRpcFixture.configuration(constrained(), 3));
            assertEquals(ExecutionOverrides.empty(), DesktopModelPreferences.latest(client));
            var created = new DesktopModelPreferences.ThreadCreations()
                    .create(client, fixture.server.workspace().id(), "新对话");

            assertTrue(fixture.threads.containsKey(created.id()));
            assertTrue(fixture.threadUpdates.isEmpty());
        }
    }

    @Test
    void 记忆模型思考独立保存且不修改安装级配置() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            ExecutionOverrides before = constrained();
            fixture.defaults = Optional.of(ModelSelectionRpcFixture.configuration(before, 3));
            fixture.recent =
                    Optional.of(ModelSelectionRpcFixture.configuration(DesktopModelPreferences.models(before), 5));
            ExecutionOverrides selected = DesktopModelPreferences.replace(
                    ExecutionOverrides.empty(), Optional.of(MODEL), Optional.of(ReasoningPreference.NONE));

            DesktopModelPreferences.remember(client, selected);

            assertEquals(before, fixture.defaults.orElseThrow().overrides());
            assertEquals(selected, fixture.recent.orElseThrow().overrides());
            assertEquals(6, fixture.recent.orElseThrow().revision());
            DesktopModelPreferences.remember(client, selected);
            assertEquals(List.of("execution/recent/update"), fixture.mutations);
        }
    }

    @Test
    void 新对话只冻结模型思考而不覆盖项目的其他限制() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.recent = Optional.of(
                    ModelSelectionRpcFixture.configuration(DesktopModelPreferences.models(constrained()), 3));
            var creations = new DesktopModelPreferences.ThreadCreations();

            var created = creations.create(client, fixture.server.workspace().id(), "新对话");

            assertEquals(
                    DesktopModelPreferences.models(constrained()),
                    fixture.configurations.get(created.id()).overrides());
            assertTrue(
                    fixture.configurations.get(created.id()).overrides().role().isEmpty());
            var next = creations.create(client, fixture.server.workspace().id(), "新对话");
            assertNotEquals(created.id(), next.id());
        }
    }

    @Test
    void 初始化失败重试复用已创建对话和原始默认值() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.recent = Optional.of(
                    ModelSelectionRpcFixture.configuration(DesktopModelPreferences.models(constrained()), 3));
            fixture.failThreadSave = true;
            var creations = new DesktopModelPreferences.ThreadCreations();

            assertThrows(
                    IllegalStateException.class,
                    () -> creations.create(client, fixture.server.workspace().id(), "新对话"));
            fixture.recent = Optional.of(ModelSelectionRpcFixture.configuration(ExecutionOverrides.empty(), 4));
            var result = creations.create(client, fixture.server.workspace().id(), "新对话");

            assertEquals(1, fixture.creations.size());
            assertEquals(
                    fixture.threadUpdates.getFirst().idempotencyKey(),
                    fixture.threadUpdates.getLast().idempotencyKey());
            assertEquals(
                    DesktopModelPreferences.models(constrained()),
                    fixture.configurations.get(result.id()).overrides());
        }
    }

    @Test
    void 创建或初始化回执丢失后复用幂等键恢复且不重复创建() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.recent = Optional.of(
                    ModelSelectionRpcFixture.configuration(DesktopModelPreferences.models(constrained()), 3));
            fixture.loseCreateResponse = true;
            fixture.loseThreadSaveResponse = true;
            var creations = new DesktopModelPreferences.ThreadCreations();

            assertThrows(
                    IllegalStateException.class,
                    () -> creations.create(client, fixture.server.workspace().id(), "新对话"));
            assertThrows(
                    IllegalStateException.class,
                    () -> creations.create(client, fixture.server.workspace().id(), "新对话"));
            creations.create(client, fixture.server.workspace().id(), "新对话");

            assertEquals(
                    fixture.creations.getFirst().idempotencyKey(),
                    fixture.creations.getLast().idempotencyKey());
            assertEquals(
                    fixture.threadUpdates.getFirst().idempotencyKey(),
                    fixture.threadUpdates.getLast().idempotencyKey());
            assertEquals(List.of("thread/create", "thread/execution/update"), fixture.mutations);
        }
    }

    @Test
    void 最近选择不影响旧继承对话而新对话冻结最近模型() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.defaults = Optional.of(ModelSelectionRpcFixture.configuration(constrained(), 3));
            var old = fixture.server.thread();
            ProviderRef workspaceModel = new ProviderRef("project", 2, "project-model");
            ProviderRef recentModel = new ProviderRef("recent", 5, "recent-model");
            fixture.workspaceDefaults.put(
                    old.workspaceId(),
                    DesktopModelPreferences.replace(
                            ExecutionOverrides.empty(), Optional.of(workspaceModel), Optional.empty()));
            DesktopModelPreferences.remember(
                    client,
                    DesktopModelPreferences.replace(
                            ExecutionOverrides.empty(),
                            Optional.of(recentModel),
                            Optional.of(ReasoningPreference.LOW)));

            var created = new DesktopModelPreferences.ThreadCreations().create(client, old.workspaceId(), "最近偏好对话");

            assertEquals(
                    Optional.of(workspaceModel),
                    client.executions()
                            .preview(old.workspaceId(), Optional.of(old.id()), ExecutionOverrides.empty())
                            .provider());
            assertEquals(
                    Optional.of(recentModel),
                    client.executions()
                            .preview(created.workspaceId(), Optional.of(created.id()), ExecutionOverrides.empty())
                            .provider());
            assertFalse(fixture.configurations.containsKey(old.id()));
            assertEquals(constrained(), fixture.defaults.orElseThrow().overrides());
            ProviderRef changed = new ProviderRef("project", 3, "changed-project-model");
            fixture.workspaceDefaults.put(
                    old.workspaceId(),
                    DesktopModelPreferences.replace(
                            ExecutionOverrides.empty(), Optional.of(changed), Optional.empty()));
            assertEquals(
                    Optional.of(changed),
                    client.executions()
                            .preview(old.workspaceId(), Optional.of(old.id()), ExecutionOverrides.empty())
                            .provider());
            assertEquals(
                    Optional.of(recentModel),
                    client.executions()
                            .preview(created.workspaceId(), Optional.of(created.id()), ExecutionOverrides.empty())
                            .provider());
        }
    }

    @Test
    void 手工安装默认仍可改变旧继承对话且不覆盖最近选择() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.defaults = Optional.of(ModelSelectionRpcFixture.configuration(constrained(), 3));
            ExecutionOverrides recent = DesktopModelPreferences.replace(
                    ExecutionOverrides.empty(),
                    Optional.of(new ProviderRef("recent", 2, "recent-model")),
                    Optional.empty());
            DesktopModelPreferences.remember(client, recent);
            ProviderRef installationModel = new ProviderRef("installed", 3, "installed-model");
            var manual =
                    DesktopModelPreferences.replace(constrained(), Optional.of(installationModel), Optional.empty());

            client.executions().updateDefaults(Optional.empty(), manual, CommandOptions.create(3));

            var thread = fixture.server.thread();
            assertEquals(
                    Optional.of(installationModel),
                    client.executions()
                            .preview(thread.workspaceId(), Optional.of(thread.id()), ExecutionOverrides.empty())
                            .provider());
            assertEquals(recent, DesktopModelPreferences.latest(client));
            assertTrue(fixture.configurations.isEmpty());
        }
    }

    @Test
    void 只记忆模型时保留最近思考而不读取安装级思考() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var client = fixture.connect()) {
            fixture.defaults = Optional.of(ModelSelectionRpcFixture.configuration(constrained(), 3));
            fixture.recent = Optional.of(ModelSelectionRpcFixture.configuration(
                    DesktopModelPreferences.replace(
                            ExecutionOverrides.empty(), Optional.of(MODEL), Optional.of(ReasoningPreference.NONE)),
                    2));
            ProviderRef selected = new ProviderRef("other", 4, "other-model");

            DesktopModelPreferences.rememberModel(client, selected);

            assertEquals(
                    Optional.of(selected),
                    fixture.recent.orElseThrow().overrides().provider());
            assertEquals(
                    Optional.of(ReasoningPreference.NONE),
                    fixture.recent.orElseThrow().overrides().reasoning());
            assertEquals(constrained(), fixture.defaults.orElseThrow().overrides());
        }
    }

    static ExecutionOverrides constrained() {
        return new ExecutionOverrides(
                Optional.of(new AgentRoleRef("explorer", 1)),
                Optional.of(MODEL),
                Optional.of(new PermissionProfileRef("limited", 2)),
                Optional.of(ApprovalPolicy.EVERY_CALL),
                Optional.of(new TurnBudget(100, 100, 2, 1, Duration.ofMinutes(1))),
                Optional.of(Set.of("read_file")),
                Optional.of(ReasoningPreference.HIGH));
    }
}
