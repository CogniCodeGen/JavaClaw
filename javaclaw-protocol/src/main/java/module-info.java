/** JavaClaw 本地 App Protocol v3。 */
module com.javaclaw.protocol {
    requires transitive com.javaclaw.api;
    requires transitive com.javaclaw.extension.spi;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jdk8;
    requires com.fasterxml.jackson.datatype.jsr310;

    exports com.javaclaw.protocol;
}
