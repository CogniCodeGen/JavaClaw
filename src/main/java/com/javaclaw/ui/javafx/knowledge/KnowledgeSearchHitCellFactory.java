package com.javaclaw.ui.javafx.knowledge;

import java.util.function.Supplier;

/** Creates search-hit cells whose highlighter reads the current query. */
public final class KnowledgeSearchHitCellFactory {
    KnowledgeSearchHitCell create(Supplier<String> query) {
        return new KnowledgeSearchHitCell(query);
    }
}
