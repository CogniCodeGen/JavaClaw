package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ViewSchemaWireCodec;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BuiltinViewWireContractTest {
    @Test
    void 所有内置页面经服务端编码后均可由独立客户端解码() throws Exception {
        ViewSchemaWireCodec server = new ViewSchemaWireCodec(new CanonicalJson());
        ViewSchemaWireCodec client = new ViewSchemaWireCodec(new CanonicalJson());
        List<Executable> checks = new ArrayList<>();
        for (ExtensionBundle bundle : BuiltinExtensions.create()) {
            try (bundle) {
                var started = new BuiltinExtensionTestSupport().start(bundle);
                for (var contribution : started.contributions()) {
                    if (contribution instanceof ExtensionContributions.View view) {
                        ViewSchema schema = view.view();
                        checks.add(() -> {
                            CanonicalPayload encoded = server.encode(schema);
                            assertEquals(encoded, client.encode(client.decode(encoded)), schema.viewId());
                        });
                    }
                }
            }
        }
        assertFalse(checks.isEmpty());
        assertAll(checks);
    }
}
