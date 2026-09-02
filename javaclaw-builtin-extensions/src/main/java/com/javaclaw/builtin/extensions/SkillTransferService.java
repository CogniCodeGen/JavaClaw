package com.javaclaw.builtin.extensions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.builtin.contracts.SkillTransferContracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;

/** Skill v5 Markdown 与 Bundle 的严格导入导出实现。 */
final class SkillTransferService {
    private static final String FORMAT = "javaclaw-skill";
    private static final int FORMAT_VERSION = 5;
    private static final String MANIFEST_ENTRY = "skill.json";
    private static final int MAXIMUM_MANIFEST_BYTES = 1024 * 1024;
    private static final String MARKDOWN_PREFIX = "<!-- javaclaw-skill-v5\n";
    private static final String MARKDOWN_SUFFIX = "\n-->\n";

    private final ExtensionPayloadCodec payloads;

    SkillTransferService(ExtensionPayloadCodec payloads) {
        this.payloads = Objects.requireNonNull(payloads, "payloads");
    }

    ExtensionResponse importDraft(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String draftsCollection) {
        SkillTransferContracts.ImportRequest input =
                payloads.decode(request.payload(), SkillTransferContracts.ImportRequest.class);
        AttachmentMetadata owned = context.attachments().requireOwned(input.attachment());
        requireSameAttachment(input.attachment(), owned);
        AttachmentContent source = context.attachments()
                .readOwned(input.attachment().digest(), SkillTransferContracts.MAXIMUM_IMPORT_BYTES);
        SkillTransferContracts.TransferFormat format = SkillTransferContracts.TransferFormat.fromMediaType(
                source.metadata().mediaType());
        ImportedDraft imported =
                switch (format) {
                    case MARKDOWN -> parseMarkdown(source.content());
                    case BUNDLE -> parseBundle(source.content(), request, context);
                };
        Optional<SkillContracts.Draft> current =
                transaction.get(draftsCollection, imported.id()).map(this::decodeDraft);
        long actualRevision = current.map(SkillContracts.Draft::revision).orElse(0L);
        requireExpected(request, actualRevision);
        Instant now = context.clock().instant();
        SkillContracts.Draft draft = new SkillContracts.Draft(
                imported.id(),
                Math.addExact(actualRevision, 1),
                imported.name(),
                imported.description(),
                imported.instructions(),
                imported.resources(),
                current.map(SkillContracts.Draft::createdAt).orElse(now),
                now);
        transaction.put(draftsCollection, draft.id(), actualRevision, payloads.encode(draft));
        SkillTransferContracts.ImportResult result = new SkillTransferContracts.ImportResult(draft, format);
        return new ExtensionResponse(payloads.encode(result), draft.revision());
    }

    ExtensionResponse exportPublished(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String publishedCollection) {
        SkillTransferContracts.ExportRequest input =
                payloads.decode(request.payload(), SkillTransferContracts.ExportRequest.class);
        SkillContracts.PublishedSkill published = transaction
                .get(publishedCollection, input.id())
                .map(this::decodePublished)
                .orElseThrow(() -> new IllegalArgumentException("Published Skill does not exist"));
        requireExpected(request, published.revision());
        byte[] content =
                switch (input.format()) {
                    case MARKDOWN -> markdown(published);
                    case BUNDLE -> bundle(published, context);
                };
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Skill export requires idempotency key"));
        AttachmentRef attachment = context.attachments()
                .storeGenerated(
                        "skill/export",
                        key,
                        input.format().mediaType(),
                        published.id() + input.format().fileSuffix(),
                        content);
        SkillTransferContracts.ExportResult result = new SkillTransferContracts.ExportResult(
                attachment, published.id(), published.revision(), published.digest(), input.format());
        return new ExtensionResponse(payloads.encode(result), published.revision());
    }

    private ImportedDraft parseMarkdown(byte[] content) {
        String markdown = decodeUtf8(content);
        if (!markdown.startsWith(MARKDOWN_PREFIX)) {
            throw new IllegalArgumentException("Markdown is not a v5 Skill export");
        }
        int end = markdown.indexOf(MARKDOWN_SUFFIX, MARKDOWN_PREFIX.length());
        if (end < 0) {
            throw new IllegalArgumentException("Markdown v5 metadata block is incomplete");
        }
        String headerJson = markdown.substring(MARKDOWN_PREFIX.length(), end);
        MarkdownHeader header = payloads.decode(new CanonicalPayload(headerJson), MarkdownHeader.class);
        requireFormat(header.format(), header.version());
        String instructions = markdown.substring(end + MARKDOWN_SUFFIX.length());
        return new ImportedDraft(header.id(), header.name(), header.description(), instructions, List.of());
    }

