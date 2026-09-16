package com.javaclaw.desktop.view;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AttachmentRef;

/** ViewSchema v2 控件向平台协调器提交的有限交互。 */
public interface ViewInteractionHandler {
    /**
     * 更新表单的未保存状态，供平台统一执行离页保护。
     *
     * @param formId 页面内表单标识
     * @param dirty 是否包含未保存修改
     */
    void dirty(String formId, boolean dirty);

    /**
     * 执行已经完成类型校验的 command。
     *
     * @param invocation 调用
     */
    void execute(ViewCommandInvocation invocation);

    /**
     * 提交已校验的表单命令；表单来源仅用于平台草稿保护，不进入业务参数或 wire schema。
     *
     * @param formId 声明该提交操作的 schema 表单标识
     * @param invocation 已完成权威绑定和字段校验的调用
     */
    default void executeForm(String formId, ViewCommandInvocation invocation) {
        execute(invocation);
    }

    /**
     * 请求平台执行页面局部图谱浏览；该动作不修改扩展业务状态。
     *
     * @param action 受限筛选或邻接展开
     */
    default void graph(ViewGraphAction action) {
        // 没有 GraphBrowsing 的旧页面无需实现局部浏览。
    }

    /** 请求重新读取全部权威数据。 */
    void reload();

    /**
     * 请求翻页。
     *
     * @param sourceId 数据源标识
     * @param direction 方向
     */
    void page(String sourceId, ViewPageDirection direction);

    /**
     * 更新稳定行选择。
     *
     * @param sourceId 数据源标识
     * @param selectedKey 选择键；取消选择为空
     */
    void select(String sourceId, Optional<String> selectedKey);

    /**
     * 在控件改变命令绑定前申请行选择；拒绝时控件恢复原选择。
     *
     * @param sourceId 数据源标识
     * @param selectedKey 目标选择键；取消选择为空
     * @return 是否接受本次选择；默认保持已有交互实现的行为
     */
    default boolean selectRequested(String sourceId, Optional<String> selectedKey) {
        select(sourceId, selectedKey);
        return true;
    }

    /**
     * 通过 Java SDK 上传本地文件；返回 Future 必须在 JavaFX 调度器上完成。
     *
     * @param request 本地文件、Schema 上限和取消源
     * @return 无宿主路径的 Attachment 引用
     */
    CompletionStage<AttachmentRef> upload(ViewAttachmentUploadRequest request);
}
