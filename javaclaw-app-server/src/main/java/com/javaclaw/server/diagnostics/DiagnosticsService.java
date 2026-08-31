package com.javaclaw.server.diagnostics;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.core.api.AttachmentMetadata;

/** Creates bounded, credential-free local diagnostic views and export bundles. */
public final class DiagnosticsService implements DiagnosticsUseCases {
    private static final int MAX_EXPORT_RECORDS = 10_000;
    private static final int MAX_EXPORT_BYTES = 8 * 1024 * 1024;
    private final DiagnosticsRepository repository;
    private final AttachmentRepository attachments;
    private final ObjectMapper json;

    /** 绑定诊断仓库、附件写入和 JSON 编码器；导出只生成可下载附件，不公开服务器路径。 */
    public DiagnosticsService(DiagnosticsRepository repository, AttachmentRepository attachments, ObjectMapper json) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public DiagnosticsSnapshot read(int limit) {
        return new DiagnosticsSnapshot(
                "javaclaw-diagnostics-v1",
                Instant.now(),
                System.getProperty("java.version", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                Runtime.getRuntime().availableProcessors(),
                false,
                false,
                repository.list(limit).stream()
                        .map(value -> new DiagnosticsSnapshot.Record(
                                value.id(),
                                value.severity(),
                                value.component(),
                                value.code(),
                                value.message(),
                                validDetails(value.detailsJson()),
                                value.createdAt()))
                        .toList());
    }

    @Override
    public AttachmentMetadata export() {
        try {
            byte[] diagnostics =
                    json.writerWithDefaultPrettyPrinter().writeValueAsBytes(toJson(read(MAX_EXPORT_RECORDS)));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("diagnostics.json"));
                zip.write(diagnostics);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("README.txt"));
                zip.write(("JavaClaw 4.0 sanitized diagnostics\n"
                                + "No credentials, environment variables, prompts, model output, "
                                + "attachments, or raw file paths are included.\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (bytes.size() > MAX_EXPORT_BYTES) {
                throw new IllegalStateException("diagnostic export exceeds 8 MiB");
            }
            return attachments.put(new ByteArrayInputStream(bytes.toByteArray()), "application/zip");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot create diagnostic export", failure);
        }
    }

    private String validDetails(String value) {
        try {
            var parsed = json.readTree(value);
            return parsed == null ? "\"[invalid diagnostic JSON]\"" : json.writeValueAsString(parsed);
        } catch (Exception ignored) {
            return "\"[invalid diagnostic JSON]\"";
        }
    }

    private ObjectNode toJson(DiagnosticsSnapshot value) {
        ObjectNode result = json.createObjectNode();
        result.put("format", value.format());
        result.put("generatedAt", value.generatedAt().toString());
        result.put("javaVersion", value.javaVersion());
        result.put("osName", value.osName());
        result.put("osArch", value.osArch());
        result.put("availableProcessors", value.availableProcessors());
        result.put("credentialsIncluded", value.credentialsIncluded());
        result.put("environmentIncluded", value.environmentIncluded());
        ArrayNode records = result.putArray("records");
        value.records().forEach(item -> {
            ObjectNode record = records.addObject();
            record.put("id", item.id());
            record.put("severity", item.severity());
            record.put("component", item.component());
            record.put("code", item.code());
            record.put("message", item.message());
            try {
                record.set("details", json.readTree(item.detailsJson()));
            } catch (Exception impossible) {
                record.put("details", "[invalid diagnostic JSON]");
            }
            record.put("createdAt", item.createdAt().toString());
        });
        return result;
    }
}
