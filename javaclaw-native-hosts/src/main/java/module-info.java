/** JavaClaw 的 FFM、Sandbox、PTY、平台传输与登录启动实现。 */
module com.javaclaw.nativehosts {
    requires com.javaclaw.api;
    requires com.javaclaw.extension.spi;
    requires com.javaclaw.protocol;
    requires java.desktop;

    exports com.javaclaw.nativehost.startup;
    exports com.javaclaw.nativehost.tray;
    exports com.javaclaw.nativehost.credential;
    exports com.javaclaw.nativehost.process;
    exports com.javaclaw.nativehost.sandbox;
    exports com.javaclaw.nativehost.transport;
}
