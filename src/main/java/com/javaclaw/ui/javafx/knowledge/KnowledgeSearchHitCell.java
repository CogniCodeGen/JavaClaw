package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService.Scope;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.SearchHit;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** Search result cell; FXML defines structure and Java only renders highlighted text runs. */
public final class KnowledgeSearchHitCell extends ListCell<SearchHit> {
    private final Supplier<String> query;
    @FXML private VBox root;
    @FXML private Label documentLabel;
    @FXML private Label scopeLabel;
    @FXML private Label scoreLabel;
    @FXML private TextFlow snippetFlow;
    @FXML private ProgressBar scoreBar;

    KnowledgeSearchHitCell(Supplier<String> query) {
        this.query = Objects.requireNonNull(query, "query");
        VBox loaded = EmbeddedFxmlLoader.load(
                KnowledgeSearchHitCell.class.getResource(
                        "/fxml/knowledge/knowledge-search-hit-cell.fxml"),
                this, VBox.class);
        if (loaded != root) throw new IllegalStateException("知识检索 Cell FXML 根节点不一致");
    }

    @Override
    protected void updateItem(SearchHit hit, boolean empty) {
        super.updateItem(hit, empty);
        setText(null);
        if (empty || hit == null) {
            setGraphic(null);
            return;
        }
        documentLabel.setText(hit.documentName());
        scopeLabel.setText(hit.scope() == Scope.GLOBAL ? "全局" : "工作区");
        scopeLabel.getStyleClass().removeAll("kc-scope-global", "kc-scope-workspace");
        scopeLabel.getStyleClass().add(hit.scope() == Scope.GLOBAL
                ? "kc-scope-global" : "kc-scope-workspace");
        scoreLabel.setText(String.format(Locale.ROOT, "%.0f%%", hit.score() * 100));
        scoreBar.setProgress(Math.max(0, Math.min(1, hit.score())));
        renderSnippet(hit.content(), query.get());
        setGraphic(root);
    }

    private void renderSnippet(String content, String queryText) {
        String text = content == null ? "" : content;
        snippetFlow.getChildren().clear();
        List<int[]> ranges = ranges(text, queryText);
        int position = 0;
        for (int[] range : ranges) {
            if (range[0] > position) snippetFlow.getChildren().add(plain(
                    text.substring(position, range[0])));
            snippetFlow.getChildren().add(highlight(text.substring(range[0], range[1])));
            position = range[1];
        }
        if (position < text.length()) snippetFlow.getChildren().add(plain(text.substring(position)));
        if (snippetFlow.getChildren().isEmpty()) snippetFlow.getChildren().add(plain(text));
    }

    private static List<int[]> ranges(String text, String query) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<int[]> found = new ArrayList<>();
        if (query != null) {
            for (String raw : query.strip().split("\\s+")) {
                if (raw.length() < 2) continue;
                String term = raw.toLowerCase(Locale.ROOT);
                for (int from = 0, index; (index = lower.indexOf(term, from)) >= 0;
                     from = index + term.length()) {
                    found.add(new int[]{index, index + term.length()});
                }
            }
        }
        found.sort((left, right) -> Integer.compare(left[0], right[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : found) {
            if (!merged.isEmpty() && range[0] <= merged.getLast()[1]) {
                merged.getLast()[1] = Math.max(merged.getLast()[1], range[1]);
            } else {
                merged.add(range);
            }
        }
        return merged;
    }

    private static Text plain(String value) {
        Text text = new Text(value);
        text.getStyleClass().add("kc-snippet-text");
        return text;
    }

    private static Label highlight(String value) {
        Label label = new Label(value);
        label.getStyleClass().add("kc-snippet-hit");
        return label;
    }
}
