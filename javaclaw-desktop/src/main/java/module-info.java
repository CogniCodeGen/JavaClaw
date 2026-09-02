/** JavaClaw JavaFX SDK 客户端。 */
module com.javaclaw.desktop {
    requires com.javaclaw.builtin.contracts;
    requires com.javaclaw.client;
    requires com.javaclaw.extension.spi;
    requires com.javaclaw.nativehosts;
    requires com.javaclaw.protocol;
    requires javafx.controls;
    requires javafx.fxml;
    requires java.prefs;

    exports com.javaclaw.desktop;
    exports com.javaclaw.desktop.shell;
    exports com.javaclaw.desktop.state;
    exports com.javaclaw.desktop.view;

    opens com.javaclaw.desktop to
            javafx.fxml;
    opens com.javaclaw.desktop.shell to
            javafx.fxml;
}
