package com.javaclaw.desktop;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.model.AutomationDefinitionInfo;
import com.javaclaw.sdk.model.AutomationInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.ProfileInfo;

/** Loop、Workflow、SDD 共用管理外壳，领域定义与执行策略仍由同一 App Server 内核处理。 */
final class AutomationPane extends ManagedManagementPage {
    private final String workspace;
    private final ListView<AutomationInfo> definitions = ManagementForms.list(
            value -> value.name() + "\n" + DesktopPresentationMapper.profileKind(value.kind()) + " · "
                    + DesktopPresentationMapper.status(value.status()),
            "尚无自动化定义",
            "创建 Loop、Workflow 或 SDD，配置有限预算、验收条件和恢复策略。");
    private final BorderPane detail = new BorderPane();
    private List<ProfileInfo> profiles = List.of();
    private long selection;

    AutomationPane(ManagementViewModel model) {
        super(model);
        workspace = model.workspaceId();
        setCenter(ManagementForms.split(definitions, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command(
                        "＋ Loop", UiActionKind.PRIMARY, model, () -> requestNavigation(() -> create("LOOP"))),
                ManagementForms.command(
                        "＋ Workflow", UiActionKind.PRIMARY, model, () -> requestNavigation(() -> create("WORKFLOW"))),
                ManagementForms.command(
                        "＋ SDD", UiActionKind.PRIMARY, model, () -> requestNavigation(() -> create("SDD"))),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, () -> requestNavigation(this::reload))));
        guardSelection(definitions, value -> {
            long accepted = ++selection;
            model.execute("读取自动化定义", sdk -> sdk.automations().readDefinition(value.id()), definition -> {
                if (accepted == selection) {
                    edit(value, definition);
                }
            });
        });
        detail.setCenter(ManagementForms.emptyState("∞", "创建自动化", "所有自动化运行在稳定 Thread 中。暂停后以新 Turn 恢复；已确认副作用不会重发。"));
        reload();
    }

    private void reload() {
        refreshing();
        String selectedId = definitions.getSelectionModel().getSelectedItem() == null
                ? null
                : definitions.getSelectionModel().getSelectedItem().id();
        model.execute(
                "读取自动化",
                sdk -> sdk.models().listProfiles().thenCombine(sdk.automations().list(), Catalog::new),
                catalog -> {
                    profiles = catalog.profiles();
                    var filtered = catalog.definitions().stream()
                            .filter(value -> workspace.equals(value.workspaceId()))
                            .toList();
                    definitions.getItems().setAll(filtered);
                    var selected = filtered.stream()
                            .filter(value -> value.id().equals(selectedId))
                            .findFirst()
                            .orElse(filtered.isEmpty() ? null : filtered.getFirst());
                    if (selected != null) {
                        definitions.getSelectionModel().select(selected);
                    }
                    ready();
                },
                ignored -> loadFailed());
    }

    private void create(String kind) {
        String profile = profiles.stream()
                .filter(value -> kind.equals(value.kind()))
                .map(ProfileInfo::id)
                .findFirst()
                .orElse("");
        String childProfile = profiles.stream()
                .filter(value -> "SUBAGENT".equals(value.kind()))
                .map(ProfileInfo::id)
                .findFirst()
                .orElse("");
        var value = new AutomationInfo(
                null,
                kind,
                "新" + DesktopPresentationMapper.profileKind(kind),
                workspace,
                profile,
                "",
                JsonDocument.EMPTY_OBJECT,
                "DRAFT",
                null,
                null,
                0,
                null,
                null);
        List<AutomationDefinitionInfo.Node> nodes = kind.equals("WORKFLOW")
                ? List.of(
                        new AutomationDefinitionInfo.Node("start", "START", "agent", "", 1, Map.of()),
                        new AutomationDefinitionInfo.Node(
                                "agent", "AGENT", "end", "", 1, Map.of("task", "完成当前节点任务", "profileId", childProfile)),
                        new AutomationDefinitionInfo.Node("end", "END", "", "", 1, Map.of()))
                : List.of();
        edit(
                value,
                new AutomationDefinitionInfo(
                        kind.equals("WORKFLOW") ? 100 : 25, 100, 200_000, 3_600, 3, "", List.of(), nodes, Map.of()));
    }

    private void edit(AutomationInfo original, AutomationDefinitionInfo definition) {
        selection++;
        var name = ManagementForms.text(original.name(), "名称");
        var prompt = ManagementForms.area(original.prompt(), 5);
        var profile = ManagementForms.choices(
                profiles.stream()
                        .filter(value -> original.kind().equals(value.kind()))
                        .toList(),
                value -> value.name() + " · " + value.model(),
                profiles.stream()
                        .filter(value -> value.id().equals(original.profileId()))
                        .findFirst()
                        .orElse(null));
        var iterations = ManagementForms.number(definition.maxIterations(), 1_000);
        var calls = ManagementForms.number(definition.maxModelCalls(), 1_000);
        var tokens = ManagementForms.number(definition.maxTokens(), 10_000_000);
        var seconds = ManagementForms.number(definition.maxDurationSeconds(), 86_400);
        var noProgress = ManagementForms.number(definition.noProgressLimit(), 25);
        var specification = ManagementForms.area(definition.specification(), 8);
        var importedDocuments = new javafx.beans.property.SimpleObjectProperty<>(definition.openSpecDocuments());
        var documents = ManagementForms.<String>list(value -> value);
        documents.setPrefHeight(110);
        var taskDocuments = ManagementForms.hint("");
        Runnable refreshDocuments = () -> {
            var paths = new java.util.TreeSet<>(importedDocuments.get().keySet());
            documents.getItems().setAll(paths);
            String tasks = paths.stream()
                    .filter(path -> {
                        String normalized = path.toLowerCase(java.util.Locale.ROOT);
                        return normalized.contains("task") || normalized.contains("任务");
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));
            taskDocuments.setText(tasks.isBlank() ? "尚未导入 tasks 文档；执行前应在 OpenSpec change 中明确可核验任务。" : tasks);
        };
        refreshDocuments.run();
        var importOpenSpec = ManagementForms.command("导入 OpenSpec…", model, () -> {
            var file = model.dialogs()
                    .chooseOpenFile(
                            this,
                            "选择 OpenSpec change ZIP",
                            List.of(new DesktopDialogGateway.FileType("OpenSpec ZIP", List.of("*.zip"))))
                    .orElse(null);
            if (file != null) {
                model.execute("读取 OpenSpec 预览", sdk -> sdk.automations().importOpenSpec(file), preview -> {
                    String content = new java.util.TreeMap<>(preview.documents())
                            .entrySet().stream()
                                    .map(entry -> "── " + entry.getKey() + " ──\n" + entry.getValue())
                                    .collect(java.util.stream.Collectors.joining("\n\n"));
                    ManagementForms.showText(
                            this, "OpenSpec 导入预览", content + "\n\n" + String.join("\n", preview.warnings()));
                    if (ManagementForms.confirm(
                            this,
                            model,
                            "采用此导入草稿？",
                            "SHA-256：" + preview.sourceSha256() + "\n只替换当前编辑器的参考文档，保存定义后才写入 H2。勾选标记不会成为已执行或审批凭据。")) {
                        importedDocuments.set(preview.documents());
                        refreshDocuments.run();
                    }
                });
            }
        });
        var viewDocument = ManagementForms.button("查看文档", () -> {
            String path = documents.getSelectionModel().getSelectedItem();
            if (path != null) {
                ManagementForms.showText(this, path, importedDocuments.get().get(path));
            }
        });
        var exportOpenSpec = ManagementForms.command("导出 OpenSpec…", model, () -> {
            var file = model.dialogs()
                    .chooseSaveFile(
                            this,
                            "导出 H2 保存的 OpenSpec 产物",
                            "openspec-change.zip",
                            List.of(new DesktopDialogGateway.FileType("OpenSpec ZIP", List.of("*.zip"))))
                    .orElse(null);
            if (file != null
                    && (!Files.exists(file) || ManagementForms.confirm(this, model, "替换导出文件？", file.toString()))) {
                boolean replace = Files.exists(file);
                model.execute(
                        "导出 OpenSpec",
                        sdk -> sdk.automations().exportOpenSpec(original.id(), file, replace),
                        ignored -> {});
            }
        });
        exportOpenSpec.setVisible(original.id() != null);
        exportOpenSpec.setManaged(exportOpenSpec.isVisible());
        var openSpecForm = ManagementForms.form(
                ManagementForms.field("OpenSpec 导入文档（H2 权威）", documents),
                ManagementForms.actions(importOpenSpec, viewDocument, exportOpenSpec));
        var criteria = ManagementForms.<AutomationDefinitionInfo.Criterion>list(
                value -> value.description() + "\n" + DesktopPresentationMapper.status(value.kind()) + " · "
                        + DesktopPresentationMapper.text(value.tool(), "人工确认"));
        criteria.getItems().setAll(definition.criteria());
        criteria.setPrefHeight(130);
        var addCriterion = ManagementForms.button(
                "＋ 验收条件",
                () -> AutomationEditors.criterion(
                        this,
                        new AutomationDefinitionInfo.Criterion(
                                "criterion_" + UUID.randomUUID().toString().substring(0, 8),
                                "",
                                "USER_CONFIRMATION",
                                "",
                                JsonDocument.EMPTY_OBJECT,
                                "",
                                "确认"),
                        value -> criteria.getItems().add(value)));
        var editCriterion = ManagementForms.button("编辑条件", () -> {
            int index = criteria.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                AutomationEditors.criterion(
                        this,
                        criteria.getItems().get(index),
                        value -> criteria.getItems().set(index, value));
            }
        });
        var removeCriterion = ManagementForms.button(
                "移除条件",
                UiActionKind.DANGER,
                () -> criteria.getItems().remove(criteria.getSelectionModel().getSelectedItem()));
        var nodes = ManagementForms.<AutomationDefinitionInfo.Node>list(value -> value.id() + " · "
                + DesktopPresentationMapper.status(value.kind()) + "\n→ "
                + DesktopPresentationMapper.text(value.next(), "结束")
                + (value.otherwise().isBlank() ? "" : " / " + value.otherwise()) + " · 最多 " + value.maxVisits()
                + " 次");
        nodes.getItems().setAll(definition.nodes());
        nodes.setPrefHeight(240);
        var addNode = ManagementForms.button(
                "＋ 节点",
                () -> AutomationEditors.node(
                        this,
                        new AutomationDefinitionInfo.Node(
                                "node_" + UUID.randomUUID().toString().substring(0, 8),
                                "TRANSFORM",
                                "end",
                                "",
                                1,
                                Map.of("operation", "constant")),
                        profiles,
                        value -> nodes.getItems().add(value)));
        var editNode = ManagementForms.button("编辑节点", () -> {
            int index = nodes.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                AutomationEditors.node(
                        this,
                        nodes.getItems().get(index),
                        profiles,
                        value -> nodes.getItems().set(index, value));
            }
        });
        var removeNode = ManagementForms.button(
                "移除节点",
                UiActionKind.DANGER,
                () -> nodes.getItems().remove(nodes.getSelectionModel().getSelectedItem()));
        var nodeForm = new WorkflowGraphEditor(nodes, addNode, editNode, removeNode);
        nodeForm.setVisible("WORKFLOW".equals(original.kind()));
        nodeForm.setManaged(nodeForm.isVisible());
        var specificationForm = ManagementForms.field("初始规格（SDD）", specification);
        var sddTabs = new javafx.scene.control.TabPane();
        sddTabs.getStyleClass().add("sdd-segment-tabs");
        sddTabs.getTabs()
                .addAll(
                        new javafx.scene.control.Tab(
                                "规格",
                                ManagementForms.form(
                                        specificationForm, ManagementForms.hint("规格描述问题、范围和验收边界；保存后进入版本化定义。"))),
                        new javafx.scene.control.Tab(
                                "设计",
                                ManagementForms.form(
                                        openSpecForm, ManagementForms.hint("设计文档仍来自经过预览确认的 OpenSpec ZIP，不执行文档中的指令。"))),
                        new javafx.scene.control.Tab(
                                "任务",
                                ManagementForms.form(
                                        ManagementForms.field("任务文档", taskDocuments),
                                        ManagementForms.hint("任务列表是规格产物，不代表已执行或已获审批。"))),
                        new javafx.scene.control.Tab(
                                "执行",
                                ManagementForms.form(ManagementForms.hint(
                                        "保存并运行后，执行沿用稳定 Thread；通过固定操作栏的“步骤 / 规格 / 验收”查看持久 Items。"))),
                        new javafx.scene.control.Tab(
                                "核验",
                                ManagementForms.form(
                                        ManagementForms.hint("下方验收条件定义自动检查或人工确认；成功状态只来自 App Server 的持久执行结果。"))));
        sddTabs.getTabs().forEach(tab -> tab.setClosable(false));
        sddTabs.setVisible("SDD".equals(original.kind()));
        sddTabs.setManaged(sddTabs.isVisible());
        var save = ManagementForms.command("保存定义", UiActionKind.PRIMARY, model, () -> {
            if (profile.getValue() == null) {
                model.validationError("请选择与自动化类型匹配的 Agent Profile");
                return;
            }
            String graphProblem =
                    "WORKFLOW".equals(original.kind()) ? WorkflowGraphEditor.validate(nodes.getItems()) : null;
            if (graphProblem != null) {
                model.validationError("Workflow 图结构问题：" + graphProblem);
                return;
            }
            if (name.getText().isBlank() || prompt.getText().isBlank()) {
                model.validationError("名称和目标不能为空");
                return;
            }
            var value = new AutomationInfo(
                    original.id(),
                    original.kind(),
                    name.getText(),
                    workspace,
                    profile.getValue().id(),
                    prompt.getText(),
                    original.definition(),
                    original.status(),
                    original.threadId(),
                    original.activeTurnId(),
                    original.revision(),
                    original.createdAt(),
                    original.updatedAt());
            var draft = new AutomationDefinitionInfo(
                    iterations.getValue(),
                    calls.getValue(),
                    tokens.getValue(),
                    seconds.getValue(),
                    noProgress.getValue(),
                    specification.getText(),
                    new ArrayList<>(criteria.getItems()),
                    new ArrayList<>(nodes.getItems()),
                    importedDocuments.get());
            model.execute(
                    "校验并保存自动化",
                    sdk -> sdk.automations().putDefinition(value, draft, ManagementViewModel.key("automation-save")),
                    saved -> {
                        saveSucceeded();
                        reload();
                        edit(saved, draft);
                    },
                    this::saveFailed);
        });
        var run = ManagementForms.command("运行自动化", UiActionKind.PRIMARY, model, () -> runAutomation(original.id()));
        var resume = ManagementForms.command(
                "从检查点恢复",
                model,
                () -> model.execute(
                        "恢复自动化",
                        sdk -> sdk.automations().resume(original.id(), ManagementViewModel.key("automation-resume")),
                        turn -> {
                            model.showThread(turn.threadId());
                            reload();
                        }));
        var interrupt = ManagementForms.command(
                "暂停 / 取消",
                UiActionKind.DANGER,
                model,
                () -> model.execute("请求中断自动化", sdk -> sdk.automations().interrupt(original.id()), ignored -> reload()));
        var history = ManagementForms.command(
                "步骤 / 规格 / 验收",
                model,
                () -> model.execute(
                        "读取执行记录",
                        sdk -> sdk.automations().items(original.id()),
                        values -> ManagementForms.showText(
                                this,
                                "执行记录",
                                values.stream()
                                        .map(ItemPresenter::text)
                                        .collect(java.util.stream.Collectors.joining("\n\n")))));
        var remove = ManagementForms.command("删除定义", UiActionKind.DANGER, model, () -> {
            if (ManagementForms.confirm(this, model, "删除自动化", "仅删除定义，保留执行 Thread 与产物。活动 Turn 必须先中断。")) {
                model.execute(
                        "删除自动化",
                        sdk -> sdk.automations()
                                .delete(
                                        original.id(),
                                        original.revision(),
                                        ManagementViewModel.key("automation-delete")),
                        ignored -> {
                            detail.setCenter(ManagementForms.emptyState("✓", "自动化定义已删除", "执行 Thread、历史步骤和产物仍保留。"));
                            reload();
                        });
            }
        });
        for (var button : List.of(run, resume, interrupt, history, remove)) {
            button.setVisible(original.id() != null);
            button.setManaged(original.id() != null);
        }
        var fields = ManagementForms.form(
                ManagementForms.hint(DesktopPresentationMapper.profileKind(original.kind()) + " · "
                        + DesktopPresentationMapper.status(original.status()) + " · 修订 " + original.revision()),
                ManagementForms.section(
                        "目标与执行者",
                        ManagementForms.field("名称", name),
                        ManagementForms.field("执行 Profile", profile),
                        ManagementForms.field("目标", prompt)),
                ManagementForms.section(
                        "预算与停机条件",
                        ManagementForms.actions(
                                ManagementForms.field("步骤 / 迭代", iterations),
                                ManagementForms.field("模型调用", calls),
                                ManagementForms.field("Token 预算", tokens)),
                        ManagementForms.actions(
                                ManagementForms.field("最长秒数", seconds), ManagementForms.field("无进展阈值", noProgress)),
                        ManagementForms.hint("0 继承有限上限，不代表无限。恢复沿用原执行剩余预算与权限上限。")),
                sddTabs,
                nodeForm,
                ManagementForms.section(
                        "验收条件",
                        ManagementForms.field("验收条件（留空时要求人工确认）", criteria),
                        ManagementForms.actions(addCriterion, editCriterion, removeCriterion)));
        editSession(
                original.id() == null ? "新" + DesktopPresentationMapper.profileKind(original.kind()) : original.name(),
                fields,
                save,
                this::reload,
                importedDocuments);
        detail.setCenter(ManagementForms.editor(fields, save, run, resume, interrupt, history, remove));
    }

    private void runAutomation(String id) {
        Runnable start = () -> model.execute(
                "启动自动化", sdk -> sdk.automations().start(id, ManagementViewModel.key("automation-start")), turn -> {
                    model.showThread(turn.threadId());
                    reload();
                });
        if (!dirtyProperty().get()) {
            if (ManagementForms.confirm(this, model, "执行自动化", "使用服务器已保存的修订启动。")) {
                start.run();
            }
            return;
        }
        model.dialogs()
                .choose(this, "运行自动化", "当前定义有未保存修改。", List.of("保存并运行", "运行已保存版本"))
                .ifPresent(choice -> {
                    if ("保存并运行".equals(choice)) {
                        requestSave(success -> {
                            if (success) {
                                start.run();
                            }
                        });
                    } else {
                        start.run();
                    }
                });
    }

    private record Catalog(List<ProfileInfo> profiles, List<AutomationInfo> definitions) {}
}
