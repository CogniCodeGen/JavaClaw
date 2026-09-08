package com.javaclaw.builtin.extensions;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExtensionResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionalTestManagedStoreTest {
    @Test
    void failedCommandRollsBackDocumentHeadHistoryAndReceiptSoTheSameIdentityCanRetry() throws Exception {
        var store = new BuiltinExtensionTestSupport.InMemoryManagedStore();
        var payload = new CanonicalPayload("{\"revision\":1}");
        assertThrows(
                IllegalStateException.class,
                () -> store.inCommand(MemoryStoreAccess.ID, "write", "identity", "digest", transaction -> {
                    transaction.put("documents", "item", 0, payload);
                    throw new IllegalStateException("failed after write");
                }));
        assertTrue(store.get("documents", "item").isEmpty());
        assertTrue(store.history("documents", "item", 0, 10).isEmpty());
        assertTrue(store.recoverCommand(MemoryStoreAccess.ID, "write", "identity", "digest")
                .isEmpty());
        var response = store.inCommand(MemoryStoreAccess.ID, "write", "identity", "digest", transaction -> {
            transaction.put("documents", "item", 0, payload);
            return new ExtensionResponse(payload, 1);
        });
        assertEquals(1, response.revision());
        assertEquals(1, store.history("documents", "item", 0, 10).size());
        assertEquals(response, store.inCommand(MemoryStoreAccess.ID, "write", "identity", "digest", transaction -> {
            throw new AssertionError("replay must not run the mutation");
        }));
    }
}
