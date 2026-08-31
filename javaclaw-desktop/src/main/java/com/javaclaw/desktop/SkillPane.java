package com.javaclaw.desktop;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.SkillContentInfo;
import com.javaclaw.sdk.model.SkillInfo;
import com.javaclaw.sdk.model.SkillResourceInfo;

/** 原 Skill 中心的列表、正文、资源、导入导出和提案审阅；H2 为唯一权威，不监听本地目录。 */
final class SkillPane extends ManagedManagementPage {
    private final ListView<SkillInfo> skills = ManagementForms.list(
            value -> value.name() + "\n" + value.version() + " · " + (value.enabled() ? "已启用" : "未启用") + " · 修订 "
                    + value.revision(),
            "尚无 Skill",
            "新建或导入 Markdown / Bundle。目录先被发现，正文与资源只在命中后按需读取。");
    private final BorderPane detail = new BorderPane();
    private final AtomicLong selection = new AtomicLong();

    SkillPane(ManagementViewModel model) {
        super(model);
        setCenter(ManagementForms.split(skills, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command(
                        "＋ 新建",
                        UiActionKind.PRIMARY,
                        model,
                        () -> requestNavigation(() -> edit(new SkillContentInfo(
                                new SkillInfo(
                                        "skill_" + UUID.randomUUID(),
                                        "新技能",
                                        "1.0",
                                        JsonDocument.EMPTY_OBJECT,
                                        false,
                                        0,
                                        null,
                                        null),
                                "",
                                List.of())))),
                ManagementForms.command(
                        "导入 Markdown / Bundle",
                        UiActionKind.PRIMARY,
                        model,
                        () -> requestNavigation(this::importBundle)),
                ManagementForms.command("待审提案", model, this::proposals),
                ManagementForms.command("学习设置", model, () -> LearningDialog.show(this, model, model.workspaceId())),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, () -> requestNavigation(this::reload))));
        guardSelection(skills, value -> {
            long accepted = selection.incrementAndGet();
            model.execute("读取技能", sdk -> sdk.knowledge().readSkillContent(value.id()), content -> {
                if (accepted == selection.get()) {
                    edit(content);
                }
            });
        });
        detail.setCenter(ManagementForms.emptyState("◇", "创建或导入 Skill", "先发现目录，再按需读取完整指令；资源与脚本不全量注入提示词。"));
        reload();
    }

    private void reload() {
        refreshing();
        String selectedId = skills.getSelectionModel().getSelectedItem() == null
                ? null
                : skills.getSelectionModel().getSelectedItem().id();
        model.execute(
                "读取技能目录",
                sdk -> sdk.knowledge().listSkills(),
                values -> {
                    skills.getItems().setAll(values);
                    var selected = values.stream()
                            .filter(value -> value.id().equals(selectedId))
                            .findFirst()
                            .orElse(values.isEmpty() ? null : values.getFirst());
                    if (selected != null) {
                        skills.getSelectionModel().select(selected);
                    }
                    ready();
                },
                ignored -> loadFailed());
    }

    private void edit(SkillContentInfo content) {
        selection.incrementAndGet();
        var original = content.skill();
        var name = ManagementForms.text(original.name(), "技能名称");
        var version = ManagementForms.text(original.version(), "发布版本");
        var body = ManagementForms.area(content.instructions(), 12);
        var enabled = ManagementForms.check("启用此技能（下一个 Turn 生效；禁用立即撤销执行权）", original.enabled());
        var resources = ManagementForms.<SkillResourceInfo>list(
                value -> value.path() + (value.executable() ? " · 沙箱脚本" : " · 参考资料"));
        resources.getItems().setAll(content.resources());
        resources.setPrefHeight(150);
        java.util.function.Supplier<SkillContentInfo> draft = () -> new SkillContentInfo(
                new SkillInfo(
                        original.id(),
                        name.getText(),
                        version.getText(),
                        original.manifest(),
                        enabled.isSelected(),
                        original.revision(),
                        original.createdAt(),
                        original.updatedAt()),
                body.getText(),
                new ArrayList<>(resources.getItems()));
        var add = ManagementForms.button(
                "＋ 资源",
                () -> resourceEditor(
                        new SkillResourceInfo("references/guide.md", "text/markdown", "", false),
                        value -> resources.getItems().add(value)));
        var edit = ManagementForms.button("编辑资源", () -> {
            var selected = resources.getSelectionModel().getSelectedItem();
            if (selected != null) {
                int index = resources.getSelectionModel().getSelectedIndex();
                resourceEditor(selected, value -> resources.getItems().set(index, value));
            }
        });
        var remove = ManagementForms.button(
                "移除资源",
                UiActionKind.DANGER,
                () -> resources.getItems().remove(resources.getSelectionModel().getSelectedItem()));
        var save = ManagementForms.command("保存技能", UiActionKind.PRIMARY, model, () -> {
            var value = draft.get();
            boolean changedScripts = !original.enabled()
                            && value.skill().enabled()
                            && value.resources().stream().anyMatch(SkillResourceInfo::executable)
                    || !value.resources().equals(content.resources())
                            && value.resources().stream().anyMatch(SkillResourceInfo::executable);
            if (changedScripts
                    && !ManagementForms.confirm(this, model, "确认脚本变更", "此 Skill 包含可执行资源。保存不授予新权限；实际执行仍经过审批与沙箱。")) {
                return;
            }
            model.execute(
                    "保存技能",
                    sdk -> sdk.knowledge().saveSkillContent(value, ManagementViewModel.key("skill-save")),
                    saved -> {
                        saveSucceeded();
                        reload();
                        edit(new SkillContentInfo(saved, value.instructions(), value.resources()));
                    },
                    this::saveFailed);
        });
        var history = ManagementForms.command(
                "版本 / 回滚",
                model,
                () -> model.execute(
                        "读取技能历史",
                        sdk -> sdk.knowledge().skillHistory(original.id()),
                        values -> ManagementForms.review(
                                this,
                                model,
                                "技能版本",
                                values,
                                value -> "修订 " + value.revision() + " · " + value.version(),
                                value -> value.manifest().canonicalJson(),
                                selected -> model.execute(
                                        "恢复技能版本",
                                        sdk -> sdk.knowledge()
                                                .restoreSkill(
                                                        original.id(),
                                                        selected.revision(),
                                                        original.revision(),
                                                        ManagementViewModel.key("skill-restore")),
                                        value -> {
                                            reload();
                                            model.execute(
                                                    "读取恢复内容",
                                                    sdk -> sdk.knowledge().readSkillContent(value.id()),
                                                    this::edit);
                                        }),
                                null)));
        history.setVisible(original.revision() > 0);
        history.setManaged(original.revision() > 0);
        var export = ManagementForms.command("导出 Bundle", model, () -> exportBundle(draft.get()));
        var delete = ManagementForms.command("卸载", UiActionKind.DANGER, model, () -> {
            if (ManagementForms.confirm(this, model, "卸载技能", "移除当前启用关系，保留被历史记录引用的修订。")) {
                model.execute(
                        "卸载技能",
                        sdk -> sdk.knowledge()
                                .uninstallSkill(
                                        original.id(), original.revision(), ManagementViewModel.key("skill-remove")),
                        ignored -> {
                            detail.setCenter(ManagementForms.emptyState("✓", "Skill 已卸载", "被历史 Turn 引用的修订仍保留。"));
                            reload();
                        });
            }
        });
        delete.setVisible(original.revision() > 0);
        delete.setManaged(original.revision() > 0);
        var fields = ManagementForms.form(
                ManagementForms.section(
                        "指令",
                        ManagementForms.field("名称", name),
                        ManagementForms.field("版本", version),
                        ManagementForms.field("完整指令", body),
                        enabled),
                ManagementForms.section(
                        "资源与脚本",
                        ManagementForms.field("引用资源 / 脚本", resources),
                        ManagementForms.actions(add, edit, remove)),
                ManagementForms.hint("导入仅产生草稿；保存后进入 H2。文件后续变化不会自动覆盖已确认内容。"));
        editSession(original.name(), fields, save, this::reload);
        detail.setCenter(ManagementForms.editor(fields, save, history, export, delete));
    }

    private void resourceEditor(SkillResourceInfo value, java.util.function.Consumer<SkillResourceInfo> save) {
        var path = ManagementForms.text(value.path(), "Bundle 内相对路径");
        var mime = ManagementForms.text(value.mediaType(), "媒体类型");
        var content = ManagementForms.area(value.content(), 14);
        var executable = ManagementForms.check("声明为可执行脚本（Java / JShell）", value.executable());
        ManagementForms.edit(
                this,
                "Skill 资源",
                ManagementForms.form(
                        ManagementForms.field("相对路径", path),
                        ManagementForms.field("类型", mime),
                        ManagementForms.field("正文", content),
                        executable),
                () -> new SkillResourceInfo(path.getText(), mime.getText(), content.getText(), executable.isSelected()),
                save);
    }

    private void importBundle() {
        var file = model.dialogs()
                .chooseOpenFile(
                        this,
                        "导入 Skill 草稿",
                        List.of(new DesktopDialogGateway.FileType(
                                "Markdown / v4 Skill Bundle", List.of("*.md", "*.zip"))))
                .orElse(null);
        if (file != null) {
            model.execute("检查 Skill Bundle", sdk -> sdk.knowledge().importSkillDraft(file), this::edit);
        }
    }

    private void exportBundle(SkillContentInfo value) {
        var file = model.dialogs()
                .chooseSaveFile(
                        this,
                        "导出当前 Skill 草稿",
                        "skill-bundle.zip",
                        List.of(new DesktopDialogGateway.FileType("v4 Skill Bundle", List.of("*.zip"))))
                .orElse(null);
        if (file == null) {
            return;
        }
        boolean overwrite = Files.exists(file);
        if (overwrite && !ManagementForms.confirm(this, model, "覆盖导出文件", "仅覆盖你选中的文件：" + file)) {
            return;
        }
        model.execute(
                "导出 Skill Bundle", sdk -> sdk.knowledge().exportSkillBundle(value, file, overwrite), ignored -> {});
    }

    private void proposals() {
        String workspace = model.workspaceId();
        model.execute(
                "读取 Skill 提案",
                sdk -> sdk.knowledge().skillProposals(workspace),
                values -> ManagementForms.review(
                        this,
                        model,
                        "待审技能提案",
                        values.stream()
                                .filter(value -> "PENDING".equals(value.state()))
                                .toList(),
                        value -> value.name() + " · " + value.version(),
                        value -> value.reason() + "\n来源：" + String.join("、", value.sourceItemIds()) + "\n\n"
                                + value.manifest(),
                        value -> model.execute(
                                "接受 Skill 提案",
                                sdk -> sdk.knowledge()
                                        .reviewSkillProposal(
                                                value.id(),
                                                true,
                                                value.revision(),
                                                ManagementViewModel.key("skill-accept")),
                                ignored -> reload()),
                        value -> model.execute(
                                "拒绝 Skill 提案",
                                sdk -> sdk.knowledge()
                                        .reviewSkillProposal(
                                                value.id(),
                                                false,
                                                value.revision(),
                                                ManagementViewModel.key("skill-reject")),
                                ignored -> reload())));
    }
}
