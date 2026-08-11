package com.javaclaw.ui.javafx.site;

import com.javaclaw.application.site.SiteCredentialApplicationService.Credential;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/** 高频列表刷新时为每条站点凭据装载一次可关闭的 FXML 卡片。 */
public final class SiteCredentialCardFactory {

    private static final URL VIEW = Objects.requireNonNull(
            SiteCredentialCardFactory.class.getResource("/fxml/site/site-credential-card.fxml"),
            "缺少 site-credential-card.fxml");
    private final SpringFxmlLoader loader;

    public SiteCredentialCardFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public Card create(Credential credential, Consumer<String> edit,
                       Consumer<String> reset, Consumer<String> delete) {
        try {
            ViewHandle<VBox> handle = loader.load(VIEW);
            handle.controller(SiteCredentialCardController.class)
                    .configure(credential, edit, reset, delete);
            return new Card(handle);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载站点凭据卡片失败", failure);
        }
    }

    public static final class Card implements AutoCloseable {
        private final ViewHandle<VBox> handle;
        private Card(ViewHandle<VBox> handle) { this.handle = handle; }
        public VBox root() { return handle.root(); }
        @Override public void close() { handle.close(); }
    }
}
