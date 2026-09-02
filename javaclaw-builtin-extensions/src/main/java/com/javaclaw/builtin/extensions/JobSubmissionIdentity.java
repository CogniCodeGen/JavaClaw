package com.javaclaw.builtin.extensions;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequest;

/** Extension Job 首次提交前使用的稳定请求身份；冻结结果不得进入该对象。 */
record JobSubmissionIdentity(
        ExtensionId extensionId,
        WorkspaceId workspaceId,
        String operation,
        long expectedRevision,
        CanonicalPayload payload) {
    JobSubmissionIdentity {
        Objects.requireNonNull(extensionId, "extensionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        operation = Objects.requireNonNull(operation, "operation").strip();
        if (operation.isEmpty()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        Objects.requireNonNull(payload, "payload");
    }

    static JobSubmissionIdentity from(ExtensionId extensionId, ExtensionRequest request) {
        ExtensionRequest checked = Objects.requireNonNull(request, "request");
        return new JobSubmissionIdentity(
                extensionId, checked.workspaceId(), checked.operation(), checked.expectedRevision(), checked.payload());
    }
}
