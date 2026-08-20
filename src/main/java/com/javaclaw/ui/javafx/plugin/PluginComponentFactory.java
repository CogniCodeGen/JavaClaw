package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.plugin.PluginManagementApplicationService.ConfigField;
import com.javaclaw.application.plugin.PluginManagementApplicationService.NamedItem;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Permission;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Plugin;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 加载插件中心可复用 FXML 片段，并把 Controller 生命周期交给返回视图。 */
public final class PluginComponentFactory {

    private static final URL CARD = resource("plugin-card.fxml");
    private static final URL PERMISSION = resource("plugin-permission-row.fxml");
    private static final URL NAMED_ITEM = resource("plugin-named-item.fxml");
    private static final URL CONFIG_FIELD = resource("plugin-config-field.fxml");

    private final SpringFxmlLoader loader;

    public PluginComponentFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    PluginChildView<VBox> card(
            Plugin plugin,
            Consumer<String> onDetails,
            Consumer<String> onApproval,
            BiConsumer<String, Boolean> onToggle) {
        ViewHandle<VBox> handle = load(CARD, "插件卡片");
        handle.controller(PluginCardController.class).configure(
                plugin, onDetails, onApproval, onToggle);
        return new PluginChildView<>(handle);
    }

    PluginChildView<HBox> permission(Permission permission) {
        ViewHandle<HBox> handle = load(PERMISSION, "插件权限行");
        handle.controller(PluginPermissionRowController.class).configure(permission);
        return new PluginChildView<>(handle);
    }

    PluginChildView<VBox> namedItem(NamedItem item) {
        ViewHandle<VBox> handle = load(NAMED_ITEM, "插件暴露条目");
        handle.controller(PluginNamedItemController.class).configure(item);
        return new PluginChildView<>(handle);
    }

    PluginConfigFieldView configField(ConfigField field, String value) {
        ViewHandle<VBox> handle = load(CONFIG_FIELD, "插件配置字段");
        PluginConfigFieldController controller =
                handle.controller(PluginConfigFieldController.class);
        controller.configure(field, value);
        return new PluginConfigFieldView(handle, controller);
    }

    private <T> ViewHandle<T> load(URL resource, String label) {
        try {
            return loader.load(resource);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载" + label + " FXML 失败", failure);
        }
    }

    private static URL resource(String name) {
        return Objects.requireNonNull(PluginComponentFactory.class.getResource(
                "/fxml/plugin/" + name), "缺少插件 FXML: " + name);
    }
}
