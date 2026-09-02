package com.javaclaw.desktop.component;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.desktop.state.InputInteractionState;
import com.javaclaw.protocol.CanonicalJson;

/** 可复用的 pending InputRequest 目录与安全表单面板。 */
public final class InputRequestPanel extends VBox {
    private final PlatformComponentFactory components;
    private final ListView<InputRequestRecord> requests = new ListView<>();
    private final InputRequestCard card;
    private final ScrollPane cardScroll = new ScrollPane();
    private final Label empty = new Label("当前没有等待输入的任务。");
    private InputInteractionState state = InputInteractionState.initial();

    /**
     * 创建输入面板。
     *
     * @param json 规范 JSON codec
     * @param components 平台组件工厂
     * @param resolution 输入决议动作
     * @param cancellation 取消所属 Turn 动作
     */
    public InputRequestPanel(
            CanonicalJson json,
            PlatformComponentFactory components,
            BiConsumer<InputRequestRecord, CanonicalPayload> resolution,
            Consumer<InputRequestRecord> cancellation) {
        this.components = Objects.requireNonNull(components, "components");
        card = new InputRequestCard(json, components, resolution, cancellation);
        configure();
    }

    /**
     * 应用最新不可变输入状态，同时保留仍存在的当前选择和表单草稿。
     *
     * @param value 权威 Desktop 输入快照
     */
    public void render(InputInteractionState value) {
        state = Objects.requireNonNull(value, "value");
        String selected = Optional.ofNullable(requests.getSelectionModel().getSelectedItem())
                .map(request -> request.request().id())
                .orElse("");
        requests.getItems().setAll(state.pendingRequests());
        requests.getItems().stream()
                .filter(request -> request.request().id().equals(selected))
                .findFirst()
                .ifPresentOrElse(
                        request -> requests.getSelectionModel().select(request),
                        () -> requests.getSelectionModel().selectFirst());
        boolean hasRequests = !state.pendingRequests().isEmpty();
        empty.setText(state.error().map(error -> "输入请求读取失败：" + error).orElse("当前没有等待输入的任务。"));
        empty.getStyleClass().remove("platform-action-error");
        if (state.error().isPresent()) {
            empty.getStyleClass().add("platform-action-error");
        }
        visible(requests, state.pendingRequests().size() > 1);
        visible(cardScroll, hasRequests);
        visible(empty, !hasRequests);
        renderSelected(requests.getSelectionModel().getSelectedItem());
    }

    private void configure() {
        getStyleClass().add("input-request-panel");
        Label title = new Label("等待输入");
        title.getStyleClass().add("thinking-panel-title");
        requests.setCellFactory(ignored -> components.detailCell(
                request -> request.request().prompt(),
                request -> request.request().producerId() + " · v" + request.revision()));
        requests.getStyleClass().addAll("platform-data-list", "input-request-list");
        requests.setAccessibleText("等待用户输入的任务");
        requests.setPrefHeight(112);
        requests.getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> renderSelected(selected));
        cardScroll.setContent(card);
        cardScroll.setFitToWidth(true);
        cardScroll.setMaxHeight(360);
        cardScroll.getStyleClass().add("input-request-scroll");
        VBox.setVgrow(cardScroll, Priority.SOMETIMES);
        empty.setWrapText(true);
        empty.getStyleClass().add("progress-empty");
        getChildren().addAll(title, requests, cardScroll, empty);
        render(InputInteractionState.initial());
    }

    private void renderSelected(InputRequestRecord selected) {
        if (selected == null) {
            card.clear();
            return;
        }
        card.render(selected, state.submitting(selected), state.error());
    }

    private static void visible(javafx.scene.Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }
}
