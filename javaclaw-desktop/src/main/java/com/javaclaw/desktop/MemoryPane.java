package com.javaclaw.desktop;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import com.javaclaw.sdk.model.MemoryDetailInfo;
import com.javaclaw.sdk.model.MemoryInfo;

/** 原记忆中心的分类、详情和版本交互；每次写入都使用 SDK 的乐观锁和显式用户决定。 */
final class MemoryPane extends ManagedManagementPage {
    private final String workspace;
    private final ListView<MemoryInfo> entries = ManagementForms.list(
            value -> kindName(value.kind()) + " · "
                    + value.content().lines().findFirst().orElse("") + "\n已保存 · " + updated(value) + " · 修订 "
                    + value.revision(),
            "尚无记忆",
            "可以手工新建，或在学习设置中开启低风险记忆建议。");
    private final BorderPane detail = new BorderPane();
    private final TextField search = ManagementForms.text("", "搜索事实、情景、实体或内容");
    private final Label statistics = ManagementForms.hint("正在读取类型统计与最近更新时间…");
    private final AtomicLong selection = new AtomicLong();
    private List<MemoryInfo> all = List.of();
    private String filter = "全部";

    MemoryPane(ManagementViewModel model) {
        super(model);
        workspace = model.workspaceId();
        var kinds = ManagementForms.choices(
                List.of("全部", "FACT", "EPISODE", "ENTITY", "RELATION", "CORRECTION", "PERSONA"),
                MemoryPane::kindName,
                "全部");
        kinds.valueProperty().addListener((ignored, old, value) -> {
            filter = value;
            filter();
        });
        search.textProperty().addListener((ignored, old, value) -> filter());
        var left = new VBox(10, ManagementForms.field("记忆库", kinds), search, statistics, entries);
        left.setPadding(new javafx.geometry.Insets(12));
        VBox.setVgrow(entries, javafx.scene.layout.Priority.ALWAYS);
        setCenter(ManagementForms.split(left, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command(
                        "＋ 新建",
                        UiActionKind.PRIMARY,
                        model,
                        () -> requestNavigation(() -> edit(new MemoryDetailInfo(
                                null, workspace, "FACT", "", "", "", false, List.of(), 0, null, null)))),
                ManagementForms.command("待审提案", model, this::proposals),
                ManagementForms.command("学习设置", model, () -> LearningDialog.show(this, model, workspace)),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, () -> requestNavigation(this::reload))));
        guardSelection(entries, value -> {
            long revision = selection.incrementAndGet();
            model.execute("读取记忆", sdk -> sdk.knowledge().readMemory(value.id()), loaded -> {
                if (revision == selection.get()) {
                    edit(loaded);
                }
            });
        });
        detail.setCenter(ManagementForms.emptyState("◈", "选择一条记忆", "查看内容、来源、固定状态与不可变历史。"));
        reload();
    }

    private void reload() {
        refreshing();
        String selectedId = entries.getSelectionModel().getSelectedItem() == null
                ? null
                : entries.getSelectionModel().getSelectedItem().id();
        model.execute(
                "加载记忆",
                sdk -> sdk.knowledge().listMemories(workspace),
                values -> {
                    all = values;
                    filter(selectedId);
                    ready();
                },
                ignored -> loadFailed());
    }

    private void filter() {
        String selectedId = entries.getSelectionModel().getSelectedItem() == null
                ? null
                : entries.getSelectionModel().getSelectedItem().id();
        filter(selectedId);
    }

    private void filter(String selectedId) {
        String query = search.getText().toLowerCase(Locale.ROOT);
        entries.getItems()
                .setAll(all.stream()
                        .filter(value -> "全部".equals(filter) || filter.equals(value.kind()))
                        .filter(value ->
                                value.content().toLowerCase(Locale.ROOT).contains(query))
                        .toList());
        String byKind = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        MemoryInfo::kind, java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .entrySet()
                .stream()
                .map(entry -> kindName(entry.getKey()) + " " + entry.getValue())
                .collect(java.util.stream.Collectors.joining(" · "));
        String latest = all.stream()
                .map(MemoryInfo::updatedAt)
                .filter(java.util.Objects::nonNull)
                .max(java.time.Instant::compareTo)
                .map(MemoryPane::updated)
                .orElse("尚无更新时间");
        statistics.setText("匹配 " + entries.getItems().size() + " / " + all.size() + " · 最近更新 " + latest
                + (byKind.isBlank() ? "" : "\n" + byKind));
        var matching = entries.getItems().stream()
                .filter(value -> value.id().equals(selectedId))
                .findFirst()
                .orElse(entries.getItems().isEmpty() ? null : entries.getItems().getFirst());
        if (matching == null) {
            selection.incrementAndGet();
            detail.setCenter(ManagementForms.emptyState("◈", "没有匹配的记忆", "调整分类或搜索词，或创建一条新的记忆。"));
        } else {
            entries.getSelectionModel().select(matching);
        }
    }

    private void edit(MemoryDetailInfo value) {
        selection.incrementAndGet();
        var kinds = ManagementForms.choices(
                List.of("FACT", "EPISODE", "ENTITY", "RELATION", "CORRECTION", "PERSONA"),
                MemoryPane::kindName,
                value.kind());
        var subject = ManagementForms.text(value.subject(), "主体；如项目、实体名称");
        var attribute = ManagementForms.text(value.attribute(), "属性或关系；用于检测同对象冲突");
        var content = ManagementForms.area(value.content(), 12);
        var pinned = ManagementForms.check("固定此条内容，自动提取不得覆盖", value.pinned());
        var status = new Label(value.id() == null ? "未保存草稿" : value.pinned() ? "已固定" : "已保存");
        status.getStyleClass().add("management-status-badge");
        var save = ManagementForms.command("保存", UiActionKind.PRIMARY, model, () -> {
            var draft = new MemoryDetailInfo(
                    value.id(),
                    workspace,
                    kinds.getValue(),
                    subject.getText(),
                    attribute.getText(),
                    content.getText(),
                    pinned.isSelected(),
                    value.sourceItemIds(),
                    value.revision(),
                    value.createdAt(),
                    value.updatedAt());
            model.execute(
                    "保存记忆",
                    sdk -> sdk.knowledge().saveMemory(draft, value.revision(), ManagementViewModel.key("memory-save")),
                    saved -> {
                        saveSucceeded();
                        edit(saved);
                        reload();
                    },
                    this::saveFailed);
        });
        var history = ManagementForms.command(
                "历史版本",
                model,
                () -> model.execute(
                        "读取历史",
                        sdk -> sdk.knowledge().memoryHistory(value.id()),
                        versions -> ManagementForms.review(
                                this,
                                model,
                                "记忆版本",
                                versions,
                                item -> "修订 " + item.revision() + " · " + item.updatedAt(),
                                MemoryPane::describe,
                                selected -> model.execute(
                                        "恢复历史为新版本",
                                        sdk -> sdk.knowledge()
                                                .restoreMemory(
                                                        value.id(),
                                                        selected.revision(),
                                                        value.revision(),
                                                        ManagementViewModel.key("memory-restore")),
                                        saved -> {
                                            edit(saved);
                                            reload();
                                        }),
                                null)));
        var delete = ManagementForms.command("删除当前条目", UiActionKind.DANGER, model, () -> {
            if (ManagementForms.confirm(this, model, "删除记忆", "删除当前条目，历史修订和已完成 Turn 的引用仍保留。")) {
                model.execute(
                        "删除记忆",
                        sdk -> sdk.knowledge()
                                .deleteMemory(value.id(), value.revision(), ManagementViewModel.key("memory-delete")),
                        ignored -> {
                            detail.setCenter(ManagementForms.emptyState("✓", "记忆已删除", "历史版本和已完成 Turn 的引用仍保留。"));
                            reload();
                        });
            }
        });
        history.setVisible(value.id() != null);
        history.setManaged(value.id() != null);
        delete.setVisible(value.id() != null);
        delete.setManaged(value.id() != null);
        var fields = ManagementForms.form(
                status,
                ManagementForms.section(
                        "内容",
                        ManagementForms.field("类别", kinds),
                        ManagementForms.field("主体", subject),
                        ManagementForms.field("属性 / 关系", attribute),
                        ManagementForms.field("内容", content),
                        pinned),
                ManagementForms.section(
                        "来源",
                        ManagementForms.hint("来源 Item："
                                + (value.sourceItemIds().isEmpty()
                                        ? "用户手工编辑"
                                        : String.join("、", value.sourceItemIds())))),
                ManagementForms.section(
                        "历史与修订",
                        ManagementForms.hint("修订 " + value.revision() + " · 最近更新 "
                                + updated(value.updatedAt())
                                + "。历史恢复会创建新版本，不覆盖不可变历史。"),
                        ManagementForms.hint("敏感信息不得写入 Memory；Persona 变更需要用户确认保存。")));
        editSession(value.id() == null ? "新记忆" : kindName(value.kind()), fields, save, this::reload);
        detail.setCenter(ManagementForms.editor(fields, save, history, delete));
    }

    private void proposals() {
        model.execute(
                "读取记忆提案",
                sdk -> sdk.knowledge().memoryProposals(workspace),
                values -> ManagementForms.review(
                        this,
                        model,
                        "待审记忆提案",
                        values.stream()
                                .filter(value -> "PENDING".equals(value.state()))
                                .toList(),
                        value -> kindName(value.draft().kind()) + " · " + value.reason(),
                        value -> describe(value.draft()) + "\n\n" + value.reason(),
                        value -> model.execute(
                                "接受提案",
                                sdk -> sdk.knowledge()
                                        .reviewMemoryProposal(
                                                value.id(),
                                                true,
                                                value.revision(),
                                                ManagementViewModel.key("memory-accept")),
                                ignored -> reload()),
                        value -> model.execute(
                                "拒绝提案",
                                sdk -> sdk.knowledge()
                                        .reviewMemoryProposal(
                                                value.id(),
                                                false,
                                                value.revision(),
                                                ManagementViewModel.key("memory-reject")),
                                ignored -> reload())));
    }

    private static String describe(MemoryDetailInfo value) {
        return kindName(value.kind()) + " · " + value.subject() + " → " + value.attribute() + "\n\n" + value.content()
                + "\n\n固定：" + value.pinned() + "\n来源：" + String.join("、", value.sourceItemIds());
    }

    private static String kindName(String kind) {
        return switch (kind) {
            case "FACT" -> "事实";
            case "EPISODE" -> "情景记忆";
            case "ENTITY" -> "实体";
            case "RELATION" -> "实体关系";
            case "CORRECTION" -> "纠错";
            case "PERSONA" -> "人格";
            default -> kind;
        };
    }

    private static String updated(MemoryInfo value) {
        return updated(value.updatedAt());
    }

    private static String updated(java.time.Instant value) {
        return value == null
                ? "时间未知"
                : java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(value);
    }
}
