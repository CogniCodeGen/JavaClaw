package com.javaclaw.extension.spi;

import java.util.Optional;

/**
 * ViewSchema v2 表单字段的安全契约。
 *
 * <p>字段只能是平台拥有的标量输入或受限结构化列表，不允许扩展提供类名、脚本、表达式或自定义渲染器。
 */
public sealed interface ViewFormField permits ViewField, ViewStructuredListField {
    /**
     * 返回提交参数名。
     *
     * @return 提交参数名
     */
    String name();

    /**
     * 返回用户可见标签。
     *
     * @return 展示标签
     */
    String label();

    /**
     * 返回初值绑定。
     *
     * @return 数据源绑定
     */
    ViewBinding binding();

    /**
     * 返回字段级安全显示条件。
     *
     * @return 直接值比较条件；空表示始终显示
     */
    Optional<ViewCondition> visibleWhen();
}
