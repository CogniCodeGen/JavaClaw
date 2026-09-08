package com.javaclaw.desktop.settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.facade.AttachmentUploadOptions;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 仅通过 Desktop Presenter 和 Java SDK 访问扩展页面的生产实现。 */
public final class SdkExtensionSettingsGateway implements ExtensionSettingsGateway {
    private final DesktopPresenter presenter;

    /**
     * 创建 SDK 扩展页面边界。
     *
     * @param presenter Desktop Presenter
     */
    public SdkExtensionSettingsGateway(DesktopPresenter presenter) {
        this.presenter = Objects.requireNonNull(presenter, "presenter");
    }

    @Override
    public CompletableFuture<List<ExtensionRpcContracts.ViewDocument>> list(String extensionId) {
        return presenter.listExtensionViews(Optional.of(requireText(extensionId, "extensionId")));
    }

    @Override
    public CompletableFuture<ViewData> load(
            WorkspaceId workspaceId,
            ExtensionRpcContracts.ViewDocument document,
            ViewSchema schema,
            ViewLoadRequest request) {
        return presenter.loadExtensionViewData(workspaceId, document, schema, request);
    }

    @Override
    public CompletableFuture<ExtensionRpcContracts.CallResult> query(
            WorkspaceId workspaceId, String extensionId, String operation, CanonicalPayload arguments) {
        var call = new ExtensionRpcContracts.CallPayload(
                extensionId, workspaceId, Optional.empty(), Optional.empty(), operation, arguments);
        return presenter.submitSettingsRequest(client -> client.extensions().query(call));
    }

    @Override
    public CompletableFuture<ExtensionRpcContracts.CallResult> execute(
            WorkspaceId workspaceId, String extensionId, ViewCommandInvocation invocation) {
        return presenter.executeExtensionViewCommand(
                Objects.requireNonNull(workspaceId, "workspaceId"),
                requireText(extensionId, "extensionId"),
                invocation);
    }

    @Override
    public CompletableFuture<AttachmentRef> upload(WorkspaceId workspaceId, ViewAttachmentUploadRequest request) {
        ViewAttachmentUploadRequest checked = Objects.requireNonNull(request, "request");
        UploadSnapshot snapshot = snapshot(checked, Objects.requireNonNull(workspaceId, "workspaceId"));
        return presenter.submitSettingsRequest(
                client -> client.attachments().upload(snapshot.source(), snapshot.options()));
    }

    @Override
    public DesktopNotificationSubscription subscribe(
            WorkspaceId workspaceId, String extensionId, Consumer<ExtensionRpcContracts.ExtensionEvent> listener) {
        return presenter.subscribeExtensionEvents(
                Objects.requireNonNull(workspaceId, "workspaceId"),
                requireText(extensionId, "extensionId"),
                Objects.requireNonNull(listener, "listener"));
    }

    private static String mediaType(Path source) {
        Optional<String> controlled = controlledMediaType(source);
        if (controlled.isPresent()) {
            return controlled.orElseThrow();
        }
        try {
            String detected = Files.probeContentType(source);
            return detected == null ? fallbackMediaType(source) : detected.toLowerCase(Locale.ROOT);
        } catch (IOException failure) {
            throw new UncheckedIOException("无法识别所选文件类型", failure);
        }
    }

    private static String fallbackMediaType(Path source) {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (name.endsWith(".txt")) {
            return "text/plain";
        }
        if (name.endsWith(".csv")) {
            return "text/csv";
        }
        return "application/octet-stream";
    }

    private static Optional<String> controlledMediaType(Path source) {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return Optional.of("text/x-java-source");
        }
        if (name.endsWith(".jsh")) {
            return Optional.of("text/x-jshell");
        }
        return Optional.empty();
    }

    static UploadSnapshot snapshot(ViewAttachmentUploadRequest request, WorkspaceId workspaceId) {
        ViewAttachmentUploadRequest checked = Objects.requireNonNull(request, "request");
        String mediaType = mediaType(checked.source());
        if (!checked.policy().accepts(mediaType)) {
            throw new IllegalArgumentException("所选文件类型不在扩展声明的允许范围内: " + mediaType);
        }
        AttachmentUploadOptions options = new AttachmentUploadOptions(
                AttachmentScope.workspace(Objects.requireNonNull(workspaceId, "workspaceId")),
                mediaType,
                checked.policy().maximumBytes(),
                checked.cancellation(),
                CommandOptions.create(0));
        return new UploadSnapshot(checked.source(), options);
    }

    /** 用户动作创建时冻结的上传参数；后台线程不得重新读取当前 Workspace。 */
    record UploadSnapshot(Path source, AttachmentUploadOptions options) {
        UploadSnapshot {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(options, "options");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
