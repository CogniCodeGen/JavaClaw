/** JavaClaw Protocol v2 Java SDK、CLI 与本地 Transport。 */
module com.javaclaw.client {
    requires transitive com.javaclaw.api;
    requires transitive com.javaclaw.builtin.contracts;
    requires transitive com.javaclaw.extension.spi;
    requires transitive com.javaclaw.protocol;

    exports com.javaclaw.client;
    exports com.javaclaw.client.sdk;
    exports com.javaclaw.client.facade;
    exports com.javaclaw.client.extension;
    exports com.javaclaw.client.transport;
}