    private ImportedDraft parseBundle(byte[] content, ExtensionRequest request, ExtensionExecutionContext context) {
        Map<String, byte[]> entries = readEntries(content);
        byte[] manifestBytes = entries.get(MANIFEST_ENTRY);
        if (manifestBytes == null) {
            throw new IllegalArgumentException("Skill Bundle does not contain skill.json");
        }
        BundleManifest manifest =
                payloads.decode(new CanonicalPayload(decodeUtf8(manifestBytes)), BundleManifest.class);
        requireFormat(manifest.format(), manifest.version());
        List<SkillContracts.Resource> resources = importResources(manifest.resources(), entries, request, context);
        Set<String> expectedEntries = new HashSet<>();
        expectedEntries.add(MANIFEST_ENTRY);
        manifest.resources().stream().map(BundleResource::path).forEach(expectedEntries::add);
        if (!entries.keySet().equals(expectedEntries)) {
            throw new IllegalArgumentException("Skill Bundle contains undeclared entries");
        }
        return new ImportedDraft(
                manifest.id(), manifest.name(), manifest.description(), manifest.instructions(), resources);
    }

    private List<SkillContracts.Resource> importResources(
            List<BundleResource> declarations,
            Map<String, byte[]> entries,
            ExtensionRequest request,
            ExtensionExecutionContext context) {
        if (declarations.size() > SkillContracts.MAXIMUM_RESOURCES) {
            throw new IllegalArgumentException("Skill Bundle contains too many resources");
        }
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Skill import requires idempotency key"));
        List<SkillContracts.Resource> resources = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < declarations.size(); index++) {
            BundleResource declaration = declarations.get(index);
            requireBundleResource(declaration, ids, paths);
            byte[] bytes = Optional.ofNullable(entries.get(declaration.path()))
                    .orElseThrow(() -> new IllegalArgumentException("Skill Bundle resource entry is missing"));
            if (!sha256(bytes).equals(declaration.digest())) {
                throw new IllegalArgumentException("Skill Bundle resource digest does not match its manifest");
            }
            AttachmentRef stored = context.attachments()
                    .storeGenerated(
                            "skill/import/resource/" + index, key, declaration.mediaType(), declaration.id(), bytes);
            if (!stored.digest().equals(declaration.digest())) {
                throw new IllegalStateException("Stored Skill resource digest changed");
            }
            resources.add(new SkillContracts.Resource(
                    declaration.id(), declaration.mediaType(), declaration.digest(), declaration.executable()));
        }
        return List.copyOf(resources);
    }

    private byte[] markdown(SkillContracts.PublishedSkill published) {
        if (!published.resources().isEmpty()) {
            throw new IllegalArgumentException("Published Skill with resources must be exported as a v5 Bundle");
        }
        MarkdownHeader header =
                new MarkdownHeader(FORMAT, FORMAT_VERSION, published.id(), published.name(), published.description());
        String value =
                MARKDOWN_PREFIX + payloads.encode(header).json() + MARKDOWN_SUFFIX + published.instructions() + "\n";
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] bundle(SkillContracts.PublishedSkill published, ExtensionExecutionContext context) {
        List<BundleResourceContent> resources = new ArrayList<>();
        for (int index = 0; index < published.resources().size(); index++) {
            SkillContracts.Resource resource = published.resources().get(index);
            AttachmentContent content =
                    context.attachments().readOwned(resource.digest(), SkillContracts.MAXIMUM_RESOURCE_BYTES);
            requirePublishedResource(resource, content.metadata());
            String path = resourcePath(index, resource.digest());
            resources.add(new BundleResourceContent(
                    new BundleResource(
                            resource.id(), resource.mediaType(), resource.digest(), resource.executable(), path),
                    content.content()));
        }
        BundleManifest manifest = new BundleManifest(
                FORMAT,
                FORMAT_VERSION,
                published.id(),
                published.name(),
                published.description(),
                published.instructions(),
                resources.stream().map(BundleResourceContent::metadata).toList());
        return writeBundle(manifest, resources);
    }

    private byte[] writeBundle(BundleManifest manifest, List<BundleResourceContent> resources) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                writeEntry(
                        output, MANIFEST_ENTRY, payloads.encode(manifest).json().getBytes(StandardCharsets.UTF_8));
                for (BundleResourceContent resource : resources) {
                    writeEntry(output, resource.metadata().path(), resource.content());
                }
            }
            byte[] result = bytes.toByteArray();
            if (result.length > SkillTransferContracts.MAXIMUM_IMPORT_BYTES) {
                throw new IllegalArgumentException("Generated Skill Bundle exceeds the supported range");
            }
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("Skill Bundle generation failed", failure);
        }
    }

    private static Map<String, byte[]> readEntries(byte[] content) {
        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = requireEntryName(entry);
                int limit = MANIFEST_ENTRY.equals(name)
                        ? MAXIMUM_MANIFEST_BYTES
                        : Math.toIntExact(SkillContracts.MAXIMUM_RESOURCE_BYTES);
                byte[] bytes = readEntry(input, limit);
                total = Math.addExact(total, bytes.length);
                if (total > SkillTransferContracts.MAXIMUM_IMPORT_BYTES || entries.putIfAbsent(name, bytes) != null) {
                    throw new IllegalArgumentException("Skill Bundle entries exceed limits or are duplicated");
                }
                input.closeEntry();
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Skill Bundle ZIP is invalid", failure);
        }
        return Map.copyOf(entries);
    }

    private static byte[] readEntry(ZipInputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) {
                continue;
            }
            if (Math.addExact(output.size(), count) > limit) {
                throw new IllegalArgumentException("Skill Bundle entry exceeds its uncompressed limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private SkillContracts.Draft decodeDraft(VersionedDocument document) {
        SkillContracts.Draft draft = payloads.decode(document.payload(), SkillContracts.Draft.class);
        requireStoredRevision(draft.revision(), document.revision());
        return draft;
    }

    private SkillContracts.PublishedSkill decodePublished(VersionedDocument document) {
        SkillContracts.PublishedSkill published =
                payloads.decode(document.payload(), SkillContracts.PublishedSkill.class);
        requireStoredRevision(published.revision(), document.revision());
        return published;
    }

    private static void requireSameAttachment(AttachmentRef reference, AttachmentMetadata metadata) {
        if (!reference.digest().equals(metadata.digest())
                || !reference.mediaType().equals(metadata.mediaType())
                || reference.sizeBytes() != metadata.sizeBytes()) {
            throw new IllegalArgumentException("Skill import Attachment differs from its Workspace claim");
        }
    }

    private static void requirePublishedResource(SkillContracts.Resource resource, AttachmentMetadata metadata) {
        if (!resource.digest().equals(metadata.digest())
                || !resource.mediaType().equals(metadata.mediaType())
                || metadata.sizeBytes() < 1
                || metadata.sizeBytes() > SkillContracts.MAXIMUM_RESOURCE_BYTES) {
            throw new IllegalArgumentException("Published Skill resource differs from its Attachment claim");
        }
    }

    private static void requireBundleResource(BundleResource resource, Set<String> ids, Set<String> paths) {
        new SkillContracts.Resource(resource.id(), resource.mediaType(), resource.digest(), resource.executable());
        if (!resource.path().matches("resources/[0-9]{2}-[0-9a-f]{64}")
                || !ids.add(resource.id())
                || !paths.add(resource.path())) {
            throw new IllegalArgumentException("Skill Bundle resource identity is invalid or duplicated");
        }
    }

    private static String requireEntryName(ZipEntry entry) {
        if (entry.isDirectory()) {
            throw new IllegalArgumentException("Skill Bundle must not contain directories");
        }
        String name = entry.getName();
        if (!MANIFEST_ENTRY.equals(name) && !name.matches("resources/[0-9]{2}-[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Skill Bundle contains an unsafe or unknown entry");
        }
        return name;
    }

    private static void requireFormat(String format, int version) {
        if (!FORMAT.equals(format) || version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Skill transfer format is not v5");
        }
    }

    private static void requireExpected(ExtensionRequest request, long actualRevision) {
        if (request.expectedRevision() != actualRevision) {
            throw new IllegalArgumentException("Skill transfer expected revision differs from current revision");
        }
    }

    private static void requireStoredRevision(long payloadRevision, long storedRevision) {
        if (payloadRevision != storedRevision) {
            throw new IllegalStateException("Skill payload revision differs from managed store");
        }
    }

    private static String resourcePath(int index, String digest) {
        return "resources/%02d-%s".formatted(index, digest);
    }

    private static void writeEntry(ZipOutputStream output, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }

    private static String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Skill text is not valid UTF-8", failure);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record ImportedDraft(
            String id, String name, String description, String instructions, List<SkillContracts.Resource> resources) {}

    private record MarkdownHeader(String format, int version, String id, String name, String description) {}

    private record BundleManifest(
            String format,
            int version,
            String id,
            String name,
            String description,
            String instructions,
            List<BundleResource> resources) {
        private BundleManifest {
            resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        }
    }

    private record BundleResource(String id, String mediaType, String digest, boolean executable, String path) {}

    private record BundleResourceContent(BundleResource metadata, byte[] content) {
        private BundleResourceContent {
            Objects.requireNonNull(metadata, "metadata");
            content = Objects.requireNonNull(content, "content").clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
