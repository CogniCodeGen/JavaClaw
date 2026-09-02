/** JavaClaw 扩展契约；实现细节由 App Server 组合。 */
module com.javaclaw.extension.spi {
    requires transitive com.javaclaw.api;

    exports com.javaclaw.extension.spi;
}
