package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService.SearchResult;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.SearchHit;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** Search query result and presentation text; contains no service references. */
public final class KnowledgeSearchViewModel {
    private final ObservableList<SearchHit> hits = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper summary = new ReadOnlyStringWrapper(
            "输入查询并点击「检索」，查看会命中的知识片段");
    private String query = "";

    public ObservableList<SearchHit> hits() { return hits; }
    public ReadOnlyStringProperty summaryProperty() { return summary.getReadOnlyProperty(); }
    public String query() { return query; }

    public void apply(SearchResult result) {
        query = result.query();
        hits.setAll(result.hits());
        summary.set(result.hits().isEmpty()
                ? "没有命中片段，请尝试更宽泛的查询"
                : "命中 " + result.hits().size() + " 个片段 · 查询「" + query + "」");
    }

    public void failure(String message) {
        hits.clear();
        summary.set(message == null ? "检索失败" : message);
    }
}
