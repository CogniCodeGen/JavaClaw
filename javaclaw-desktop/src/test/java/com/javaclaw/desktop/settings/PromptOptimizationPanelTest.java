package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.PromptOptimizationAdoption;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.PromptOptimizationProvenance;
import com.javaclaw.api.PromptOptimizationRef;
import com.javaclaw.api.PromptOptimizationResult;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.PromptOptimizationRpcContracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptOptimizationPanelTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void 面板要求精确计费确认并支持取消和人工采纳() {
        FxTestSupport.run(() -> {
            PanelGateway gateway = new PanelGateway();
            AtomicBoolean adopted = new AtomicBoolean();
            PromptOptimizationPanel panel = new PromptOptimizationPanel(gateway, () -> adopted.set(true));
            Parent root = attach(panel.content());
            panel.workspaceChanged(Optional.of(gateway.workspace));
            panel.selectRole(Optional.of(gateway.role));
            panel.activate();

            assertFalse(button(root, "生成优化草稿").isDisabled());
            completeTextDialog(PromptOptimizationRpcContracts.BILLING_CONFIRMATION);
            button(root, "生成优化草稿").fire();
            assertTrue(draftList(root)
                            .getSelectionModel()
                            .getSelectedItem()
                            .result()
                            .state()
                    == PromptOptimizationState.QUEUED);
            assertFalse(button(root, "取消任务").isDisabled());
            button(root, "取消任务").fire();
            assertTrue(draftList(root)
                            .getSelectionModel()
                            .getSelectedItem()
                            .result()
                            .state()
                    == PromptOptimizationState.CANCELLED);

            ListView<PromptOptimizationDraft> drafts = draftList(root);
            PromptOptimizationDraft ready = drafts.getItems().stream()
                    .filter(value -> value.result().state() == PromptOptimizationState.READY)
                    .findFirst()
                    .orElseThrow();
            drafts.getSelectionModel().select(ready);
            assertFalse(button(root, "采纳草稿").isDisabled());
            completeTextDialog(PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION);
            button(root, "采纳草稿").fire();
            assertTrue(adopted.get());
            assertTrue(texts(root).stream().anyMatch(value -> value.contains("已采纳为")));
            assertTrue(button(root, "采纳草稿").isDisabled());
            button(root, "刷新状态").fire();
        });
    }

    @Test
    void 无活动工作区或非活动Agent时不开放付费动作() {
        FxTestSupport.run(() -> {
            PanelGateway gateway = new PanelGateway();
            gateway.workspace = workspace(WorkspaceLifecycle.ARCHIVED);
            PromptOptimizationPanel panel = new PromptOptimizationPanel(gateway, () -> {});
            Parent root = attach(panel.content());
            panel.workspaceChanged(Optional.empty());
            panel.selectRole(Optional.of(role(RoleLifecycle.DISABLED)));
            panel.activate();

            assertTrue(button(root, "生成优化草稿").isDisabled());
            assertTrue(button(root, "刷新状态").isDisabled());
            assertTrue(texts(root).stream().anyMatch(value -> value.contains("请先保存Agent")));
            panel.selectRole(Optional.empty());
            assertTrue(texts(root).stream().anyMatch(value -> value.contains("请先保存Agent")));
        });
    }

    private static Parent attach(Node value) {
        Parent root = (Parent) value;
        new Scene(root, 920, 620);
        root.applyCss();
        return root;
    }

    private static Button button(Parent root, String text) {
        return nodes(root, Button.class).stream()
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> texts(Parent root) {
        return nodes(root, Label.class).stream().map(Label::getText).toList();
    }

    private static ListView<PromptOptimizationDraft> draftList(Parent root) {
        ListView<?> value = nodes(root, ListView.class).stream()
                .filter(candidate -> !candidate.getItems().isEmpty())
                .filter(candidate -> candidate.getItems().getFirst() instanceof PromptOptimizationDraft)
                .findFirst()
                .orElseThrow();
        return castList(value);
    }

    @SuppressWarnings("unchecked")
    private static ListView<PromptOptimizationDraft> castList(ListView<?> value) {
        return (ListView<PromptOptimizationDraft>) value;
    }

    private static void completeTextDialog(String confirmation) {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(java.util.Objects::nonNull)
                .map(javafx.scene.Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .findFirst()
                .ifPresent(dialog -> {
                    Node editor = dialog.lookup(".dialog-confirmation-editor");
                    if (editor instanceof TextField field) {
                        field.setText(confirmation);
                    }
                    Node accept = dialog.lookupButton(ButtonType.OK);
                    if (accept instanceof Button button) {
                        button.fire();
                    }
                }));
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

    private static Workspace workspace(WorkspaceLifecycle lifecycle) {
        return new Workspace(
                WorkspaceId.parse("00000000-0000-0000-0000-000000000001"),
                "Workspace",
                Path.of("/tmp/prompt-optimization-workspace"),
                lifecycle,
                1,
                NOW,
                NOW);
    }

    private static AgentRole role(RoleLifecycle lifecycle) {
        AgentRoleSpec spec = new AgentRoleSpec(
                "Profile",
                "角色测试",
                "原始说明",
                Optional.of(new com.javaclaw.api.ModelPreference(new ProviderRef("provider", 1, "model"))),
                Optional.empty(),
                new com.javaclaw.api.CapabilityNarrowing(Optional.of(Set.of()), Optional.empty()),
                com.javaclaw.api.PermissionConstraint.INHERIT,
                java.util.Map.of());
        return new AgentRole("role", 1, lifecycle, spec, false, NOW, NOW);
    }

    private static final class PanelGateway implements PromptOptimizationSettingsGateway {
        private Workspace workspace = workspace(WorkspaceLifecycle.ACTIVE);
        private final AgentRole role = role(RoleLifecycle.ACTIVE);
        private final List<PromptOptimizationDraft> drafts = new ArrayList<>();
        private int nextId = 10;

        private PanelGateway() {
            drafts.add(draft(2, PromptOptimizationState.READY, Optional.empty()));
        }

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(List.of(workspace));
        }

        @Override
        public CompletionStage<PromptOptimizationDraft> start(
                WorkspaceId workspaceId,
                AgentRoleRef profileRef,
                boolean billingConfirmed,
                String confirmation,
                CommandOptions options) {
            PromptOptimizationDraft queued = draft(nextId++, PromptOptimizationState.QUEUED, Optional.empty());
            replace(queued);
            return CompletableFuture.completedFuture(queued);
        }

        @Override
        public CompletionStage<List<PromptOptimizationDraft>> list(WorkspaceId workspaceId) {
            return CompletableFuture.completedFuture(List.copyOf(drafts));
        }

        @Override
        public CompletionStage<PromptOptimizationDraft> read(PromptOptimizationId id) {
            return CompletableFuture.completedFuture(require(id));
        }

        @Override
        public CompletionStage<PromptOptimizationDraft> cancel(
                PromptOptimizationId id, String reason, CommandOptions options) {
            PromptOptimizationDraft cancelled = copy(require(id), PromptOptimizationState.CANCELLED, Optional.empty());
            replace(cancelled);
            return CompletableFuture.completedFuture(cancelled);
        }

        @Override
        public CompletionStage<PromptOptimizationAdoption> adopt(
                PromptOptimizationId id, boolean adoptionConfirmed, String confirmation, CommandOptions options) {
            AgentRoleRef adoptedRef = new AgentRoleRef(role.id(), 2);
            PromptOptimizationDraft adopted = copy(require(id), PromptOptimizationState.READY, Optional.of(adoptedRef));
            replace(adopted);
            AgentRole updated = new AgentRole(
                    role.id(), 2, RoleLifecycle.ACTIVE, role.spec(), false, role.createdAt(), NOW.plusSeconds(1));
            return CompletableFuture.completedFuture(new PromptOptimizationAdoption(adopted, updated));
        }

        private PromptOptimizationDraft require(PromptOptimizationId id) {
            return drafts.stream()
                    .filter(value -> value.ref().id().equals(id))
                    .findFirst()
                    .orElseThrow();
        }

        private void replace(PromptOptimizationDraft value) {
            drafts.removeIf(candidate -> candidate.ref().id().equals(value.ref().id()));
            drafts.add(value);
        }

        private PromptOptimizationDraft draft(
                int suffix, PromptOptimizationState state, Optional<AgentRoleRef> adoptedRole) {
            PromptOptimizationId id =
                    PromptOptimizationId.parse(String.format("00000000-0000-0000-0000-%012d", suffix));
            PromptOptimizationRef ref = new PromptOptimizationRef(
                    id,
                    workspace.id(),
                    new AgentRoleRef(role.id(), role.revision()),
                    ThreadId.parse("00000000-0000-0000-0000-000000000003"),
                    TurnId.parse("00000000-0000-0000-0000-000000000004"));
            return new PromptOptimizationDraft(
                    ref,
                    result(state),
                    new PromptOptimizationProvenance("role-optimization-v1", DIGEST, NOW, NOW),
                    adoptedRole);
        }

        private static PromptOptimizationDraft copy(
                PromptOptimizationDraft source, PromptOptimizationState state, Optional<AgentRoleRef> adoptedRole) {
            return new PromptOptimizationDraft(source.ref(), result(state), source.provenance(), adoptedRole);
        }

        private static PromptOptimizationResult result(PromptOptimizationState state) {
            boolean ready = state == PromptOptimizationState.READY;
            return new PromptOptimizationResult(
                    state,
                    2,
                    ready ? Optional.of("优化后的说明") : Optional.empty(),
                    ready ? Optional.of(DIGEST) : Optional.empty(),
                    state == PromptOptimizationState.FAILED ? Optional.of("MODEL_FAILED") : Optional.empty());
        }
    }
}
