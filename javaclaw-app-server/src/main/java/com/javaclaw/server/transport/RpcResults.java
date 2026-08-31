package com.javaclaw.server.transport;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Small wire-result helpers shared by domain handlers. */
final class RpcResults {
    private RpcResults() {}

    static ObjectNode flag(String name, boolean value) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put(name, value);
        return result;
    }
}
