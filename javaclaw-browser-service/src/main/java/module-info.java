/** JavaClaw 进程外 Browser Worker 与宿主客户端。 */
module com.javaclaw.browser.service {
    requires com.javaclaw.api;
    requires com.javaclaw.builtin.contracts;
    requires com.javaclaw.nativehosts;
    requires com.javaclaw.protocol;
    requires playwright;

    exports com.javaclaw.browser.client;
}
