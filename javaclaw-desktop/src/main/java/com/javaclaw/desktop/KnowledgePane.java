package com.javaclaw.desktop;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import com.javaclaw.sdk.model.KnowledgeSourceInfo;
import com.javaclaw.sdk.model.KnowledgeSourceStatsInfo;

/** 原知识中心的文档、检索测试与索引版本；页面只提交附件引用，解析在受限 Worker 完成。 */
final class KnowledgePane extends ManagedManagementPage {
    private final String workspace;
    private final ListView<KnowledgeSourceInfo> sources =
            ManagementForms.list(this::sourceLabel, "尚无知识源", "导入文档后，可在这里查看正文、检索状态和索引版本。");
    private final BorderPane detail = new BorderPane();
    private final javafx.scene.control.TextField search = ManagementForms.text("", "搜索知识源");
    private List<KnowledgeSourceInfo> allSources = List.of();
    private Map<String, KnowledgeSourceStatsInfo> stats = Map.of();
    private String statusFilter = "全部";

    KnowledgePane(ManagementViewModel model) {
        super(model);
        workspace = model.workspaceId();
        var statuses = ManagementForms.choices(
                List.of("全部", "READY", "INDEXING", "FAILED"),
                value -> "全部".equals(value) ? value : DesktopPresentationMapper.status(value),
                "全部");
        statuses.valueProperty().addListener((ignored, old, value) -> {
            statusFilter = value;
            filterSources();
        });
        search.textProperty().addListener((ignored, old, value) -> filterSources());
        setTop(ManagementForms.actions(
                ManagementForms.command("＋ 导入文档", UiActionKind.PRIMARY, model, this::importDocument),
                ManagementForms.command("检索测试", model, this::search),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, this::reload)));
        var left = new VBox(10, ManagementForms.field("状态", statuses), search, sources);
        left.setPadding(new javafx.geometry.Insets(12));
        VBox.setVgrow(sources, javafx.scene.layout.Priority.ALWAYS);
        setCenter(ManagementForms.split(left, detail));
        guardSelection(sources, this::showSource);
        detail.setCenter(ManagementForms.emptyState(
                "⌕", "导入第一个知识源", "支持 PDF、TXT、Markdown、CSV、JSON、XML、HTML 和 DOCX；未配置向量模型时自动使用关键词检索。"));
        reload();
    }

    private void reload() {
        refreshing();
        String selectedId = sources.getSelectionModel().getSelectedItem() == null
                ? null
                : sources.getSelectionModel().getSelectedItem().id();
        model.execute(
                "读取知识源与索引统计",
                sdk -> sdk.knowledge()
                        .listSources(workspace)
                        .thenCombine(sdk.knowledge().sourceStats(workspace), KnowledgeCatalog::new),
                catalog -> {
                    allSources = catalog.sources();
                    stats = catalog.stats().stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    KnowledgeSourceStatsInfo::sourceId, Function.identity()));
                    filterSources();
                    var selected = sources.getItems().stream()
                            .filter(value -> value.id().equals(selectedId))
                            .findFirst()
                            .orElse(
                                    sources.getItems().isEmpty()
                                            ? null
                                            : sources.getItems().getFirst());
                    if (selected != null) {
                        sources.getSelectionModel().select(selected);
                    }
                    sources.refresh();
                    ready();
                },
                ignored -> loadFailed());
    }

    private void filterSources() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        sources.getItems()
                .setAll(allSources.stream()
                        .filter(value -> "全部".equals(statusFilter) || statusFilter.equals(value.status()))
                        .filter(value ->
                                value.displayName().toLowerCase(Locale.ROOT).contains(query))
                        .toList());
    }

    private void importDocument() {
        var file = model.dialogs()
                .chooseOpenFile(
                        this,
                        "导入知识文档",
                        List.of(new DesktopDialogGateway.FileType(
                                "文档",
                                List.of("*.pdf", "*.txt", "*.md", "*.csv", "*.json", "*.xml", "*.html", "*.docx"))))
                .orElse(null);
        if (file == null) {
            return;
        }
        String key = ManagementViewModel.key("knowledge-import");
        model.execute(
                "上传并建立知识索引",
                sdk -> sdk.attachments()
                        .upload(file, mediaType(file), key + "-upload")
                        .thenCompose(attachment -> sdk.knowledge()
                                .importSource(
                                        workspace,
                                        attachment,
                                        file.getFileName().toString(),
                                        key)),
                value -> {
                    reload();
                    showSource(value);
                });
    }

    private void showSource(KnowledgeSourceInfo source) {
        KnowledgeSourceStatsInfo sourceStats = stats.get(source.id());
        var content = new MarkdownView();
        content.getStyleClass().add("management-read-only-document");
        content.show("点击“读取正文”查看当前版本；不会重新解析文档。", false, ignored -> {});
        var read = ManagementForms.command(
                "读取正文",
                model,
                () -> model.execute(
                        "读取正文",
                        sdk -> sdk.knowledge().sourceContent(source.id(), source.revision()),
                        value -> content.show(
                                value.length() <= 120_000
                                        ? value
                                        : value.substring(0, 120_000) + "\n\n[页面仅显示前 120,000 字符；完整内容保存在知识库中。]",
                                true,
                                uri -> ArtifactViewer.openLink(this, model, uri))));
        var history = ManagementForms.command(
                "索引版本",
                model,
                () -> model.execute(
                        "读取索引版本",
                        sdk -> sdk.knowledge().sourceHistory(source.id()),
                        values -> ManagementForms.showText(
                                this,
                                "索引版本",
                                values.stream()
                                        .map(value -> "修订 " + value.revision() + " · "
                                                + DesktopPresentationMapper.status(value.status()) + "\n正文摘要："
                                                + value.contentSha256() + "\n解析器：" + value.extractorFingerprint()
                                                + "\n")
                                        .collect(java.util.stream.Collectors.joining("\n")))));
        var reindex = ManagementForms.command(
                "重建索引",
                model,
                () -> model.execute(
                        "重建索引",
                        sdk -> sdk.knowledge()
                                .reindexSource(
                                        source.id(), source.revision(), ManagementViewModel.key("knowledge-reindex")),
                        value -> {
                            showSource(value);
                            reload();
                        }));
        var delete = ManagementForms.command("删除", UiActionKind.DANGER, model, () -> {
            if (ManagementForms.confirm(this, model, "删除知识源", "删除索引与知识源，不修改用户原始文件。")) {
                model.execute(
                        "删除知识源",
                        sdk -> sdk.knowledge()
                                .deleteSource(
                                        source.id(), source.revision(), ManagementViewModel.key("knowledge-delete")),
                        ignored -> {
                            detail.setCenter(ManagementForms.emptyState("✓", "知识源已删除", "用户的原始文件没有被修改。"));
                            reload();
                        });
            }
        });
        detail.setCenter(ManagementForms.scroll(ManagementForms.form(
                ManagementForms.hint(source.displayName() + " · " + source.mediaType() + "\n状态："
                        + DesktopPresentationMapper.status(source.status()) + " · 修订 " + source.revision()),
                ManagementForms.section(
                        "索引摘要",
                        ManagementForms.hint(
                                sourceStats == null
                                        ? "统计尚未加载"
                                        : "Generation " + sourceStats.generation() + " · " + sourceStats.chunkCount()
                                                + " 个片段 · " + retrievalMode(sourceStats.retrievalMode()) + "\n索引时间："
                                                + DesktopPresentationMapper.instant(sourceStats.indexedAt(), "尚未建立")
                                                + (sourceStats.failureSummary() == null
                                                                || sourceStats
                                                                        .failureSummary()
                                                                        .isBlank()
                                                        ? ""
                                                        : "\n失败原因：" + sourceStats.failureSummary()))),
                ManagementForms.hint("重建先生成新版本，成功后切换；失败不会先删除当前可查询索引。"),
                ManagementForms.actions(read, history, reindex, delete),
                ManagementForms.section("正文预览", content))));
    }

    private void search() {
        var query = ManagementForms.text("", "输入检索问题");
        var hits = ManagementForms.<com.javaclaw.sdk.model.KnowledgeHitInfo>list(
                value -> value.displayName() + " · 修订 " + value.sourceRevision() + " · 分数 "
                        + String.format(Locale.ROOT, "%.4f", value.score()) + "\n" + value.content());
        var button = ManagementForms.command("检索", model, () -> {
            String input = query.getText();
            model.execute(
                    "检索知识",
                    sdk -> sdk.knowledge().search(workspace, input, 20),
                    values -> hits.getItems().setAll(values));
        });
        query.setOnAction(ignored -> button.fire());
        var page = ManagementForms.form(ManagementForms.field("检索测试", query), button, hits);
        VBox.setVgrow(hits, javafx.scene.layout.Priority.ALWAYS);
        detail.setCenter(page);
    }

    static String mediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (name.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (name.endsWith(".md")) {
            return "text/markdown";
        }
        if (name.endsWith(".csv")) {
            return "text/csv";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".xml")) {
            return "application/xml";
        }
        if (name.endsWith(".html")) {
            return "text/html";
        }
        return "text/plain";
    }

    private String sourceLabel(KnowledgeSourceInfo value) {
        KnowledgeSourceStatsInfo valueStats = stats.get(value.id());
        String index = valueStats == null
                ? "统计待加载"
                : valueStats.chunkCount() + " 片段 · " + retrievalMode(valueStats.retrievalMode()) + " · G"
                        + valueStats.generation();
        return value.displayName() + "\n" + DesktopPresentationMapper.status(value.status()) + " · " + index;
    }

    private static String retrievalMode(String mode) {
        return switch (mode) {
            case "VECTOR" -> "向量检索";
            case "KEYWORD" -> "关键词检索";
            default -> "索引不可用";
        };
    }

    private record KnowledgeCatalog(List<KnowledgeSourceInfo> sources, List<KnowledgeSourceStatsInfo> stats) {}
}
