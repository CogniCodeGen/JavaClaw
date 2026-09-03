package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.Workspace;

/**
 * 全局可恢复 Extension Job 管理页的不可变状态。
 *
 * @param workspaces Workspace 过滤目录
 * @param filter 当前过滤条件
 * @param page 当前 keyset 页面
 * @param detail 当前权威详情
 * @param feedback 异步反馈和请求 epoch
 */
public record AutomationJobSettingsState(
        List<Workspace> workspaces,
        AutomationJobFilter filter,
        AutomationJobPage page,
        AutomationJobDetail detail,
        AutomationJobFeedback feedback) {
    /** 取得集合所有权并校验详情属于当前选择。 */
    public AutomationJobSettingsState {
        workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
        filter = Objects.requireNonNull(filter, "filter");
        AutomationJobPage checkedPage = Objects.requireNonNull(page, "page");
        page = checkedPage;
        detail = Objects.requireNonNull(detail, "detail");
        feedback = Objects.requireNonNull(feedback, "feedback");
        detail.value().ifPresent(value -> {
            String selectedId = checkedPage.selected().orElseThrow().id();
            if (!selectedId.equals(value.job().id())) {
                throw new IllegalArgumentException("后台任务 detail does not match selection");
            }
        });
    }

    /** @return 空初始状态 */
    public static AutomationJobSettingsState initial() {
        return new AutomationJobSettingsState(
                List.of(),
                AutomationJobFilter.all(),
                AutomationJobPage.first(),
                AutomationJobDetail.empty(),
                AutomationJobFeedback.initial());
    }
}
