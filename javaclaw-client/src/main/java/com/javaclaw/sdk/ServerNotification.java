package com.javaclaw.sdk;

import com.fasterxml.jackson.databind.JsonNode;

record ServerNotification(String method, JsonNode params) {}
