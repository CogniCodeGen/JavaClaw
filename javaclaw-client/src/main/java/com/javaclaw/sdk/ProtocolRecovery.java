package com.javaclaw.sdk;

import com.fasterxml.jackson.databind.JsonNode;

record ProtocolRecovery(String threadId, JsonNode snapshot, JsonNode events, JsonNode liveItems) {}
