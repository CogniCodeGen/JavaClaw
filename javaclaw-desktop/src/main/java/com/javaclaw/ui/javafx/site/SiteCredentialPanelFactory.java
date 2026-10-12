package com.javaclaw.ui.javafx.site;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.ScrollPane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 为当前工作区创建独立、可关闭的站点管理面板。 */
public final class SiteCredentialPanelFactory {

    private static final URL VIEW = Objects.requireNonNull(
            SiteCredentialPanelFactory.class.getResource("/fxml/site/site-credential-panel.fxml"),
            "缺少 site-credential-panel.fxml");
    private final SpringFxmlLoader loader;

    public SiteCredentialPanelFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public SiteCredentialPanel create() {
        try {
            ViewHandle<ScrollPane> handle = loader.load(VIEW);
            return new SiteCredentialPanel(handle);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载站点管理面板失败", failure);
        }
    }
}
