package com.javaclaw.chat.markdown;

import jfx.incubator.scene.control.richtext.StyleResolver;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 聊天气泡文档模型：自持 {@link RichParagraph} 列表的只读模型。
 *
 * <p>{@code isWritable()=false} 只是禁掉用户编辑；程序侧通过
 * {@link #replaceFrom(int, List)} 变更段落列表并手动 {@code fireChangeEvent}
 * 驱动视图增量刷新——这是流式渲染「稳定前缀不动、只重建尾部」的基础。</p>
 *
 * <p>注意：不能改用可写 {@link jfx.incubator.scene.control.richtext.model.RichTextModel}——
 * 其 {@code insertParagraph}（嵌入 Region 段落，表格/代码块依赖）在 JavaFX 25
 * 直接抛 UnsupportedOperationException（POC 已证伪，见 devtools.RichTextAreaPoc）。</p>
 */
public final class ChatDocModel extends StyledTextModel {

    private final List<RichParagraph> paragraphs = new ArrayList<>();

    public ChatDocModel() {
        paragraphs.add(RichParagraph.builder().build()); // 文档至少一段（空段）
    }

    @Override public boolean isWritable() { return false; }
    @Override public int size() { return paragraphs.size(); }
    @Override public RichParagraph getParagraph(int index) { return paragraphs.get(index); }
    @Override public String getPlainText(int index) { return paragraphs.get(index).getPlainText(); }
    @Override public int getParagraphLength(int index) { return getPlainText(index).length(); }

    @Override
    public StyleAttributeMap getStyleAttributeMap(StyleResolver resolver, TextPos pos) {
        return StyleAttributeMap.EMPTY;
    }

    // 只读模型不走 replace() 编辑协议，以下协定方法不会被视图调用
    @Override protected int insertTextSegment(int i, int o, String t, StyleAttributeMap a) { throw new UnsupportedOperationException(); }
    @Override protected void insertLineBreak(int i, int o) { throw new UnsupportedOperationException(); }
    @Override protected void insertParagraph(int i, Supplier<javafx.scene.layout.Region> f) { throw new UnsupportedOperationException(); }
    @Override protected void removeRange(TextPos s, TextPos e) { throw new UnsupportedOperationException(); }
    @Override protected void setParagraphStyle(int i, StyleAttributeMap a) { throw new UnsupportedOperationException(); }
    @Override protected void applyStyle(int s, int o1, int o2, StyleAttributeMap a, boolean m) { throw new UnsupportedOperationException(); }

    /**
     * 从 fromIndex 起替换到文档末尾的所有段落并通知视图。
     *
     * @param fromIndex 首个被替换的段落下标（== 当前段落数时为纯追加）
     * @param newTail   新的尾部段落
     */
    public void replaceFrom(int fromIndex, List<RichParagraph> newTail) {
        int from = Math.max(0, Math.min(fromIndex, paragraphs.size()));
        TextPos start = from >= paragraphs.size()
                ? getDocumentEnd()
                : TextPos.ofLeading(from, 0);
        TextPos oldEnd = getDocumentEnd();
        while (paragraphs.size() > from) {
            paragraphs.remove(paragraphs.size() - 1);
        }
        paragraphs.addAll(newTail);
        if (paragraphs.isEmpty()) {
            paragraphs.add(RichParagraph.builder().build());
        }
        int lastLen = getParagraphLength(paragraphs.size() - 1);
        fireChangeEvent(start, oldEnd, 0, Math.max(0, paragraphs.size() - 1 - from), lastLen);
    }

    /** 整文档替换（全量重渲染兜底路径） */
    public void replaceAll(List<RichParagraph> ps) {
        replaceFrom(0, ps);
    }
}
