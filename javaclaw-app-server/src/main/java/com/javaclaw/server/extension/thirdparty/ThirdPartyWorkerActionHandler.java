package com.javaclaw.server.extension.thirdparty;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.NetworkBroker;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.extension.spi.ExtensionDocumentStore;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProtocolException;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ThirdPartyDocumentStore;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ThirdPartyStorageQuota;

/** 在 Host 内执行 Worker 请求的受限 document/blob/网络操作。 */
final class ThirdPartyWorkerActionHandler {
    private static final int MAX_ACTIONS_PER_ROUND = 16;

    private final H2Database database;
    private final CanonicalJson json;
    private final Clock clock;
    private final NetworkBroker network;

    ThirdPartyWorkerActionHandler(H2Database database, CanonicalJson json, Clock clock, NetworkBroker network) {
        this.database = Objects.requireNonNull(database, "database");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.network = Objects.requireNonNull(network, "network");
    }

    List<ThirdPartyWorkerProtocol.ActionResult> execute(
            InstalledThirdPartyBundle bundle,
            PermissionProfile permission,
            CancellationToken cancellation,
            List<ThirdPartyWorkerProtocol.Action> actions) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(cancellation, "cancellation");
        List<ThirdPartyWorkerProtocol.Action> requested = List.copyOf(actions);
        if (requested.isEmpty() || requested.size() > MAX_ACTIONS_PER_ROUND) {
            throw new IllegalArgumentException("worker action count is outside the allowed range");
        }
        requireUniqueIds(requested);
        ExtensionDocumentStore store = new H2ThirdPartyDocumentStore(
                database, bundle.descriptor().id(), ThirdPartyStorageQuota.defaults(), clock);
        ArrayList<ThirdPartyWorkerProtocol.ActionResult> results = new ArrayList<>(requested.size());
        for (ThirdPartyWorkerProtocol.Action action : requested) {
            cancellation.throwIfCancelled();
            results.add(execute(store, permission, cancellation, action));
        }
        return List.copyOf(results);
    }

    private ThirdPartyWorkerProtocol.ActionResult execute(
            ExtensionDocumentStore store,
            PermissionProfile permission,
            CancellationToken cancellation,
            ThirdPartyWorkerProtocol.Action action) {
        try {
            CanonicalPayload payload =
                    switch (action.kind()) {
                        case DOCUMENT_GET -> get(store, action.arguments());
                        case DOCUMENT_PUT -> put(store, action.arguments());
                        case BLOB_PUT -> putBlob(store, action.arguments());
                        case NETWORK_EXCHANGE -> exchange(action.arguments(), permission, cancellation);
                    };
            return ThirdPartyWorkerProtocol.ActionResult.success(action.id(), payload);
        } catch (TurnCancelledException cancellationFailure) {
            throw cancellationFailure;
        } catch (IllegalArgumentException | ProtocolException failure) {
            return ThirdPartyWorkerProtocol.ActionResult.failure(action.id(), "INVALID_ARGUMENT", message(failure));
        } catch (SecurityException failure) {
            return ThirdPartyWorkerProtocol.ActionResult.failure(
                    action.id(), "PERMISSION_DENIED", "Network Broker denied the request");
        } catch (PersistenceException failure) {
            return ThirdPartyWorkerProtocol.ActionResult.failure(
                    action.id(), failure.kind().name(), message(failure));
        } catch (Exception failure) {
            return ThirdPartyWorkerProtocol.ActionResult.failure(
                    action.id(), "NETWORK_FAILED", "Network Broker request failed");
        }
    }

    private CanonicalPayload exchange(
            CanonicalPayload payload, PermissionProfile permission, CancellationToken cancellation) throws Exception {
        BrokerRequest request = json.decode(payload, BrokerRequest.class);
        return json.encode(network.exchange(request, permission, cancellation));
    }

    private CanonicalPayload get(ExtensionDocumentStore store, CanonicalPayload payload) {
        DocumentGet arguments = json.decode(payload, DocumentGet.class);
        Optional<VersionedDocument> document = store.get(arguments.key());
        if (document.isEmpty()) {
            return json.encode(new DocumentResult(false, Optional.empty(), 0, Optional.empty(), Optional.empty()));
        }
        VersionedDocument found = document.orElseThrow();
        return json.encode(new DocumentResult(
                true,
                Optional.of(found.key()),
                found.revision(),
                Optional.of(found.payload()),
                Optional.of(found.updatedAt())));
    }

    private CanonicalPayload put(ExtensionDocumentStore store, CanonicalPayload payload) {
        DocumentPut arguments = json.decode(payload, DocumentPut.class);
        long revision = store.put(arguments.key(), arguments.expectedRevision(), arguments.payload());
        return json.encode(new RevisionResult(revision));
    }

    private CanonicalPayload putBlob(ExtensionDocumentStore store, CanonicalPayload payload) {
        BlobPut arguments = json.decode(payload, BlobPut.class);
        byte[] content;
        try {
            content = Base64.getDecoder().decode(arguments.contentBase64());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("contentBase64 is invalid", failure);
        }
        if (content.length != arguments.sizeBytes()) {
            throw new IllegalArgumentException("decoded Blob size differs from sizeBytes");
        }
        String digest = store.putBlob(new ByteArrayInputStream(content), arguments.sizeBytes(), arguments.mediaType());
        return json.encode(new BlobResult(digest));
    }

    private static void requireUniqueIds(List<ThirdPartyWorkerProtocol.Action> actions) {
        HashSet<String> ids = new HashSet<>();
        if (actions.stream().anyMatch(action -> !ids.add(action.id()))) {
            throw new IllegalArgumentException("worker action ids must be unique within a round");
        }
    }

    private static String message(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private record DocumentGet(String key) {
        private DocumentGet {
            key = Objects.requireNonNull(key, "key");
        }
    }

    private record DocumentPut(String key, long expectedRevision, CanonicalPayload payload) {
        private DocumentPut {
            key = Objects.requireNonNull(key, "key");
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must not be negative");
            }
            Objects.requireNonNull(payload, "payload");
        }
    }

    private record BlobPut(String mediaType, long sizeBytes, String contentBase64) {
        private BlobPut {
            mediaType = Objects.requireNonNull(mediaType, "mediaType");
            contentBase64 = Objects.requireNonNull(contentBase64, "contentBase64");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
        }
    }

    private record DocumentResult(
            boolean present,
            Optional<String> key,
            long revision,
            Optional<CanonicalPayload> payload,
            Optional<Instant> updatedAt) {}

    private record RevisionResult(long revision) {}

    private record BlobResult(String digest) {}
}
