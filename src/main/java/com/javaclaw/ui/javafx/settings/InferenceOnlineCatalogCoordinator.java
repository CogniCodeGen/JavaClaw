package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.HuggingFaceModelCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns the online catalog request lifecycle so detail/download actions cannot cancel a search. */
final class InferenceOnlineCatalogCoordinator implements AutoCloseable {
    private final InferenceManagementApplicationService useCases;
    private final FxDispatcher fx;
    private final TextField searchField;
    private final ListView<InferenceSettingsChoice<HuggingFaceModelCatalogPort.ModelSummary>> list;
    private final Label stateLabel;
    private final Label updatedLabel;
    private final Label pageLabel;
    private final ProgressBar progressBar;
    private final Button retryButton;
    private final Button nextButton;
    private final Consumer<Integer> countChanged;
    private final VBox root;
    private final SplitPane onlineSplit;
    private final SplitPane localSplit;
    private final ChangeListener<Number> widthListener =
            (ignored, previous, value) -> responsive(value.doubleValue());
    private final UiAsyncAction<HuggingFaceModelCatalogPort.SearchPage> action;
    private final PauseTransition debounce = new PauseTransition(Duration.millis(350));
    private final AtomicLong generation = new AtomicLong();
    private String currentCursor = "";
    private String nextCursor = "";
    private String activeQuery = "";
    private int pageNumber = 1;
    private HuggingFaceModelCatalogPort.SearchState latestState =
            HuggingFaceModelCatalogPort.SearchState.LOADING;
    private boolean showingCached;

