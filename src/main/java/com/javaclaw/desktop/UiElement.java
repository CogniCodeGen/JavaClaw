package com.javaclaw.desktop;

import java.awt.Rectangle;

/**
 * 界面可交互元素 —— 统一的 {@code @ref} 元素模型（Tier 2 结构化定位）。
 *
 * <p>来源有二，对上层完全透明：</p>
 * <ul>
 *   <li><b>无障碍树</b>（有则优先）：从 OS 的 Accessibility API 拿到角色 + 包围盒，{@code confidence=1.0} 精确；</li>
 *   <li><b>视觉 Set-of-Mark</b>（兜底）：截图经 OCR / 轮廓检测得到候选框，{@code confidence<1.0} 为推断。</li>
 * </ul>
 *
 * <p>无论来源，模型始终只看到一个稳定的 {@link #ref}（如 {@code "e3"}）来指代元素，
 * 据此发起点击 / 输入；底层平台差异被适配器吸收。这与项目浏览器层的 {@code @ref} 思路一致。</p>
 *
 * <p>注：v1 的结构化定位尚未接入（{@code snapshot()} 默认返回空），上层据此自动降级到
 * "整窗截图 + 视觉模型"路线。本记录先行定义，标明架构演进方向。</p>
 *
 * @param ref        模型用于指代该元素的稳定引用，如 {@code "e7"}
 * @param role       元素角色：button / textField / link / ...（视觉路线为粗分类）
 * @param name       可见文本或无障碍名称
 * @param bounds     屏幕逻辑坐标系下的包围盒
 * @param confidence 置信度：{@code 1.0}=无障碍树精确，{@code <1.0}=视觉推断
 */
public record UiElement(String ref, String role, String name, Rectangle bounds, double confidence) {

    /** 该元素中心点（点击时使用），屏幕逻辑坐标。 */
    public java.awt.Point center() {
        return new java.awt.Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }
}
