package com.javaclaw.chat.markdown;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableDataTest {

    private static final Parser TABLE_PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    @Test
    void extractsAlignmentAndPadsMissingCells() {
        TableData data = tableData("""
                | 左对齐 | 居中 | 右对齐 |
                | :--- | :---: | ---: |
                | A | B |
                """);

        assertEquals(3, data.columnCount());
        assertEquals(6, data.cellCount());
        assertEquals(TableCellAlignment.LEFT, data.rows().get(0).get(0).alignment());
        assertEquals(TableCellAlignment.CENTER, data.rows().get(0).get(1).alignment());
        assertEquals(TableCellAlignment.RIGHT, data.rows().get(0).get(2).alignment());
        assertTrue(data.rows().get(0).stream().allMatch(TableCellData::header));
        assertTrue(data.rows().get(1).get(2).text().isEmpty());
        assertFalse(data.rows().get(1).get(2).header());
        assertEquals(TableCellAlignment.RIGHT, data.rows().get(1).get(2).alignment());
    }

    @Test
    void serializesCompleteTableAsSpreadsheetFriendlyTsv() {
        TableData data = new TableData(List.of(
                List.of(
                        cell("名称\t说明", true),
                        cell("建议\n操作", true),
                        cell("", true)),
                List.of(
                        cell("工具", false),
                        cell("保持  原有空格", false),
                        cell("完成", false))), 3);

        assertEquals("名称 说明\t建议 操作\t\n工具\t保持  原有空格\t完成", data.toTsv());
    }

    @Test
    void serializesEveryRowAndColumnInRectangularSelection() {
        TableData data = new TableData(List.of(
                List.of(cell("H0", true), cell("H1", true), cell("H2", true)),
                List.of(cell("A0", false), cell("A1", false), cell("", false)),
                List.of(cell("B0", false), cell("B1", false), cell("B2  ", false))), 3);

        TableCellRange backwards = TableCellRange.between(2, 2, 0, 1);

        assertEquals("H1\tH2\nA1\t\nB1\tB2  ", data.toTsv(backwards));
        assertFalse(data.toTsv(backwards).startsWith("\t"),
                "复制应从选区最左列开始，不带未选列的前导制表符");
    }

    @Test
    void tableSelectionStartsOnlyAfterCrossingCellBoundary() {
        TableSelectionModel selection = new TableSelectionModel();
        selection.begin(1, 1);

        assertFalse(selection.extendTo(1, 1));
        assertFalse(selection.hasSelection());

        assertTrue(selection.extendTo(2, 2));
        assertEquals(new TableCellRange(1, 2, 1, 2), selection.selectedRange());
        assertTrue(selection.contains(1, 1));
        assertTrue(selection.contains(2, 2));
        assertFalse(selection.contains(0, 1));

        selection.extendTo(1, 1);
        selection.finishGesture();
        assertEquals(new TableCellRange(1, 1, 1, 1), selection.selectedRange());

        selection.clear();
        assertFalse(selection.hasSelection());
    }

    @Test
    void deeplyCopiesRowsAndRejectsRaggedInput() {
        List<TableCellData> mutableRow = new ArrayList<>(List.of(cell("A", false)));
        List<List<TableCellData>> mutableRows = new ArrayList<>(List.of(mutableRow));
        TableData data = new TableData(mutableRows, 1);

        mutableRow.set(0, cell("changed", false));
        mutableRows.clear();
        assertEquals("A", data.rows().getFirst().getFirst().text());
        assertThrows(IllegalArgumentException.class,
                () -> new TableData(List.of(List.of(cell("A", false))), 2));
    }

    private static TableCellData cell(String text, boolean header) {
        return new TableCellData(text, header, TableCellAlignment.LEFT);
    }

    private static TableData tableData(String markdown) {
        Node document = TABLE_PARSER.parse(markdown);
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof TableBlock table) {
                return MarkdownParagraphRenderer.collectTableData(table);
            }
        }
        throw new AssertionError("Markdown 未解析出表格");
    }
}
