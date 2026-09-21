package com.javaclaw.desktop.settings;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.javaclaw.builtin.contracts.SiteContracts;

/**
 * 网站布局调用的平台交互，不携带业务命令或权限。
 *
 * @param changeContext 检查未完成操作并确认放弃草稿
 * @param dirty 当前页面是否有草稿
 * @param pending 是否有不允许切换的操作
 * @param discard 丢弃草稿并刷新权威状态
 * @param dialogs 打开网站登记和共享凭据窗口
 * @param selected 接收本次权威网站选择
 * @param accountsExpanded 通知账号与登录分区是否展开
 */
record SiteSettingsActions(
        BooleanSupplier changeContext,
        BooleanSupplier dirty,
        BooleanSupplier pending,
        Runnable discard,
        Dialogs dialogs,
        Consumer<Optional<SiteContracts.Projection>> selected,
        Consumer<Boolean> accountsExpanded) {
    /** @param registerAddress 打开网站登记窗口 @param manageCredentials 打开共享凭据窗口 */
    record Dialogs(Runnable registerAddress, Runnable manageCredentials) {}
}
