package com.javaclaw.ui.javafx.site;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.ScrollPane;

import java.util.Objects;

/** 嵌入设置窗口的站点管理 FXML 面板及其 Controller 生命周期。 */
public final class SiteCredentialPanel implements AutoCloseable {

    private final ViewHandle<ScrollPane> handle;

    SiteCredentialPanel(ViewHandle<ScrollPane> handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    public ScrollPane root() { return handle.root(); }

    public void activate() { controller().activate(); }

    public void deactivate() { controller().deactivate(); }

    SiteCredentialController controller() {
        return handle.controller(SiteCredentialController.class);
    }

    @Override public void close() { handle.close(); }
}
