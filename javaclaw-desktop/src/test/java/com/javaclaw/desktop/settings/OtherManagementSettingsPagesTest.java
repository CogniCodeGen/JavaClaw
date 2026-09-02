package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.InstructionScope;
import com.javaclaw.api.InstructionSourceResolution;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceInstructionSettings;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtherManagementSettingsPagesTest {
    @Test
    void 内置扩展页只允许可选能力启停并区分平台Host() {
        FxTestSupport.run(() -> {
            BuiltinGateway gateway = new BuiltinGateway();
            gateway.statuses.add(status(
                    "com.javaclaw.plan",
                    ExtensionAvailability.OPTIONAL,
                    ExtensionState.DISABLED,
                    BuiltinExtensionRpcContracts.RuntimeKind.BUNDLE));
            gateway.statuses.add(status(
                    "com.javaclaw.mcp",
                    ExtensionAvailability.REQUIRED,
                    ExtensionState.ENABLED,
                    BuiltinExtensionRpcContracts.RuntimeKind.PLATFORM));
            BuiltinExtensionSettingsPage page = new BuiltinExtensionSettingsPage(gateway);
            Parent root = attach(page);
            page.activate();

            list(root, BuiltinExtensionRpcContracts.Status.class)
                    .getSelectionModel()
                    .select(gateway.statuses.getFirst());
            button(root, "启用").fire();
            assertTrue(texts(root).contains("ENABLED"));
            confirmNextDialog();
            button(root, "停用").fire();
            assertTrue(texts(root).contains("DISABLED"));

            list(root, BuiltinExtensionRpcContracts.Status.class)
                    .getSelectionModel()
                    .select(gateway.statuses.get(1));
            assertTrue(texts(root).contains("必需，只读"));
            assertTrue(texts(root).contains("App Server 平台 Host"));
            assertTrue(button(root, "停用").isDisabled());
            button(root, "刷新").fire();
            assertFalse(page.dirty());
        });
    }

    @Test
    void 学习策略页保存低风险自动学习并可丢弃未保存改动() {
        FxTestSupport.run(() -> {
            LearningGateway gateway = new LearningGateway();
            LearningSettingsPage page = new LearningSettingsPage(gateway);
            Parent root = attach(page);
            page.activate();
            ComboBox<MemoryContracts.LearningPolicy> policy = combo(root, MemoryContracts.LearningPolicy.class);

            policy.setValue(MemoryContracts.LearningPolicy.AUTO_LOW_RISK);
            assertTrue(page.dirty());
            page.warnUnsavedChanges();
            button(root, "保存").fire();
            assertEquals(MemoryContracts.LearningPolicy.AUTO_LOW_RISK, gateway.policy);
            assertTrue(texts(root).stream().anyMatch(value -> value.contains("低风险 FACT")));

            policy.setValue(MemoryContracts.LearningPolicy.OFF);
            button(root, "丢弃").fire();
            assertEquals(MemoryContracts.LearningPolicy.AUTO_LOW_RISK, policy.getValue());
            button(root, "刷新").fire();
            assertFalse(page.dirty());
        });
    }

    @Test
    void 项目约定页只展示脱敏层级且保存安全fallbackBasename() {
        FxTestSupport.run(() -> {
            InstructionGateway gateway = new InstructionGateway();
            InstructionSettingsPage page = new InstructionSettingsPage(gateway);
            Parent root = attach(page);
            page.activate();

            assertTrue(texts(root).contains("PROJECT_TRUNCATED"));
            assertTrue(texts(root).stream().noneMatch(value -> value.contains("项目约定正文")));
            TextField fallback = fieldByAccessibleText(root, "项目约定备用文件名");
            fallback.setText("TEAM.md");
            assertTrue(page.dirty());
            page.warnUnsavedChanges();
            button(root, "保存备用文件名").fire();
            assertEquals(Optional.of("TEAM.md"), gateway.settings.fallbackBasename());

            ComboBox<?> executionRoot = comboByAccessibleText(root, "项目约定 execution root");
            executionRoot.getSelectionModel().selectLast();
            assertTrue(list(root, InstructionSourceResolution.class).getItems().stream()
                    .anyMatch(value -> value.relativePath().equals("module/AGENTS.md")));
            fallback.setText("../unsafe.md");
            button(root, "保存备用文件名").fire();
            assertTrue(texts(root).stream().anyMatch(value -> value.contains("basename")));
            page.discardDraft();
            button(root, "重新解析").fire();
        });
    }

    private static Parent attach(Parent root) {
        new Scene(root, 1_080, 760);
        root.applyCss();
        return root;
    }

    private static Button button(Parent root, String text) {
        return nodes(root, Button.class).stream()
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少按钮: " + text));
    }

    private static TextField fieldByAccessibleText(Parent root, String text) {
        return nodes(root, TextField.class).stream()
                .filter(value -> text.equals(value.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static ComboBox<?> comboByAccessibleText(Parent root, String text) {
        return nodes(root, ComboBox.class).stream()
                .filter(value -> text.equals(value.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static <T> ComboBox<T> combo(Parent root, Class<T> type) {
        ComboBox<?> found = nodes(root, ComboBox.class).stream()
                .filter(value -> !value.getItems().isEmpty())
                .filter(value -> type.isInstance(value.getItems().getFirst()))
                .findFirst()
                .orElseThrow();
        return castCombo(found);
    }

    private static <T> javafx.scene.control.ListView<T> list(Parent root, Class<T> type) {
        javafx.scene.control.ListView<?> found = nodes(root, javafx.scene.control.ListView.class).stream()
                .filter(value -> !value.getItems().isEmpty())
                .filter(value -> type.isInstance(value.getItems().getFirst()))
                .findFirst()
                .orElseThrow();
        return castList(found);
    }

    @SuppressWarnings("unchecked")
    private static <T> ComboBox<T> castCombo(ComboBox<?> value) {
        return (ComboBox<T>) value;
    }

    @SuppressWarnings("unchecked")
    private static <T> javafx.scene.control.ListView<T> castList(javafx.scene.control.ListView<?> value) {
        return (javafx.scene.control.ListView<T>) value;
    }

    private static List<String> texts(Parent root) {
        return nodes(root, Label.class).stream().map(Label::getText).toList();
    }

    private static void confirmNextDialog() {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(java.util.Objects::nonNull)
                .map(javafx.scene.Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .findFirst()
                .map(value -> value.lookupButton(ButtonType.OK))
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .ifPresent(Button::fire));
    }

    private static <T extends Node> List<T> nodes(Parent root, Class<T> type) {
        ArrayList<T> result = new ArrayList<>();
        Queue<Node> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node current = pending.remove();
            if (type.isInstance(current)) {
                result.add(type.cast(current));
            }
            if (current instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return List.copyOf(result);
    }

    private static BuiltinExtensionRpcContracts.Status status(
            String id,
            ExtensionAvailability availability,
            ExtensionState state,
            BuiltinExtensionRpcContracts.RuntimeKind runtime) {
        return new BuiltinExtensionRpcContracts.Status(
                id,
                id,
                "5.0.0",
                1,
                1,
                availability,
                state,
                runtime,
                Set.of(ContributionKind.COMMAND, ContributionKind.VIEW),
                DesktopTestFixtures.NOW);
    }

    private static final class BuiltinGateway implements BuiltinExtensionSettingsGateway {
        private final List<BuiltinExtensionRpcContracts.Status> statuses = new ArrayList<>();

        @Override
        public CompletionStage<List<BuiltinExtensionRpcContracts.Status>> builtinExtensions() {
            return CompletableFuture.completedFuture(List.copyOf(statuses));
        }

        @Override
        public CompletionStage<BuiltinExtensionRpcContracts.Status> setBuiltinExtensionEnabled(
                BuiltinExtensionRpcContracts.Status current, boolean enabled) {
            BuiltinExtensionRpcContracts.Status updated = new BuiltinExtensionRpcContracts.Status(
                    current.id(),
                    current.displayName(),
                    current.version(),
                    current.descriptorRevision(),
                    current.stateRevision() + 1,
                    current.availability(),
                    enabled ? ExtensionState.ENABLED : ExtensionState.DISABLED,
                    current.runtimeKind(),
                    current.contributionKinds(),
                    current.updatedAt().plusSeconds(1));
            statuses.set(statuses.indexOf(current), updated);
            return CompletableFuture.completedFuture(updated);
        }
    }

    private static final class LearningGateway implements LearningSettingsGateway {
        private final Workspace workspace = DesktopTestFixtures.workspace();
        private MemoryContracts.LearningPolicy policy = MemoryContracts.LearningPolicy.SUGGEST;
        private long revision = 1;

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(List.of(workspace));
        }

        @Override
        public CompletionStage<MemoryContracts.LearningSettings> read(WorkspaceId workspaceId) {
            return CompletableFuture.completedFuture(
                    new MemoryContracts.LearningSettings(revision, policy, DesktopTestFixtures.NOW));
        }

        @Override
        public CompletionStage<MemoryContracts.LearningSettings> update(
                WorkspaceId workspaceId, MemoryContracts.LearningPolicy next, CommandOptions options) {
            policy = next;
            revision++;
            return read(workspaceId);
        }
    }

    private static final class InstructionGateway implements InstructionSettingsGateway {
        private final Workspace workspace = DesktopTestFixtures.workspace();
        private final TestManagedWorktreeSettings worktrees = new TestManagedWorktreeSettings();
        private WorkspaceInstructionSettings settings =
                new WorkspaceInstructionSettings(workspace.id(), Optional.of("PROJECT.md"), 1, DesktopTestFixtures.NOW);

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(List.of(workspace));
        }

        @Override
        public CompletionStage<List<ManagedWorktree>> managedWorktrees(
                WorkspaceId workspaceId, boolean includeCleaned) {
            return CompletableFuture.completedFuture(worktrees.list(workspaceId, includeCleaned));
        }

        @Override
        public CompletionStage<InstructionResolution> instructionResolution(
                WorkspaceId workspaceId, Optional<WorktreeId> worktreeId) {
            List<InstructionSourceResolution> sources = List.of(
                    new InstructionSourceResolution(
                            InstructionScope.PROJECT,
                            "module/AGENTS.md",
                            Optional.of("a".repeat(64)),
                            40_000,
                            32_768,
                            true,
                            Optional.empty()),
                    new InstructionSourceResolution(
                            InstructionScope.GLOBAL,
                            "AGENTS.override.md",
                            Optional.empty(),
                            0,
                            0,
                            false,
                            Optional.of("READ_DENIED")));
            return CompletableFuture.completedFuture(new InstructionResolution(
                    sources,
                    "b".repeat(64),
                    0,
                    32_768,
                    List.of("PROJECT_TRUNCATED"),
                    Instant.parse("2026-09-01T00:00:00Z")));
        }

        @Override
        public CompletionStage<WorkspaceInstructionSettings> instructionSettings(WorkspaceId workspaceId) {
            return CompletableFuture.completedFuture(settings);
        }

        @Override
        public CompletionStage<WorkspaceInstructionSettings> updateInstructionSettings(
                WorkspaceId workspaceId, Optional<String> fallbackBasename, CommandOptions options) {
            String value = fallbackBasename.orElse("");
            if (!value.isEmpty() && !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("fallback must be a safe basename"));
            }
            settings = new WorkspaceInstructionSettings(
                    workspaceId,
                    fallbackBasename,
                    settings.revision() + 1,
                    settings.updatedAt().plusSeconds(1));
            return CompletableFuture.completedFuture(settings);
        }
    }
}
