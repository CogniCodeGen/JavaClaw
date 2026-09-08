package com.javaclaw.desktop.settings;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewGraphAction;
import com.javaclaw.desktop.view.ViewInteractionHandler;
import com.javaclaw.desktop.view.ViewPageDirection;

/** 将受限页面交互交回页面所有者；不持有 SDK、工作区或额外业务状态。 */
record ViewPageInteractions(
        BiConsumer<String, Boolean> dirtyAction,
        Consumer<ViewCommandInvocation> commandAction,
        Runnable reloadAction,
        BiConsumer<String, ViewPageDirection> pageAction,
        BiConsumer<String, Optional<String>> selectionAction,
        Function<ViewAttachmentUploadRequest, CompletionStage<AttachmentRef>> uploadAction,
        Consumer<ViewGraphAction> graphAction)
        implements ViewInteractionHandler {
    @Override
    public void dirty(String formId, boolean dirty) {
        dirtyAction.accept(formId, dirty);
    }

    @Override
    public void execute(ViewCommandInvocation invocation) {
        commandAction.accept(invocation);
    }

    @Override
    public void reload() {
        reloadAction.run();
    }

    @Override
    public void page(String sourceId, ViewPageDirection direction) {
        pageAction.accept(sourceId, direction);
    }

    @Override
    public void select(String sourceId, Optional<String> selectedKey) {
        selectionAction.accept(sourceId, selectedKey);
    }

    @Override
    public CompletionStage<AttachmentRef> upload(ViewAttachmentUploadRequest request) {
        return uploadAction.apply(request);
    }

    @Override
    public void graph(ViewGraphAction action) {
        graphAction.accept(action);
    }
}
