package com.javaclaw.ui.javafx.theme;

import javafx.beans.property.ReadOnlyIntegerProperty;

/**
 * 字体系统提供给只读渲染组件的稳定视图。
 *
 * <p>属性和值只能在 JavaFX Application Thread 读取。实现负责在外观变化后递增
 * {@link #revisionProperty()}，调用方不得通过本接口修改或持久化字体设置。</p>
 */
public interface FontProfile {

    double chatFontPx();

    double chatLineHeight();

    String uiStack();

    String monoStack();

    ReadOnlyIntegerProperty revisionProperty();
}
