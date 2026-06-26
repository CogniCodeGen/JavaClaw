package com.javaclaw.desktop;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Set-of-Mark 标注渲染器 —— 在截图上给每个可交互元素叠加编号方框与标签（纯 Java 2D，零依赖、跨平台）。
 *
 * <p>这是 Tier 2 结构化定位的"视觉表达"层：把 {@link UiElement} 的包围盒画成带 {@code @ref} 编号的方框，
 * 让模型<b>选编号</b>而不是猜像素坐标——这是把模型接地（grounding）精度从"经常点偏"提到"基本不偏"的关键手段，
 * 与项目浏览器层的 {@code @ref} 思路一脉相承。</p>
 *
 * <p>本类只负责画图，不关心元素从哪来（无障碍树或视觉检测皆可），保持单一职责。</p>
 */
public final class SetOfMarkRenderer {

    /** 一个待标注的方框：引用编号 + 图像本地坐标系下的包围盒。 */
    public record Mark(String ref, Rectangle bounds) {
    }

    /** 编号方框循环使用的高对比配色，确保相邻元素颜色不同、易于肉眼/模型区分。 */
    private static final Color[] PALETTE = {
            new Color(0xE6194B), new Color(0x3CB44B), new Color(0x4363D8),
            new Color(0xF58231), new Color(0x911EB4), new Color(0x008080),
            new Color(0x9A6324), new Color(0x800000), new Color(0x808000),
            new Color(0x000075)
    };

    private SetOfMarkRenderer() {
    }

    /**
     * 在底图副本上绘制所有标注，返回新图（不修改原图）。
     *
     * @param base  原始截图
     * @param marks 待标注元素（包围盒须为相对底图左上角的本地坐标）
     * @return 叠加了编号方框的新图
     */
    public static BufferedImage render(BufferedImage base, List<Mark> marks) {
        // 复制底图，避免污染调用方持有的原始截图
        BufferedImage out = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(base, 0, 0, null);

        Font font = new Font("SansSerif", Font.BOLD, 13);
        g.setFont(font);

        for (int i = 0; i < marks.size(); i++) {
            Mark mark = marks.get(i);
            Rectangle b = mark.bounds();
            Color color = PALETTE[i % PALETTE.length];

            // 半透明高亮填充 + 实线描边，突出元素轮廓而不完全遮挡内容
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.12f));
            g.setColor(color);
            g.fillRect(b.x, b.y, b.width, b.height);
            g.setComposite(AlphaComposite.SrcOver);
            g.setStroke(new BasicStroke(2f));
            g.setColor(color);
            g.drawRect(b.x, b.y, b.width, b.height);

            drawTag(g, mark.ref(), color, b);
        }
        g.dispose();
        return out;
    }

    /** 在方框左上角绘制一个填充的编号标签（彩底白字），保证编号清晰可读。 */
    private static void drawTag(Graphics2D g, String ref, Color color, Rectangle box) {
        int padding = 3;
        int textW = g.getFontMetrics().stringWidth(ref);
        int textH = g.getFontMetrics().getHeight();
        int tagW = textW + padding * 2;
        int tagH = textH;
        // 标签默认贴在方框左上角外侧；贴边时回收到框内，避免画出图像边界
        int tagX = box.x;
        int tagY = box.y - tagH;
        if (tagY < 0) {
            tagY = box.y;
        }
        g.setColor(color);
        g.fillRect(tagX, tagY, tagW, tagH);
        g.setColor(Color.WHITE);
        g.drawString(ref, tagX + padding, tagY + g.getFontMetrics().getAscent());
    }
}
