package com.javaclaw.server.extension.thirdparty;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.extension.spi.ContributionKind;

/** 第三方 Bundle 中经过签名的不可变 manifest。 */
record ThirdPartyBundleManifest(
        int formatVersion,
        String id,
        String displayName,
        String version,
        String signingKeyId,
        EntryPoint entryPoint,
        PermissionRequest permissions,
        List<Contribution> contributions,
        List<SchemaDocument> schemas,
        List<BundleFile> files) {
    static final int FORMAT_VERSION = 1;

    ThirdPartyBundleManifest {
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported bundle formatVersion: " + formatVersion);
        }
        id = text(id, "id");
        if (!id.matches("[a-z][a-z0-9.-]{1,63}")) {
            throw new IllegalArgumentException("extension id must use lowercase DNS-like characters");
        }
        displayName = text(displayName, "displayName");
        version = text(version, "version");
        signingKeyId = text(signingKeyId, "signingKeyId");
        if (!signingKeyId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("invalid signingKeyId");
        }
        Objects.requireNonNull(entryPoint, "entryPoint");
        Objects.requireNonNull(permissions, "permissions");
        contributions = List.copyOf(contributions);
        schemas = List.copyOf(schemas);
        files = List.copyOf(files);
        requireUnique(contributions.stream().map(Contribution::contributionId).toList(), "contributionId");
        requireUnique(schemas.stream().map(SchemaDocument::schemaId).toList(), "schemaId");
        requireUnique(files.stream().map(BundleFile::path).toList(), "file path");
        if (files.stream().noneMatch(file -> file.path().equals(entryPoint.executable()))) {
            throw new IllegalArgumentException("entryPoint executable must be declared in files");
        }
    }

    Set<ContributionKind> contributionKinds() {
        return contributions.stream().map(Contribution::kind).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    record EntryPoint(String executable, List<String> arguments) {
        EntryPoint {
            executable = relativePath(executable, "executable");
            arguments = List.copyOf(arguments);
            if (arguments.stream().anyMatch(value -> value == null || value.isBlank() || value.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("entryPoint arguments must be non-blank and contain no NUL");
            }
        }

        String executableName() {
            return java.nio.file.Path.of(executable).getFileName().toString();
        }
    }

    record PermissionRequest(
            boolean workspaceRead,
            boolean workspaceWrite,
            boolean allowDelete,
            Set<String> networkHosts,
            Set<Integer> networkPorts,
            boolean tlsOnly,
            Duration maxRunTime,
            ResourceLimits resources,
            ToolRisk maximumToolRisk) {
        PermissionRequest {
            networkHosts = Set.copyOf(networkHosts);
            networkPorts = Set.copyOf(networkPorts);
            Objects.requireNonNull(maxRunTime, "maxRunTime");
            Objects.requireNonNull(resources, "resources");
            Objects.requireNonNull(maximumToolRisk, "maximumToolRisk");
            if (workspaceWrite && !workspaceRead) {
                throw new IllegalArgumentException("workspaceWrite requires workspaceRead");
            }
            if (allowDelete && !workspaceWrite) {
                throw new IllegalArgumentException("allowDelete requires workspaceWrite");
            }
        }
    }

    record Contribution(
            String contributionId,
            ContributionKind kind,
            Set<String> operations,
            Optional<CanonicalPayload> descriptor) {
        Contribution {
            contributionId = text(contributionId, "contributionId");
            Objects.requireNonNull(kind, "kind");
            operations = Set.copyOf(operations);
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            if (operations.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("contribution operations must be non-blank");
            }
            boolean operationKind = kind == ContributionKind.QUERY
                    || kind == ContributionKind.COMMAND
                    || kind == ContributionKind.ORCHESTRATOR;
            if (operationKind && operations.isEmpty()) {
                throw new IllegalArgumentException(kind + " contribution requires operations");
            }
            if (!operationKind && !operations.isEmpty()) {
                throw new IllegalArgumentException(kind + " contribution must not declare operations");
            }
            boolean descriptorRequired = kind != ContributionKind.QUERY
                    && kind != ContributionKind.COMMAND
                    && kind != ContributionKind.ORCHESTRATOR;
            if (descriptorRequired != descriptor.isPresent()) {
                throw new IllegalArgumentException(kind + " descriptor presence is invalid");
            }
        }
    }

    record SchemaDocument(String schemaId, CanonicalPayload schema) {
        SchemaDocument {
            schemaId = text(schemaId, "schemaId");
            Objects.requireNonNull(schema, "schema");
        }
    }

    record BundleFile(String path, String sha256, long sizeBytes) {
        BundleFile {
            path = relativePath(path, "path");
            sha256 = text(sha256, "sha256").toLowerCase(java.util.Locale.ROOT);
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("file sha256 must be lowercase hexadecimal");
            }
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("file sizeBytes must not be negative");
            }
        }
    }

    private static void requireUnique(List<String> values, String name) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("duplicate " + name);
        }
    }

    private static String relativePath(String value, String name) {
        return BundlePathNames.requireRelative(text(value, name), name);
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
