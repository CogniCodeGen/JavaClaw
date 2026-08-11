package com.javaclaw.ui.javafx.diagnostics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsViewModelTest {

    @Test
    void exposesStableDefaultsAndResultSummary() {
        DiagnosticsViewModel model = new DiagnosticsViewModel();

        assertEquals("最近 24 小时", model.selectedRangeProperty().get().label());
        assertEquals("全部", model.selectedEventProperty().get());
        assertEquals("请点击「查询」加载事件", model.summaryProperty().get());

        model.showResults(List.of("one", "two"));

        assertEquals(List.of("one", "two"), model.results());
        assertEquals("共 2 条事件（上限 2000）", model.summaryProperty().get());
        assertTrue(model.errorProperty().get().isEmpty());
    }

    @Test
    void clearsStaleQueryResultsAndMapsFailures() {
        DiagnosticsViewModel model = new DiagnosticsViewModel();
        model.showResults(List.of("stale"));

        model.showQueryFailure(new IOException("trace unreadable"));

        assertTrue(model.results().isEmpty());
        assertEquals("trace unreadable", model.errorProperty().get());
        assertEquals("查询失败：trace unreadable", model.summaryProperty().get());
    }

    @Test
    void formatsExportReceiptInKilobytes() {
        DiagnosticsViewModel model = new DiagnosticsViewModel();

        model.showExportSuccess("/tmp/diagnostics.zip", 3072);

        assertEquals("诊断包已导出 · /tmp/diagnostics.zip （3 KB）",
                model.summaryProperty().get());
    }
}
