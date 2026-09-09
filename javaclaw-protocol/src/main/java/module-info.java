/** JavaClaw 本地 App Protocol v3。 */
module com.javaclaw.protocol {
    requires transitive com.javaclaw.api;
    requires transitive com.javaclaw.extension.spi;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jdk8;
    requires com.fasterxml.jackson.datatype.jsr310;

    exports com.javaclaw.protocol;

    // ViewSchema 的私有 wire record 由 Jackson 构造；仅向 codec 开放反射，保持其他模块的封装边界。
    opens com.javaclaw.protocol to
            com.fasterxml.jackson.databind;
}
