package com.javaclaw.desktop.shell;

import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.ThreadState;

/**
 * 按控件依赖划分一次渲染需要更新的区域；所有标志只比较不可变事实，不读取控件状态。
 *
 * @param scope 工作区或会话选择变化
 * @param connection 连接事实变化
 * @param transcript 正文、阅读策略或其归属变化
 * @param inputs 等待输入的请求变化
 * @param labels 标题、运行状态、连接或错误提示变化
 * @param actions 发送与取消所依赖的状态变化
 */
record ShellRenderChanges(
        boolean scope, boolean connection, boolean transcript, boolean inputs, boolean labels, boolean actions) {
    static ShellRenderChanges between(DesktopState previous, DesktopState next) {
        if (previous == null) {
            return new ShellRenderChanges(true, true, true, true, true, true);
        }
        boolean connection = !previous.connection().equals(next.connection());
        boolean scope = selectionChanged(previous.threads(), next.threads());
        boolean turn = !previous.threads().activeTurn().equals(next.threads().activeTurn());
        boolean labels = connection
                || scope
                || turn
                || !previous.interaction()
                        .pendingApprovals()
                        .equals(next.interaction().pendingApprovals())
                || !previous.interaction()
                        .inputs()
                        .pendingRequests()
                        .equals(next.interaction().inputs().pendingRequests())
                || !previous.interaction().error().equals(next.interaction().error());
        boolean actions = connection
                || scope
                || turn
                || previous.interaction().busy() != next.interaction().busy();
        return new ShellRenderChanges(
                scope,
                connection,
                scope || connection || !previous.transcript().equals(next.transcript()),
                !previous.interaction().inputs().equals(next.interaction().inputs()),
                labels,
                actions);
    }

    private static boolean selectionChanged(ThreadState previous, ThreadState next) {
        return !previous.selectedWorkspace().equals(next.selectedWorkspace())
                || !previous.selectedThread().equals(next.selectedThread());
    }
}
