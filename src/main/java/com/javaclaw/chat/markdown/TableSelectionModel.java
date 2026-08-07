package com.javaclaw.chat.markdown;

/** 记录表格级矩形选区，不依赖 JavaFX，便于单独验证复制语义。 */
final class TableSelectionModel {

    private int anchorRow = -1;
    private int anchorColumn = -1;
    private TableCellRange selectedRange;

    void begin(int row, int column) {
        requireCell(row, column);
        anchorRow = row;
        anchorColumn = column;
        selectedRange = null;
    }

    /**
     * 扩展当前手势。只有真正跨过单元格后才进入表格选区模式，
     * 因此单个单元格内的拖动仍交给 RichTextFX 处理。
     *
     * @return 矩形选区是否发生了变化
     */
    boolean extendTo(int row, int column) {
        requireCell(row, column);
        if (anchorRow < 0) return false;
        if (selectedRange == null && row == anchorRow && column == anchorColumn) {
            return false;
        }
        TableCellRange nextRange = TableCellRange.between(
                anchorRow, anchorColumn, row, column);
        if (nextRange.equals(selectedRange)) return false;
        selectedRange = nextRange;
        return true;
    }

    void finishGesture() {
        anchorRow = -1;
        anchorColumn = -1;
    }

    void clear() {
        finishGesture();
        selectedRange = null;
    }

    boolean hasSelection() {
        return selectedRange != null;
    }

    boolean contains(int row, int column) {
        return selectedRange != null && selectedRange.contains(row, column);
    }

    TableCellRange selectedRange() {
        return selectedRange;
    }

    String selectedTsv(TableData data) {
        return selectedRange == null ? null : data.toTsv(selectedRange);
    }

    private static void requireCell(int row, int column) {
        if (row < 0 || column < 0) {
            throw new IllegalArgumentException(
                    "单元格坐标不能为负数: row=" + row + ", column=" + column);
        }
    }
}

/** 已归一化的闭区间矩形选区。 */
record TableCellRange(int firstRow, int lastRow, int firstColumn, int lastColumn) {

    TableCellRange {
        if (firstRow < 0 || firstColumn < 0
                || lastRow < firstRow || lastColumn < firstColumn) {
            throw new IllegalArgumentException(
                    "无效的表格选区: rows=" + firstRow + ".." + lastRow
                            + ", columns=" + firstColumn + ".." + lastColumn);
        }
    }

    static TableCellRange between(
            int anchorRow, int anchorColumn, int extentRow, int extentColumn) {
        return new TableCellRange(
                Math.min(anchorRow, extentRow),
                Math.max(anchorRow, extentRow),
                Math.min(anchorColumn, extentColumn),
                Math.max(anchorColumn, extentColumn));
    }

    boolean contains(int row, int column) {
        return row >= firstRow && row <= lastRow
                && column >= firstColumn && column <= lastColumn;
    }
}
