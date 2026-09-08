package com.javaclaw.desktop.settings;

/** 页面查询与本地编辑代次；包含表单和图谱窗口变化，自动刷新返回时不得覆盖较新的用户状态。 */
final class ViewPageLoadState {
    private long edits;
    private long renderedEdits;
    private Request pending;

    Request begin(long epoch, boolean automatic) {
        pending = new Request(epoch, edits, automatic);
        return pending;
    }

    void edited() {
        edits++;
    }

    boolean unchanged() {
        return edits == renderedEdits;
    }

    void applied() {
        renderedEdits = edits;
    }

    boolean pending() {
        return pending != null;
    }

    boolean complete(Request request, boolean dirty) {
        if (pending != request) {
            return false;
        }
        pending = null;
        return !request.automatic() || request.edits() == edits && !dirty;
    }

    void cancel() {
        pending = null;
    }

    /**
     * @param epoch 页面请求代次
     * @param edits 发起时的本地编辑代次
     * @param automatic 是否属于不阻塞用户输入的后台对账
     */
    record Request(long epoch, long edits, boolean automatic) {}
}