    InferenceOnlineCatalogCoordinator(
            InferenceManagementApplicationService useCases,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            TextField searchField,
            ListView<InferenceSettingsChoice<HuggingFaceModelCatalogPort.ModelSummary>> list,
            Label stateLabel,
            Label updatedLabel,
            Label pageLabel,
            ProgressBar progressBar,
            Button retryButton,
            Button nextButton,
            VBox root,
            SplitPane onlineSplit,
            SplitPane localSplit,
            Consumer<Integer> countChanged) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.searchField = Objects.requireNonNull(searchField, "searchField");
        this.list = Objects.requireNonNull(list, "list");
        this.stateLabel = Objects.requireNonNull(stateLabel, "stateLabel");
        this.updatedLabel = Objects.requireNonNull(updatedLabel, "updatedLabel");
        this.pageLabel = Objects.requireNonNull(pageLabel, "pageLabel");
        this.progressBar = Objects.requireNonNull(progressBar, "progressBar");
        this.retryButton = Objects.requireNonNull(retryButton, "retryButton");
        this.nextButton = Objects.requireNonNull(nextButton, "nextButton");
        this.root = Objects.requireNonNull(root, "root");
        this.onlineSplit = Objects.requireNonNull(onlineSplit, "onlineSplit");
        this.localSplit = Objects.requireNonNull(localSplit, "localSplit");
        this.countChanged = Objects.requireNonNull(countChanged, "countChanged");
        action = new UiAsyncAction<>(Objects.requireNonNull(tasks, "tasks"), fx);
        searchField.textProperty().addListener((ignored, previous, value) -> searchDebounced());
        root.widthProperty().addListener(widthListener);
    }

    void searchFirstPage() { search("", 1); }

    void refresh() { search(currentCursor, pageNumber); }

    void next() {
        if (!nextCursor.isBlank()) search(nextCursor, pageNumber + 1);
    }

    void retry() { search(currentCursor, pageNumber); }

    void searchDebounced() {
        debounce.stop();
        debounce.setOnFinished(ignored -> search("", 1));
        debounce.playFromStart();
    }

    private void search(String cursor, int requestedPage) {
        long run = generation.incrementAndGet();
        activeQuery = normalizedQuery();
        currentCursor = cursor == null ? "" : cursor;
        pageNumber = requestedPage;
        nextCursor = "";
        nextButton.setDisable(true);
        renderInitial();
        HuggingFaceModelCatalogPort.SearchRequest request =
                new HuggingFaceModelCatalogPort.SearchRequest(activeQuery, currentCursor, 20);
        action.execute(TaskSpec.io("搜索 Deliverance 在线模型"), context ->
                        useCases.searchOnlineModels(request,
                                value -> fx.dispatch(() -> applyProgress(run, value)),
                                context.cancellation()::isCancellationRequested),
                page -> applyPage(run, page), failure -> applyFailure(run, failure));
    }

    private void renderInitial() {
        latestState = HuggingFaceModelCatalogPort.SearchState.LOADING;
        showingCached = false;
        stateLabel.setText("正在连接 Hugging Face 并读取候选仓库…");
        updatedLabel.setText("匿名访问 · 严格兼容验证");
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        show(progressBar, true);
        show(retryButton, false);
        pageLabel.setText("第 " + pageNumber + " 页 · 正在验证");
    }

    private void applyProgress(long run, HuggingFaceModelCatalogPort.SearchProgress value) {
        if (run != generation.get()) return;
        latestState = value.state();
        if (value.state() == HuggingFaceModelCatalogPort.SearchState.STALE) showingCached = true;
        boolean preserveCache = showingCached && value.models().isEmpty()
                && (value.state() == HuggingFaceModelCatalogPort.SearchState.LOADING
                || value.state() == HuggingFaceModelCatalogPort.SearchState.PARTIAL);
        if (!preserveCache) replaceModels(value.models());
        if (!value.models().isEmpty() || value.complete()) showingCached = false;
        int visibleCount = preserveCache ? list.getItems().size() : value.models().size();
        int total = value.candidateCount();
        int scanned = value.scannedCount();
        progressBar.setProgress(total == 0 ? ProgressBar.INDETERMINATE_PROGRESS
                : Math.min(1.0, (double) scanned / total));
        boolean working = value.state() == HuggingFaceModelCatalogPort.SearchState.LOADING
                || value.state() == HuggingFaceModelCatalogPort.SearchState.PARTIAL
                || value.state() == HuggingFaceModelCatalogPort.SearchState.STALE;
        show(progressBar, working);
        show(retryButton, value.state() == HuggingFaceModelCatalogPort.SearchState.ERROR);
        stateLabel.setText(status(value, visibleCount));
        updatedLabel.setText(updated(value));
        pageLabel.setText("第 " + pageNumber + " 页 · " + visibleCount
                + " 个兼容模型" + (working ? " · 验证中" : ""));
    }

    private void applyPage(long run, HuggingFaceModelCatalogPort.SearchPage page) {
        if (run != generation.get()) return;
        nextCursor = page.nextCursor();
        nextButton.setDisable(nextCursor.isBlank());
        if (latestState != HuggingFaceModelCatalogPort.SearchState.ERROR) replaceModels(page.models());
        pageLabel.setText("第 " + pageNumber + " 页 · " + page.models().size()
                + " 个兼容模型" + (nextCursor.isBlank() ? " · 已到末页" : ""));
    }

    private void applyFailure(long run, Throwable failure) {
        if (run != generation.get()) return;
        latestState = HuggingFaceModelCatalogPort.SearchState.ERROR;
        show(progressBar, false);
        show(retryButton, true);
        stateLabel.setText("在线目录刷新失败：" + SettingsFieldSupport.failureMessage(failure));
        updatedLabel.setText(list.getItems().isEmpty() ? "没有可用缓存" : "继续显示上次验证结果");
    }

    private void replaceModels(java.util.List<HuggingFaceModelCatalogPort.ModelSummary> models) {
        String selected = selectedRepository();
        var choices = models.stream().sorted(Comparator.comparing(
                        HuggingFaceModelCatalogPort.ModelSummary::lastModified).reversed())
                .map(model -> new InferenceSettingsChoice<>(
                        InferenceModelPresentation.onlineChoice(model), model)).toList();
        list.getItems().setAll(choices);
        choices.stream().filter(choice -> choice.value().repository().equals(selected)).findFirst()
                .ifPresent(list.getSelectionModel()::select);
        countChanged.accept(choices.size());
    }

    private String selectedRepository() {
        var selected = list.getSelectionModel().getSelectedItem();
        return selected == null ? "" : selected.value().repository();
    }

    private static String status(
            HuggingFaceModelCatalogPort.SearchProgress value, int found) {
        return switch (value.state()) {
            case LOADING, PARTIAL -> "正在验证 Hugging Face 仓库 " + value.scannedCount()
                    + " / " + value.candidateCount() + " · 已找到 " + found + " 个兼容模型";
            case STALE -> "正在后台刷新 · 当前显示上次验证的 " + found + " 个兼容模型";
            case READY -> found > 0
                    ? "验证完成：扫描 " + value.candidateCount() + " 个仓库，找到 " + found + " 个兼容模型"
                    : emptyStatus(value.candidateCount(), value.rejectionReasons());
            case ERROR -> "刷新未完整完成：" + value.error()
                    + (found > 0 ? " · 保留 " + found + " 个已验证结果" : "");
        };
    }

    private static String emptyStatus(int total, Map<String, Integer> reasons) {
        if (reasons.isEmpty()) return "扫描完成：" + total + " 个候选仓库中没有兼容模型";
        String reason = reasons.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(entry -> entry.getKey() + " " + entry.getValue() + " 个").orElse("");
        return "扫描完成：" + total + " 个候选仓库均被兼容性规则淘汰 · " + reason;
    }

    private static String updated(HuggingFaceModelCatalogPort.SearchProgress value) {
        Instant time = value.cachedAt();
        return time.equals(Instant.EPOCH) ? "匿名访问 · 严格兼容验证"
                : "最近验证 " + InferenceModelPresentation.instant(time);
    }

    private String normalizedQuery() {
        String value = searchField.getText();
        return value == null ? "" : value.strip();
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void responsive(double width) {
        boolean narrow = width > 0 && width < 900;
        for (SplitPane split : java.util.List.of(onlineSplit, localSplit)) {
            split.setOrientation(narrow ? Orientation.VERTICAL : Orientation.HORIZONTAL);
            split.setPrefHeight(narrow ? 760 : 520);
            for (Node item : split.getItems()) {
                if (item instanceof Region region) region.setMinWidth(narrow ? 0 : 270);
            }
        }
    }

    void cancel() {
        generation.incrementAndGet();
        debounce.stop();
        action.cancel();
    }

    @Override public void close() {
        cancel();
        root.widthProperty().removeListener(widthListener);
        action.close();
    }
}
