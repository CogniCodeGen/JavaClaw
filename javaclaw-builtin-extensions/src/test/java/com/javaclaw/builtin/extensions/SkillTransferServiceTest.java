package com.javaclaw.builtin.extensions;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.builtin.contracts.SkillTransferContracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTransferServiceTest {
    @Test
    void markdownExportAndImportRoundTripCreatesDraftWithoutResources() throws Exception {
        BuiltinExtensionTestSupport source = new BuiltinExtensionTestSupport();
        var sourceExtension = source.start(new SkillExtension());
        SkillContracts.PublishedSkill published = publish(source, sourceExtension, "review", Optional.empty());

        SkillTransferContracts.ExportResult exported = export(
                source, sourceExtension, published, SkillTransferContracts.TransferFormat.MARKDOWN, "export-markdown");
        byte[] markdown = source.attachmentContents.get(exported.attachment().digest());
        assertTrue(new String(markdown, StandardCharsets.UTF_8).startsWith("<!-- javaclaw-skill-v5"));

        BuiltinExtensionTestSupport target = new BuiltinExtensionTestSupport();
        var targetExtension = target.start(new SkillExtension());
        target.claimAttachment(target.workspaceId, exported.attachment(), markdown);
        SkillTransferContracts.ImportResult imported = target.decode(
                targetExtension.command(target.request(
                        "skill/import",
                        new SkillTransferContracts.ImportRequest(exported.attachment()),
                        Optional.of("import-markdown"),
                        0)),
                SkillTransferContracts.ImportResult.class);

        assertEquals(SkillTransferContracts.TransferFormat.MARKDOWN, imported.format());
        assertEquals(published.id(), imported.draft().id());
        assertEquals(published.instructions(), imported.draft().instructions());
        assertTrue(imported.draft().resources().isEmpty());
    }

    @Test
    void bundleRoundTripStoresEveryResourceAsWorkspaceAttachment() throws Exception {
        BuiltinExtensionTestSupport source = new BuiltinExtensionTestSupport();
        var sourceExtension = source.start(new SkillExtension());
        byte[] resourceContent = "受审阅的资源正文".getBytes(StandardCharsets.UTF_8);
        AttachmentRef resource = reference("guide.txt", "text/plain", resourceContent);
        source.claimAttachment(source.workspaceId, resource, resourceContent);
        SkillContracts.PublishedSkill published = publish(source, sourceExtension, "portable", Optional.of(resource));

        SkillTransferContracts.ExportResult exported = export(
                source, sourceExtension, published, SkillTransferContracts.TransferFormat.BUNDLE, "export-bundle");
        byte[] bundle = source.attachmentContents.get(exported.attachment().digest());
        BuiltinExtensionTestSupport target = new BuiltinExtensionTestSupport();
        var targetExtension = target.start(new SkillExtension());
        target.claimAttachment(target.workspaceId, exported.attachment(), bundle);

        SkillTransferContracts.ImportResult imported = target.decode(
                targetExtension.command(target.request(
                        "skill/import",
                        new SkillTransferContracts.ImportRequest(exported.attachment()),
                        Optional.of("import-bundle"),
                        0)),
                SkillTransferContracts.ImportResult.class);

        assertEquals(SkillTransferContracts.TransferFormat.BUNDLE, imported.format());
        assertEquals(1, imported.draft().resources().size());
        SkillContracts.Resource importedResource = imported.draft().resources().getFirst();
        assertEquals(resource.digest(), importedResource.digest());
        assertArrayEquals(resourceContent, target.attachmentContents.get(resource.digest()));
    }

    @Test
    void transferRejectsOldZipUnknownEntryAndMarkdownResourceLoss() throws Exception {
        byte[] invalid = zip("../legacy.skill", "legacy".getBytes(StandardCharsets.UTF_8));
        AttachmentRef invalidBundle = reference("legacy.zip", SkillTransferContracts.BUNDLE_MEDIA_TYPE, invalid);
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        support.claimAttachment(support.workspaceId, invalidBundle, invalid);

        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "skill/import",
                        new SkillTransferContracts.ImportRequest(invalidBundle),
                        Optional.of("reject-legacy"),
                        0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillTransferContracts.ImportRequest(
                        new AttachmentRef(invalidBundle.digest(), "application/zip", "old.zip", invalid.length)));

        byte[] resourceContent = "resource".getBytes(StandardCharsets.UTF_8);
        AttachmentRef resource = reference("resource.txt", "text/plain", resourceContent);
        support.claimAttachment(support.workspaceId, resource, resourceContent);
        SkillContracts.PublishedSkill published = publish(support, started, "with-resource", Optional.of(resource));
        assertThrows(
                IllegalArgumentException.class,
                () -> export(
                        support,
                        started,
                        published,
                        SkillTransferContracts.TransferFormat.MARKDOWN,
                        "reject-lossy-markdown"));
    }

    private static SkillContracts.PublishedSkill publish(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String id,
            Optional<AttachmentRef> resource)
            throws Exception {
        SkillContracts.Draft draft = support.decode(
                started.command(support.request(
                        "draft/save-content",
                        new SkillContracts.SaveContentRequest(id, id, "可移植技能", "执行已审阅步骤"),
                        Optional.of("save-" + id),
                        0)),
                SkillContracts.Draft.class);
        if (resource.isPresent()) {
            AttachmentRef attachment = resource.orElseThrow();
            draft = support.decode(
                    started.command(support.request(
                            "draft/resource/add",
                            new SkillContracts.AddResourceRequest(id, attachment.fileName(), attachment, false),
                            Optional.of("resource-" + id),
                            draft.revision())),
                    SkillContracts.Draft.class);
        }
        return support.decode(
                started.command(support.request(
                        "publish",
                        new SkillContracts.PublishRequest(id, draft.revision()),
                        Optional.of("publish-" + id),
                        0)),
                SkillContracts.PublishedSkill.class);
    }

    private static SkillTransferContracts.ExportResult export(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            SkillContracts.PublishedSkill published,
            SkillTransferContracts.TransferFormat format,
            String key)
            throws Exception {
        return support.decode(
                started.command(support.request(
                        "skill/export",
                        new SkillTransferContracts.ExportRequest(published.id(), format),
                        Optional.of(key),
                        published.revision())),
                SkillTransferContracts.ExportResult.class);
    }

    private static AttachmentRef reference(String fileName, String mediaType, byte[] content) throws Exception {
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        return new AttachmentRef(digest, mediaType, fileName, content.length);
    }

    private static byte[] zip(String name, byte[] content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            output.putNextEntry(new ZipEntry(name));
            output.write(content);
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
