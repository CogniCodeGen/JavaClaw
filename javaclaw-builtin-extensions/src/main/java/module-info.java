/** JavaClaw 随发行版签名、进程内装配的内置扩展。 */
module com.javaclaw.builtin.extensions {
    requires com.javaclaw.api;
    requires com.javaclaw.builtin.contracts;
    requires transitive com.javaclaw.extension.spi;
    requires org.slf4j;
    requires org.quartz;

    exports com.javaclaw.builtin.extensions;
}
