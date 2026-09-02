package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SkillContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillAttachmentResourceTest {
    @Test
    void resourceAddRequiresExactClaimFromCurrentWorkspace() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        SkillContracts.Draft draft = saveDraft(support, started, "owned");
        AttachmentRef attachment = new AttachmentRef("b".repeat(64), "text/markdown", "guide.md", 128);

        assertThrows(
                IllegalArgumentException.class,
                () -> addResource(support, started, draft, "guide", attachment, "unclaimed"));
        support.claimAttachment(WorkspaceId.random(), attachment);
        assertThrows(
                IllegalArgumentException.class,
                () -> addResource(support, started, draft, "guide", attachment, "other-workspace"));

        support.claimAttachment(support.workspaceId, attachment);
        AttachmentRef wrongMedia = new AttachmentRef(attachment.digest(), "text/plain", "guide.md", 128);
        AttachmentRef wrongSize = new AttachmentRef(attachment.digest(), attachment.mediaType(), "guide.md", 127);
        assertThrows(
                IllegalArgumentException.class,
                () -> addResource(support, started, draft, "guide", wrongMedia, "wrong-media"));
        assertThrows(
                IllegalArgumentException.class,
                () -> addResource(support, started, draft, "guide", wrongSize, "wrong-size"));

        SkillContracts.Draft updated = addResource(support, started, draft, "guide", attachment, "owned-add");
        assertEquals(
                List.of(new SkillContracts.Resource("guide", attachment.mediaType(), attachment.digest(), false)),
                updated.resources());
    }

    @Test
    void resourceAddRejectsStaleRevisionAndDuplicateResourceIdentity() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        SkillContracts.Draft draft = saveDraft(support, started, "revisioned");
        AttachmentRef first = new AttachmentRef("c".repeat(64), "text/plain", "first.txt", 64);
        AttachmentRef second = new AttachmentRef("d".repeat(64), "text/plain", "second.txt", 64);
        support.claimAttachment(support.workspaceId, first);
        support.claimAttachment(support.workspaceId, second);
        SkillContracts.Draft updated = addResource(support, started, draft, "same", first, "first-add");

        assertThrows(
                IllegalArgumentException.class,
                () -> addResource(support, started, draft, "second", second, "stale-add"));
        assertThrows(
                IllegalArgumentException.class,
                () -> addResource(support, started, updated, "same", second, "duplicate-add"));
        assertEquals(1, updated.resources().size());
    }

    private static SkillContracts.Draft saveDraft(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started started, String id)
            throws Exception {
        SkillContracts.SaveContentRequest content =
                new SkillContracts.SaveContentRequest(id, "资源技能", "安全资源管理", "只读取已上传资源");
        return support.decode(
                started.command(support.request("draft/save-content", content, Optional.of(id + "-save"), 0)),
                SkillContracts.Draft.class);
    }

    private static SkillContracts.Draft addResource(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            SkillContracts.Draft draft,
            String resourceId,
            AttachmentRef attachment,
            String key)
            throws Exception {
        SkillContracts.AddResourceRequest request =
                new SkillContracts.AddResourceRequest(draft.id(), resourceId, attachment, false);
        return support.decode(
                started.command(support.request("draft/resource/add", request, Optional.of(key), draft.revision())),
                SkillContracts.Draft.class);
    }
}
