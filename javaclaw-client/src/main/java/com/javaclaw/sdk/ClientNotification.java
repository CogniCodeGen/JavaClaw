package com.javaclaw.sdk;

/** Typed SDK notification union; protocol method names and JSON trees stay internal. */
public sealed interface ClientNotification
        permits EventNotification,
                ItemDeltaNotification,
                ApprovalRequestedNotification,
                UserInputRequestedNotification,
                McpAuthorizationRequestedNotification,
                McpStatusChangedNotification,
                ResyncRequiredNotification,
                UnknownNotification {}
