package com.javaclaw.ui.javafx.knowledge;

import javafx.fxml.FXML;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tooltip;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 顶栏知识库菜单 Controller：按快照构建 FXML 菜单项并提交显式文档标识。 */
public final class KnowledgeMenuController implements AutoCloseable {

    private static final String ACTIVE_STYLE = "knowledge-active";

    @FXML private MenuButton root;
    @FXML private Tooltip knowledgeTooltip;

    private final KnowledgeMenuEntryFactory entries;
    private final KnowledgeMenuViewModel viewModel = new KnowledgeMenuViewModel();
    private final List<KnowledgeMenuEntryView> loadedEntries = new ArrayList<>();
    private final Map<String, CheckMenuItem> documentItems = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Supplier<KnowledgeMenuSnapshot> snapshots = KnowledgeMenuSnapshot::ragDisabled;
    private Consumer<Set<String>> selectionChanged = ignored -> { };
    private Runnable openManager = () -> { };

    @Autowired
    public KnowledgeMenuController(KnowledgeMenuEntryFactory entries) {
        this.entries = Objects.requireNonNull(entries, "entries");
    }

    @FXML
    private void initialize() {
        root.textProperty().bind(viewModel.buttonTextProperty());
        viewModel.activeProperty().addListener(
                (ignored, previous, active) -> applyActiveStyle(active));
    }

    public void configure(
            Supplier<KnowledgeMenuSnapshot> snapshots,
            Consumer<Set<String>> selectionChanged,
            Runnable openManager) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.selectionChanged = Objects.requireNonNull(selectionChanged, "selectionChanged");
        this.openManager = Objects.requireNonNull(openManager, "openManager");
    }

    @FXML
    private void showing() {
        if (closed.get()) return;
        knowledgeTooltip.hide();
        root.setTooltip(null);
        refresh();
    }

    @FXML
    private void hidden() {
        if (!closed.get()) root.setTooltip(knowledgeTooltip);
    }

    /** 按当前运行时快照重建菜单，原有动态 FXML 句柄会先被销毁。 */
    public void refresh() {
        if (closed.get()) return;
        clearEntries();
        KnowledgeMenuSnapshot snapshot = Objects.requireNonNull(
                snapshots.get(), "knowledge menu snapshot");
        if (!snapshot.ragEnabled()) {
            add(entries.action("RAG 未启用（请在设置 → 嵌入模型中开启）",
                    true, null, () -> { }));
            return;
        }
        if (snapshot.documentCount() == 0) {
            add(entries.action("知识库为空，请先导入文档", true, null, () -> { }));
            add(entries.separator());
            addManageEntry();
            return;
        }

        KnowledgeMenuEntryView selectAll = entries.check(
                "全选 / 取消全选",
                snapshot.selectedDocumentNames().size() == snapshot.documentCount(),
                null,
                this::selectAll);
        add(selectAll);
        appendSection("全局知识库", snapshot.globalDocuments(),
                snapshot.selectedDocumentNames());
        appendSection("工作区知识库", snapshot.workspaceDocuments(),
                snapshot.selectedDocumentNames());
        add(entries.separator());
        addManageEntry();
    }

    /** 清空动态项并恢复无选中文案；供工作区切换边界调用。 */
    public void reset() {
        if (closed.get()) return;
        clearEntries();
        viewModel.updateSelectedCount(0);
    }

    private void appendSection(
            String title,
            List<KnowledgeMenuSnapshot.Document> documents,
            Set<String> selected) {
        if (documents.isEmpty()) return;
        add(entries.separator());
        add(entries.header(title));
        for (KnowledgeMenuSnapshot.Document document : documents) {
            String text = document.name() + "  " + document.chunks() + " 片段";
            KnowledgeMenuEntryView entry = entries.check(
                    text,
                    selected.contains(document.name()),
                    "knowledge-doc-item",
                    ignored -> submitSelection());
            documentItems.put(document.name(), (CheckMenuItem) entry.root());
            add(entry);
        }
    }

    private void selectAll(boolean selected) {
        documentItems.values().forEach(item -> item.setSelected(selected));
        submitSelection();
    }

    private void submitSelection() {
        Set<String> selected = new LinkedHashSet<>();
        documentItems.forEach((name, item) -> {
            if (item.isSelected()) selected.add(name);
        });
        updateSelectAll(selected.size() == documentItems.size());
        selectionChanged.accept(Set.copyOf(selected));
        viewModel.updateSelectedCount(selected.size());
    }

    private void updateSelectAll(boolean selected) {
        if (!root.getItems().isEmpty() && root.getItems().getFirst() instanceof CheckMenuItem all) {
            all.setSelected(selected);
        }
    }

    private void addManageEntry() {
        add(entries.action("知识库中心...", false,
                "knowledge-manage-entry", openManager));
    }

    private void add(KnowledgeMenuEntryView entry) {
        loadedEntries.add(entry);
        root.getItems().add(entry.root());
    }

    private void applyActiveStyle(boolean active) {
        if (active && !root.getStyleClass().contains(ACTIVE_STYLE)) {
            root.getStyleClass().add(ACTIVE_STYLE);
        } else if (!active) {
            root.getStyleClass().remove(ACTIVE_STYLE);
        }
    }

    private void clearEntries() {
        root.getItems().clear();
        documentItems.clear();
        RuntimeException failure = null;
        for (int index = loadedEntries.size() - 1; index >= 0; index--) {
            try {
                loadedEntries.get(index).close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        loadedEntries.clear();
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        clearEntries();
        snapshots = KnowledgeMenuSnapshot::ragDisabled;
        selectionChanged = ignored -> { };
        openManager = () -> { };
    }

    boolean isClosed() { return closed.get(); }
}
