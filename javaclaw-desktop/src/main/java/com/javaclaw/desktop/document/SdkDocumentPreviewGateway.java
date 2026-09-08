package com.javaclaw.desktop.document;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;

/** 将页面请求转入 Desktop 的 SDK 后台执行通道；不读取本地工作区文件。 */
public final class SdkDocumentPreviewGateway implements DocumentPreviewGateway {
    private final DesktopPresenter desktop;

    /** @param desktop 当前Desktop会话协调器 */
    public SdkDocumentPreviewGateway(DesktopPresenter desktop) {
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<DocumentPreview> resolve(DocumentReference reference) {
        return desktop.submitSettingsRequest(client -> client.documents().resolve(reference, CommandOptions.create(0)));
    }

    @Override
    public CompletionStage<DocumentChunk> read(String handle, long offset) {
        return desktop.submitSettingsRequest(client -> client.documents().readChunk(handle, offset, 256 * 1024));
    }

    @Override
    public CompletionStage<DocumentPreview> resource(String handle, String href) {
        return desktop.submitSettingsRequest(
                client -> client.documents().resolveResource(handle, href, CommandOptions.create(0)));
    }

    @Override
    public CompletionStage<DocumentPreview> renew(String handle) {
        return desktop.submitSettingsRequest(client -> client.documents().renew(handle, CommandOptions.create(0)));
    }

    @Override
    public CompletionStage<Void> close(String handle) {
        return desktop.submitSettingsRequest(client -> {
            client.documents().close(handle, CommandOptions.create(0));
            return null;
        });
    }
}
