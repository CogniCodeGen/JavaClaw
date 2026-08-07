package com.javaclaw.chat.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 后台 Markdown 阶段生成的不可变表格数据。 */
record TableData(List<List<TableCellData>> rows, int columnCount) {

    TableData {
        Objects.requireNonNull(rows, "rows");
        if (columnCount < 0) throw new IllegalArgumentException("列数不能为负数");

        List<List<TableCellData>> copiedRows = new ArrayList<>(rows.size());
        for (List<TableCellData> row : rows) {
            List<TableCellData> copiedRow = List.copyOf(row);
            if (copiedRow.size() != columnCount) {
                throw new IllegalArgumentException(
                        "表格行列数不一致: expected=" + columnCount
                                + ", actual=" + copiedRow.size());
            }
            copiedRows.add(copiedRow);
        }
        rows = List.copyOf(copiedRows);
    }

    int cellCount() {
        return Math.multiplyExact(rows.size(), columnCount);
    }

    /** 电子表格友好的整表复制格式；单元格内部换行和制表符统一为空格。 */
    String toTsv() {
        return appendTsv(0, rows.size() - 1, 0, columnCount - 1);
    }

    /** 仅序列化矩形选区，左上角单元格映射为粘贴目标的起点。 */
    String toTsv(TableCellRange range) {
        Objects.requireNonNull(range, "range");
        if (range.lastRow() >= rows.size() || range.lastColumn() >= columnCount) {
            throw new IndexOutOfBoundsException(
                    "表格选区越界: " + range + ", rows=" + rows.size()
                            + ", columns=" + columnCount);
        }
        return appendTsv(
                range.firstRow(), range.lastRow(),
                range.firstColumn(), range.lastColumn());
    }

    private String appendTsv(
            int firstRow, int lastRow, int firstColumn, int lastColumn) {
        StringBuilder tsv = new StringBuilder();
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            if (rowIndex > firstRow) tsv.append('\n');
            List<TableCellData> row = rows.get(rowIndex);
            for (int column = firstColumn; column <= lastColumn; column++) {
                if (column > firstColumn) tsv.append('\t');
                tsv.append(normalizeClipboardText(row.get(column).text()));
            }
        }
        return tsv.toString();
    }

    private static String normalizeClipboardText(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\t' || current == '\n' || current == '\r') {
                pendingSpace = normalized.length() > 0
                        && normalized.charAt(normalized.length() - 1) != ' ';
            } else {
                if (pendingSpace && current != ' ') normalized.append(' ');
                normalized.append(current);
                pendingSpace = false;
            }
        }
        return normalized.toString();
    }
}

/** 单元格数据不持有 AST 或 JavaFX 对象，可安全传递到后台 renderer。 */
record TableCellData(String text, boolean header, TableCellAlignment alignment) {
    TableCellData {
        text = Objects.requireNonNullElse(text, "");
        Objects.requireNonNull(alignment, "alignment");
    }
}

enum TableCellAlignment {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right");

    private final String cssValue;

    TableCellAlignment(String cssValue) {
        this.cssValue = cssValue;
    }

    String cssValue() {
        return cssValue;
    }
}
