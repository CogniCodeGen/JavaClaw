package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.desktop.component.PlatformComponentFactory;

/** 服务选择视口；窄窗使用顶部选择器，保持同一目录和选择，不扩大全局窗口的最小尺寸。 */
final class ProviderServiceBrowser extends BorderPane {
    private final ListView<ProviderEndpoint> services = new ListView<>();
    private final ComboBox<ProviderEndpoint> compact = new ComboBox<>();
    private final TextField search = new TextField();
    private final VBox sidebar = new VBox(8);
    private final HBox toolbar = new HBox(8);
    private List<ProviderEndpoint> catalog = List.of();
    private Optional<ProviderEndpoint> selected = Optional.empty();
    private Consumer<ProviderEndpoint> listener = ignored -> {};
    private boolean rendering;
    private boolean narrow;
    private boolean creating;
    private Node create;

    ProviderServiceBrowser(Node detail) {
        PlatformComponentFactory components = new PlatformComponentFactory();
        setMinSize(0, 0);
        setCenter(detail);
        services.setId("providerServicesList");
        services.getStyleClass().add("platform-data-list");
        services.setMinSize(0, 0);
        services.setCellFactory(ignored -> components.detailCell(
                value -> value.spec().displayName(),
                value -> SettingsLabels.providerLifecycle(value.lifecycle()) + " · "
                        + value.spec().models().size() + " 个模型"));
        services.setPlaceholder(new Label("暂无匹配服务"));
        compact.setId("providerServicesCompact");
        compact.setMaxWidth(Double.MAX_VALUE);
        compact.setMinWidth(120);
        compact.setConverter(SettingsLabels.converter(value -> value.spec().displayName()));
        compact.setPromptText("选择模型服务");
        search.setId("providerServicesSearch");
        search.setPromptText("搜索服务");
        search.setMinWidth(0);
        sidebar.setPrefWidth(220);
        sidebar.setMinWidth(180);
        VBox.setVgrow(services, Priority.ALWAYS);
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox.setHgrow(compact, Priority.ALWAYS);
        services.getSelectionModel().selectedItemProperty().addListener((ignored, before, value) -> select(value));
        compact.valueProperty().addListener((ignored, before, value) -> select(value));
        search.textProperty().addListener((ignored, before, value) -> filter());
        widthProperty().addListener((ignored, before, value) -> layoutForWidth(value.doubleValue()));
        sidebar.getChildren().setAll(search, services);
        setLeft(sidebar);
    }

    void onSelected(Consumer<ProviderEndpoint> callback) {
        listener = callback;
    }

    void creationAction(Node action) {
        create = action;
        arrange();
    }

    void creating(boolean value) {
        creating = value;
        compact.setPromptText(value ? "正在添加新服务" : "选择模型服务");
        filter();
    }

    void render(List<ProviderEndpoint> values, Optional<ProviderEndpoint> selection) {
        catalog = List.copyOf(values);
        selected = selection;
        filter();
    }

    void lock(boolean locked) {
        services.setDisable(locked);
        compact.setDisable(locked);
    }

    private void select(ProviderEndpoint value) {
        if (!rendering && value != null) {
            listener.accept(value);
        }
    }

    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        List<ProviderEndpoint> visible = catalog.stream()
                .filter(value ->
                        value.spec().displayName().toLowerCase(Locale.ROOT).contains(query)
                                || value.spec()
                                        .baseUri()
                                        .map(Object::toString)
                                        .orElse("")
                                        .toLowerCase(Locale.ROOT)
                                        .contains(query))
                .toList();
        rendering = true;
        try {
            if (!services.getItems().equals(visible)) {
                services.getItems().setAll(visible);
                compact.getItems().setAll(visible);
            }
            ProviderEndpoint current = creating ? null : selected.orElse(null);
            services.getSelectionModel().select(current);
            compact.setValue(current);
        } finally {
            rendering = false;
        }
    }

    private void layoutForWidth(double width) {
        boolean next = width < 900;
        if (next == narrow) {
            return;
        }
        narrow = next;
        arrange();
    }

    private void arrange() {
        sidebar.getChildren().clear();
        toolbar.getChildren().clear();
        setLeft(null);
        setTop(null);
        if (narrow) {
            toolbar.getChildren().setAll(search, compact);
            if (create != null) {
                toolbar.getChildren().add(create);
            }
            setTop(toolbar);
            setMargin(getCenter(), new Insets(8, 0, 0, 0));
        } else {
            sidebar.getChildren().setAll(search, services);
            if (create != null) {
                sidebar.getChildren().add(create);
            }
            setLeft(sidebar);
            setMargin(getCenter(), new Insets(0, 0, 0, 12));
        }
    }
}
