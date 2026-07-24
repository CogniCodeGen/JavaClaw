package com.javaclaw.mode;

import com.javaclaw.api.conversation.ActionMode;
import com.javaclaw.api.conversation.Capabilities;
import com.javaclaw.api.conversation.Placement;

/** 打开工作流中心的侧边栏动作。 */
public final class WorkflowCenterMode implements ActionMode {
    private final Runnable opener;
    private final Runnable closer;
    public WorkflowCenterMode(Runnable opener) { this(opener, () -> {}); }
    public WorkflowCenterMode(Runnable opener, Runnable closer) {
        this.opener = opener;
        this.closer = closer;
    }
    @Override public String id() { return "workflow-center"; }
    @Override public String displayName() { return "⎇ 工作流中心"; }
    @Override public String tooltip() { return "设计、发布和调试本地 Java 工作流"; }
    @Override public Placement placement() { return Placement.SIDEBAR_ACTION; }
    @Override public Capabilities capabilities() { return Capabilities.minimal(); }
    @Override public void open() { if (opener != null) opener.run(); }
    @Override public void shutdown() { if (closer != null) closer.run(); }
}
