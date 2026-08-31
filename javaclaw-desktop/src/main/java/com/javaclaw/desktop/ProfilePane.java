package com.javaclaw.desktop;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.model.ProfileInfo;

/** 原 Agent Studio 的左侧列表与右侧编辑器；人设与业务约定可编辑，固定底座和实际权限不由文本控制。 */
final class ProfilePane extends ManagedManagementPage {
    private final DesktopViewModel chat;
    private final ListView<ProfileInfo> profiles = ManagementForms.list(
            value -> value.name() + "\n" + DesktopPresentationMapper.profileKind(value.kind()) + " · "
                    + DesktopPresentationMapper.provider(value.provider()),
            "尚无智能体",
            "创建智能体后，可配置人设、模型、工具范围和有限运行预算。");
    private final BorderPane detail = new BorderPane();

    ProfilePane(ManagementViewModel model, DesktopViewModel chat) {
        super(model);
        this.chat = chat;
        setCenter(ManagementForms.split(profiles, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command("＋ 添加智能体", UiActionKind.PRIMARY, model, () -> requestNavigation(this::create)),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, () -> requestNavigation(this::reload))));
        guardSelection(profiles, this::edit);
        detail.setCenter(ManagementForms.emptyState("✦", "选择一个智能体", "编辑人设、模型、工具范围和有限运行预算。"));
        reload();
    }

    private void reload() {
        refreshing();
        String selectedId = profiles.getSelectionModel().getSelectedItem() == null
                ? null
                : profiles.getSelectionModel().getSelectedItem().id();
        model.execute(
                "读取智能体",
                sdk -> sdk.models().listProfiles(),
                values -> {
                    profiles.getItems().setAll(values);
                    var selected = values.stream()
                            .filter(value -> value.id().equals(selectedId))
                            .findFirst()
                            .orElse(values.isEmpty() ? null : values.getFirst());
                    if (selected != null) {
                        profiles.getSelectionModel().select(selected);
                    }
                    model.profilesChanged();
                    ready();
                },
                ignored -> loadFailed());
    }

    private void create() {
        var baseline = profiles.getItems().stream()
                .filter(value -> value.id().equals("profile_chat"))
                .findFirst()
                .orElse(null);
        edit(new ProfileInfo(
                "profile_" + UUID.randomUUID(),
                "新智能体",
                "CHAT",
                baseline == null ? "openai" : baseline.provider(),
                baseline == null ? "" : baseline.model(),
                "",
                Set.of(),
                "READ_ONLY",
                25,
                100,
                java.util.Map.of(),
                0,
                null));
    }

    private void edit(ProfileInfo original) {
        var name = ManagementForms.text(original.name(), "名称");
        var kind = ManagementForms.choices(
                List.of("CHAT", "PLAN", "LOOP", "WORKFLOW", "SDD", "SCHEDULE", "SUBAGENT"),
                DesktopPresentationMapper::profileKind,
                original.kind());
        var provider = ManagementForms.choices(
                List.of("openai", "anthropic", "google"), DesktopPresentationMapper::provider, original.provider());
        var modelName = ManagementForms.text(original.model(), "模型标识");
        var prompt = ManagementForms.area(original.systemPrompt(), 14);
        var tools = ManagementForms.area(String.join("\n", original.enabledTools()), 4);
        var sandbox = ManagementForms.choices(
                List.of("READ_ONLY", "WORKSPACE_WRITE", "HOST_FULL_ACCESS"),
                DesktopPresentationMapper::sandbox,
                original.requestedSandboxMode());
        kind.valueProperty().addListener((ignored, old, value) -> {
            if ("PLAN".equals(value)) {
                sandbox.setValue("READ_ONLY");
            }
        });
        var iterations = ManagementForms.number(original.maxIterations(), 1000);
        var calls = ManagementForms.number(original.maxModelCalls(), 1000);
        var tokens = ManagementForms.number(
                Integer.parseInt(original.attributes().getOrDefault("maxTokens", "200000")), 10_000_000);
        var seconds = ManagementForms.number(
                Integer.parseInt(original.attributes().getOrDefault("maxDurationSeconds", "3600")), 86_400);
        var save = ManagementForms.command("保存智能体", UiActionKind.PRIMARY, model, () -> {
            var attributes = new LinkedHashMap<>(original.attributes());
            attributes.put("maxTokens", tokens.getValue().toString());
            attributes.put("maxDurationSeconds", seconds.getValue().toString());
            var value = new ProfileInfo(
                    original.id(),
                    name.getText(),
                    kind.getValue(),
                    provider.getValue(),
                    modelName.getText(),
                    prompt.getText(),
                    Arrays.stream(tools.getText().split("[,\\s]+"))
                            .filter(item -> !item.isBlank())
                            .collect(Collectors.toSet()),
                    sandbox.getValue(),
                    iterations.getValue(),
                    calls.getValue(),
                    attributes,
                    original.revision(),
                    original.updatedAt());
            if ("HOST_FULL_ACCESS".equals(value.requestedSandboxMode())
                    && !ManagementForms.confirm(
                            this, model, "请求宿主权限上限", "此配置不授予执行权。每次实际 HOST_FULL_ACCESS 仍必须交互审批，无人值守永远不能使用。")) {
                return;
            }
            model.execute(
                    "保存智能体",
                    sdk -> sdk.models().putProfile(value, original.revision(), ManagementViewModel.key("profile-save")),
                    saved -> {
                        saveSucceeded();
                        edit(saved);
                        reload();
                    },
                    this::saveFailed);
        });
        var preview = ManagementForms.command(
                "提示词构成",
                UiActionKind.GHOST,
                model,
                () -> chat.previewPrompt(
                        original,
                        value -> ManagementForms.showText(
                                this,
                                "提示词构成",
                                value.layers().stream()
                                                .map(layer -> layer.id() + " v" + layer.version())
                                                .collect(Collectors.joining("\n"))
                                        + "\n\n工具：" + String.join("、", value.tools()) + "\n\n"
                                        + String.join("\n", value.warnings()))));
        var optimize = ManagementForms.command("优化提示词…", model, () -> {
            String input = prompt.getText();
            chat.optimizePrompt(
                    original,
                    input,
                    value -> {
                        var draft = ManagementForms.area(value.draft(), 16);
                        var form = ManagementForms.form(
                                ManagementForms.hint("原草稿不会自动覆盖；此处仅采用到编辑器，仍需显式保存。"),
                                ManagementForms.field("建议草稿", draft),
                                ManagementForms.hint("主要变化：\n" + String.join("\n", value.changes())),
                                ManagementForms.hint("注意事项：\n" + String.join("\n", value.warnings())));
                        ManagementForms.edit(this, "优化预览", form, draft::getText, accepted -> {
                            if (prompt.getText().equals(input) && value.expectedRevision() == original.revision()) {
                                prompt.setText(accepted);
                            } else {
                                ManagementForms.showText(this, "编辑冲突", "当前草稿已变化，未覆盖。生成结果已保存在对应 Turn。");
                            }
                        });
                    },
                    () -> {});
        });
        var remove = ManagementForms.command("删除智能体", UiActionKind.DANGER, model, () -> {
            if (ManagementForms.confirm(this, model, "删除智能体", "删除当前定义；历史 Turn 的配置快照保持不变。")) {
                model.execute(
                        "删除智能体",
                        sdk -> sdk.models()
                                .deleteProfile(
                                        original.id(), original.revision(), ManagementViewModel.key("profile-delete")),
                        ignored -> {
                            detail.setCenter(ManagementForms.emptyState("✓", "智能体已删除", "历史 Turn 的配置快照保持不变。"));
                            reload();
                        });
            }
        });
        for (var button : List.of(preview, optimize, remove)) {
            button.setVisible(original.revision() > 0);
            button.setManaged(original.revision() > 0);
        }
        var fields = ManagementForms.form(
                ManagementForms.section(
                        "身份与模型",
                        ManagementForms.field("名称", name),
                        ManagementForms.field("运行模式", kind),
                        ManagementForms.field("云 Provider", provider),
                        ManagementForms.field("模型", modelName)),
                ManagementForms.section(
                        "提示词", ManagementForms.field("人设与业务约定", prompt), ManagementForms.actions(preview, optimize)),
                ManagementForms.section(
                        "工具与权限",
                        ManagementForms.field("允许的工具（留空为实际可用目录；每行一个）", tools),
                        ManagementForms.field("请求的沙箱上限", sandbox)),
                ManagementForms.section(
                        "预算",
                        ManagementForms.actions(
                                ManagementForms.field("迭代", iterations), ManagementForms.field("模型调用", calls)),
                        ManagementForms.actions(
                                ManagementForms.field("Token 预算", tokens), ManagementForms.field("最长秒数", seconds))));
        editSession(original.name(), fields, save, this::reload);
        detail.setCenter(ManagementForms.editor(fields, save, remove));
    }
}
