package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.extension.spi.ViewAttachmentPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SdkExtensionSettingsGatewayTest {
    private static final WorkspaceId INITIAL_WORKSPACE = WorkspaceId.parse("61e9d496-0798-49d8-a58e-f2337058e382");
    private static final WorkspaceId LATER_WORKSPACE = WorkspaceId.parse("e929bf02-e577-46b4-adb7-bf21610890b7");

    @Test
    void freezesWorkspaceAndUploadPolicyBeforeBackgroundSubmission(@TempDir Path temporary) {
        CancellationSource cancellation = new CancellationSource();
        ViewAttachmentUploadRequest request =
                request(temporary.resolve("Runner.JAVA"), Set.of("text/x-java-source"), 4_096, cancellation);

        SdkExtensionSettingsGateway.UploadSnapshot snapshot =
                SdkExtensionSettingsGateway.snapshot(request, INITIAL_WORKSPACE);

        // 模拟用户动作提交后切换 Workspace；已生成快照不能跟随当前选择漂移。
        WorkspaceId currentWorkspace = LATER_WORKSPACE;
        assertEquals(
                AttachmentScope.workspace(INITIAL_WORKSPACE), snapshot.options().scope());
        assertNotEquals(
                AttachmentScope.workspace(currentWorkspace), snapshot.options().scope());
        assertEquals("text/x-java-source", snapshot.options().mediaType());
        assertEquals(4_096, snapshot.options().maximumBytes());
        assertSame(cancellation, snapshot.options().cancellation());
        assertEquals(request.source(), snapshot.source());
    }

    @Test
    void usesControlledJavaAndJshellMediaTypesBeforePlatformProbe(@TempDir Path temporary) {
        SdkExtensionSettingsGateway.UploadSnapshot javaSource = SdkExtensionSettingsGateway.snapshot(
                request(temporary.resolve("Agent.java"), Set.of("text/x-java-source"), 1_024, new CancellationSource()),
                INITIAL_WORKSPACE);
        SdkExtensionSettingsGateway.UploadSnapshot jshellSource = SdkExtensionSettingsGateway.snapshot(
                request(temporary.resolve("bootstrap.JSH"), Set.of("text/x-jshell"), 1_024, new CancellationSource()),
                INITIAL_WORKSPACE);

        assertEquals("text/x-java-source", javaSource.options().mediaType());
        assertEquals("text/x-jshell", jshellSource.options().mediaType());
    }

    @Test
    void rejectsControlledMediaTypeOutsideExtensionPolicy(@TempDir Path temporary) {
        ViewAttachmentUploadRequest request =
                request(temporary.resolve("Runner.java"), Set.of("text/plain"), 1_024, new CancellationSource());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> SdkExtensionSettingsGateway.snapshot(request, INITIAL_WORKSPACE));

        assertEquals("所选文件类型不在扩展声明的允许范围内: text/x-java-source", failure.getMessage());
    }

    @Test
    void unknownSuffixUsesSafeBinaryFallback(@TempDir Path temporary) {
        SdkExtensionSettingsGateway.UploadSnapshot snapshot = SdkExtensionSettingsGateway.snapshot(
                request(
                        temporary.resolve("evidence.javaclaw-unknown"),
                        Set.of("application/octet-stream"),
                        2_048,
                        new CancellationSource()),
                INITIAL_WORKSPACE);

        assertEquals("application/octet-stream", snapshot.options().mediaType());
    }

    private static ViewAttachmentUploadRequest request(
            Path source, Set<String> mediaTypes, long maximumBytes, CancellationSource cancellation) {
        return new ViewAttachmentUploadRequest(
                source, new ViewAttachmentPolicy(mediaTypes, maximumBytes), cancellation);
    }
}
